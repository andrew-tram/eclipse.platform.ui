/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.filebuffers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnmappableCharacterException;
import java.nio.charset.UnsupportedCharsetException;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;

import org.eclipse.core.resources.IResourceStatus;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBufferStatusCodes;
import org.eclipse.core.filebuffers.IPersistableAnnotationModel;
import org.eclipse.core.filebuffers.ITextFileBuffer;

import org.eclipse.jface.text.Assert;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.source.IAnnotationModel;

/**
 * @since 3.0
 */
public class JavaTextFileBuffer extends JavaFileBuffer implements ITextFileBuffer {


	private class DocumentListener implements IDocumentListener {

		/*
		 * @see org.eclipse.jface.text.IDocumentListener#documentAboutToBeChanged(org.eclipse.jface.text.DocumentEvent)
		 */
		public void documentAboutToBeChanged(DocumentEvent event) {
		}

		/*
		 * @see org.eclipse.jface.text.IDocumentListener#documentChanged(org.eclipse.jface.text.DocumentEvent)
		 */
		public void documentChanged(DocumentEvent event) {
			fCanBeSaved= true;
			removeFileBufferContentListeners();
			fManager.fireDirtyStateChanged(JavaTextFileBuffer.this, fCanBeSaved);
		}
	}

	/**
	 * Reader chunk size.
	 */
	private static final int READER_CHUNK_SIZE= 2048;
	/**
	 * Buffer size.
	 */
	private static final int BUFFER_SIZE= 8 * READER_CHUNK_SIZE;
	/**
	 * Constant for representing the OK status. This is considered a value object.
	 */
	private static final IStatus STATUS_OK= new Status(IStatus.OK, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, FileBuffersMessages.FileBuffer_status_ok, null);
	/**
	 * Constant for representing the error status. This is considered a value object.
	 */
	private static final IStatus STATUS_ERROR= new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, FileBuffersMessages.FileBuffer_status_error, null);
	/**
	 * Constant denoting UTF-8 encoding.
	 */
	private static final String CHARSET_UTF_8= "UTF-8"; //$NON-NLS-1$

	/**
	 * Constant denoting an empty set of properties
	 * @since 3.1
	 */
	private static final QualifiedName[] NO_PROPERTIES= new QualifiedName[0];


	/** The element's document */
	protected IDocument fDocument;
	/** The encoding used to create the document from the storage or <code>null</code> for workbench encoding. */
	protected String fEncoding;
	/** Internal document listener */
	protected IDocumentListener fDocumentListener= new DocumentListener();
	/** The encoding which has explicitly been set on the file. */
	private String fExplicitEncoding;
	/** Tells whether the file on disk has a BOM. */
	private boolean fHasBOM;
	/** The annotation model of this file buffer */
    private IAnnotationModel fAnnotationModel;
	/**
	 * Lock for lazy creation of annotation model.
	 * @since 3.2
	 */
	private final Object fAnnotationModelCreationLock= new Object();


	public JavaTextFileBuffer(TextFileBufferManager manager) {
		super(manager);
	}

	/*
	 * @see org.eclipse.core.buffer.text.IBufferedTextFile#getDocument()
	 */
	public IDocument getDocument() {
		return fDocument;
	}

	/*
	 * @see org.eclipse.core.filebuffers.ITextFileBuffer#getAnnotationModel()
	 */
	public IAnnotationModel getAnnotationModel() {
		synchronized (fAnnotationModelCreationLock) {
			if (fAnnotationModel == null && !isDisconnected()) {
				fAnnotationModel= fManager.createAnnotationModel(getLocation());
				if (fAnnotationModel != null)
					fAnnotationModel.connect(fDocument);
			}
		}
		return fAnnotationModel;
	}

	/*
	 * @see org.eclipse.core.buffer.text.IBufferedTextFile#getEncoding()
	 */
	public String getEncoding() {
		return fEncoding;
	}

	/*
	 * @see org.eclipse.core.buffer.text.IBufferedTextFile#setEncoding(java.lang.String)
	 */
	public void setEncoding(String encoding) {
		fExplicitEncoding= encoding;
		if (encoding == null || encoding.equals(fEncoding))
			cacheEncodingState(null);
		else {
			fEncoding= encoding;
			fHasBOM= false;
		}
	}

	/*
	 * @see org.eclipse.core.buffer.text.IBufferedFile#getStatus()
	 */
	public IStatus getStatus() {
		if (!isDisconnected()) {
			if (fStatus != null)
				return fStatus;
			return (fDocument == null ? STATUS_ERROR : STATUS_OK);
		}
		return STATUS_ERROR;
	}

	private InputStream getFileContents(IFileStore file, IProgressMonitor monitor) {
		try {
			if (file != null)
				return file.openInputStream(EFS.NONE, null);
		} catch (CoreException e) {
		}
		return null;
	}

	private void setFileContents(InputStream stream, boolean overwrite, IProgressMonitor monitor) {
		try {
			OutputStream out= fFileStore.openOutputStream(EFS.NONE, null);
			try {
				byte[] buffer= new byte[8192];
				while (true) {
					int bytesRead= -1;
					try {
						bytesRead= stream.read(buffer);
					} catch (IOException e) {
					}
					if (bytesRead == -1)
						break;
					try {
						out.write(buffer, 0, bytesRead);
					} catch (IOException e) {
					}
					if (monitor != null)
						monitor.worked(1);
				}
			} finally {
				try {
					stream.close();
				} catch (IOException e) {
				} finally {
					try {
						out.close();
					} catch (IOException e) {
					}
				}
			}
		} catch (CoreException e) {
		}
	}

	/*
	 * @see org.eclipse.core.filebuffers.IFileBuffer#revert(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void revert(IProgressMonitor monitor) throws CoreException {
		if (isDisconnected())
			return;

		IDocument original= null;
		fStatus= null;

		try {
			original= fManager.createEmptyDocument(getLocation());
			cacheEncodingState(monitor);
			setDocumentContent(original, fFileStore, fEncoding, monitor);
		} catch (CoreException x) {
			fStatus= x.getStatus();
		}

		if (original == null)
			return;

		String originalContents= original.get();
		boolean replaceContents= !originalContents.equals(fDocument.get());

		if (!replaceContents && !fCanBeSaved)
			return;

		fManager.fireStateChanging(this);
		try {

			if (replaceContents)  {
				fManager.fireBufferContentAboutToBeReplaced(this);
				fDocument.set(original.get());
			}

			boolean fireDirtyStateChanged= fCanBeSaved;
			if (fCanBeSaved) {
				fCanBeSaved= false;
				addFileBufferContentListeners();
			}

			if (replaceContents)
				fManager.fireBufferContentReplaced(this);

			if (fFileStore != null)
				fSynchronizationStamp= fFileStore.fetchInfo().getLastModified();

			if (fAnnotationModel instanceof IPersistableAnnotationModel) {
				IPersistableAnnotationModel persistableModel= (IPersistableAnnotationModel) fAnnotationModel;
				try {
				    persistableModel.revert(fDocument);
				} catch (CoreException x) {
					fStatus= x.getStatus();
				}
			}

			if (fireDirtyStateChanged)
				fManager.fireDirtyStateChanged(this, fCanBeSaved);

		} catch (RuntimeException x) {
			fManager.fireStateChangeFailed(this);
			throw x;
		}
	}

	/*
	 * @see org.eclipse.core.filebuffers.IFileBuffer#getContentType()
	 * @since 3.1
	 */
	public IContentType getContentType () throws CoreException {
		if (fFileStore == null)
			return null;

		InputStream stream= null;
		try {
			if (isDirty()) {
				Reader reader= new DocumentReader(getDocument());
				try {
					IContentDescription desc= Platform.getContentTypeManager().getDescriptionFor(reader, fFileStore.getName(), NO_PROPERTIES);
					if (desc != null && desc.getContentType() != null)
						return desc.getContentType();
				} finally {
					try {
						if (reader != null)
							reader.close();
					} catch (IOException ex) {
					}
				}
			}
			stream= fFileStore.openInputStream(EFS.NONE, null);
			IContentDescription desc= Platform.getContentTypeManager().getDescriptionFor(stream, fFileStore.getName(), NO_PROPERTIES);
			if (desc != null && desc.getContentType() != null)
				return desc.getContentType();
			return null;
		} catch (IOException x) {
			throw new CoreException(new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, NLSUtility.format(FileBuffersMessages.FileBuffer_error_queryContentDescription, fFileStore.toString()), x));
		} finally {
			try {
				if (stream != null)
					stream.close();
			} catch (IOException x) {
			}
		}
	}

	/*
	 * @see org.eclipse.core.internal.filebuffers.FileBuffer#addFileBufferContentListeners()
	 */
	protected void addFileBufferContentListeners() {
		if (fDocument != null)
			fDocument.addDocumentListener(fDocumentListener);
	}

	/*
	 * @see org.eclipse.core.internal.filebuffers.FileBuffer#removeFileBufferContentListeners()
	 */
	protected void removeFileBufferContentListeners() {
		if (fDocument != null)
			fDocument.removeDocumentListener(fDocumentListener);
	}

	/*
	 * @see org.eclipse.core.internal.filebuffers.FileBuffer#initializeFileBufferContent(org.eclipse.core.runtime.IProgressMonitor)
	 */
	protected void initializeFileBufferContent(IProgressMonitor monitor) throws CoreException {
		try {
			fDocument= fManager.createEmptyDocument(getLocation());
			cacheEncodingState(monitor);
			setDocumentContent(fDocument, fFileStore, fEncoding, monitor);
		} catch (CoreException x) {
			fDocument= fManager.createEmptyDocument(getLocation());
			fStatus= x.getStatus();
		}
	}

	/*
	 * @see org.eclipse.core.internal.filebuffers.ResourceFileBuffer#connected()
	 */
	protected void connected() {
		super.connected();
		if (fAnnotationModel != null)
			fAnnotationModel.connect(fDocument);
	}

	/*
	 * @see org.eclipse.core.internal.filebuffers.ResourceFileBuffer#disconnected()
	 */
	protected void disconnected() {
		if (fAnnotationModel != null)
			fAnnotationModel.disconnect(fDocument);
		super.disconnected();
	}

	protected void cacheEncodingState(IProgressMonitor monitor) {
		fEncoding= fExplicitEncoding;
		fHasBOM= false;

		InputStream stream= getFileContents(fFileStore, monitor);
		if (stream != null) {
			try {
				QualifiedName[] options= new QualifiedName[] { IContentDescription.CHARSET, IContentDescription.BYTE_ORDER_MARK };
				IContentDescription description= Platform.getContentTypeManager().getDescriptionFor(stream, fFileStore.getName(), options);
				if (description != null) {
					fHasBOM= description.getProperty(IContentDescription.BYTE_ORDER_MARK) != null;
					if (fEncoding == null)
						fEncoding= description.getCharset();
				}
			} catch (IOException e) {
				// do nothing
			} finally {
				try {
					stream.close();
				} catch (IOException ex) {
					FileBuffersPlugin.getDefault().getLog().log(new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, FileBuffersMessages.JavaTextFileBuffer_error_closeStream, ex));
				}
			}
		}
	}

	/*
	 * @see org.eclipse.core.internal.filebuffers.FileBuffer#commitFileBufferContent(org.eclipse.core.runtime.IProgressMonitor, boolean)
	 */
	protected void commitFileBufferContent(IProgressMonitor monitor, boolean overwrite) throws CoreException {
		String encoding= computeEncoding();

		Charset charset;
		try {
			charset= Charset.forName(encoding);
		} catch (UnsupportedCharsetException ex) {
			String message= NLSUtility.format(FileBuffersMessages.ResourceTextFileBuffer_error_unsupported_encoding_message_arg, encoding);
			IStatus s= new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, message, ex);
			throw new CoreException(s);
		} catch (IllegalCharsetNameException ex) {
			String message= NLSUtility.format(FileBuffersMessages.ResourceTextFileBuffer_error_illegal_encoding_message_arg, encoding);
			IStatus s= new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, message, ex);
			throw new CoreException(s);
		}

		CharsetEncoder encoder= charset.newEncoder();
		encoder.onMalformedInput(CodingErrorAction.REPLACE);
		encoder.onUnmappableCharacter(CodingErrorAction.REPORT);

		byte[] bytes;
		int bytesLength;

		try {
			ByteBuffer byteBuffer= encoder.encode(CharBuffer.wrap(fDocument.get()));
			bytesLength= byteBuffer.limit();
			if (byteBuffer.hasArray())
				bytes= byteBuffer.array();
			else {
				bytes= new byte[bytesLength];
				byteBuffer.get(bytes);
			}
		} catch (CharacterCodingException ex) {
			Assert.isTrue(ex instanceof UnmappableCharacterException);
			String message= NLSUtility.format(FileBuffersMessages.ResourceTextFileBuffer_error_charset_mapping_failed_message_arg, encoding);
			IStatus s= new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IFileBufferStatusCodes.CHARSET_MAPPING_FAILED, message, null);
			throw new CoreException(s);
		}

		IFileInfo fileInfo= fFileStore == null ? null : fFileStore.fetchInfo();
		if (fileInfo != null && fileInfo.exists()) {

			if (!overwrite)
				checkSynchronizationState();

			InputStream stream= new ByteArrayInputStream(bytes, 0, bytesLength);

			/*
			 * XXX:
			 * This is a workaround for a corresponding bug in Java readers and writer,
			 * see: http://developer.java.sun.com/developer/bugParade/bugs/4508058.html
			 */
			if (fHasBOM && CHARSET_UTF_8.equals(encoding))
				stream= new SequenceInputStream(new ByteArrayInputStream(IContentDescription.BOM_UTF_8), stream);


			// here the file synchronizer should actually be removed and afterwards added again. However,
			// we are already inside an operation, so the delta is sent AFTER we have added the listener
			setFileContents(stream, overwrite, monitor);
			// set synchronization stamp to know whether the file synchronizer must become active
			fSynchronizationStamp= fFileStore.fetchInfo().getLastModified();

			if (fAnnotationModel instanceof IPersistableAnnotationModel) {
				IPersistableAnnotationModel persistableModel= (IPersistableAnnotationModel) fAnnotationModel;
				persistableModel.commit(fDocument);
			}

		} else {

			fFileStore= FileBuffers.getFileStoreAtLocation(getLocation());
			fFileStore.getParent().mkdir(EFS.NONE, null);
			OutputStream out= fFileStore.openOutputStream(EFS.NONE, null);
			try {
				/*
				 * XXX:
				 * This is a workaround for a corresponding bug in Java readers and writer,
				 * see: http://developer.java.sun.com/developer/bugParade/bugs/4508058.html
				 */
				if (fHasBOM && CHARSET_UTF_8.equals(encoding))
					out.write(IContentDescription.BOM_UTF_8);

				out.write(bytes);
				out.flush();
			} catch (IOException x) {
				IStatus s= new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, x.getLocalizedMessage(), x);
				throw new CoreException(s);
			} finally {
				try {
					out.close();
				} catch (IOException x) {
				}
			}

			// set synchronization stamp to know whether the file synchronizer must become active
			fSynchronizationStamp= fFileStore.fetchInfo().getLastModified();

		}
	}

	private String computeEncoding() {
		// User-defined encoding has first priority
		if (fExplicitEncoding != null)
			return fExplicitEncoding;

		if (fFileStore != null) {
			// Probe content
			Reader reader= new DocumentReader(fDocument);
			try {
				QualifiedName[] options= new QualifiedName[] { IContentDescription.CHARSET, IContentDescription.BYTE_ORDER_MARK };
				IContentDescription description= Platform.getContentTypeManager().getDescriptionFor(reader, fFileStore.getName(), options);
				if (description != null) {
					String encoding= description.getCharset();
					if (encoding != null)
						return encoding;
				}
			} catch (IOException ex) {
				// try next strategy
			} finally {
				try {
					if (reader != null)
						reader.close();
				} catch (IOException x) {
				}
			}
		}

		// Use file's encoding if the file has a BOM
		if (fHasBOM)
			return fEncoding;

		// Use global default
		return fManager.getDefaultEncoding();
	}

	/**
	 * Initializes the given document with the given file's content using the given encoding.
	 *
	 * @param document the document to be initialized
	 * @param file the file which delivers the document content
	 * @param encoding the character encoding for reading the given stream
	 * @param monitor the progress monitor
	 * @exception CoreException if the given stream can not be read
	 */
	private void setDocumentContent(IDocument document, IFileStore file, String encoding, IProgressMonitor monitor) throws CoreException {
		InputStream contentStream= getFileContents(file, monitor);
		if (contentStream == null)
			return;

		Reader in= null;
		try {

			if (encoding == null)
				encoding= fManager.getDefaultEncoding();

			/*
			 * XXX:
			 * This is a workaround for a corresponding bug in Java readers and writer,
			 * see: http://developer.java.sun.com/developer/bugParade/bugs/4508058.html
			 */
			if (fHasBOM && CHARSET_UTF_8.equals(encoding)) {
				int n= 0;
				do {
					int bytes= contentStream.read(new byte[IContentDescription.BOM_UTF_8.length]);
					if (bytes == -1)
						throw new IOException();
					n += bytes;
				} while (n < IContentDescription.BOM_UTF_8.length);
			}

			in= new BufferedReader(new InputStreamReader(contentStream, encoding), BUFFER_SIZE);
			StringBuffer buffer= new StringBuffer(BUFFER_SIZE);
			char[] readBuffer= new char[READER_CHUNK_SIZE];
			int n= in.read(readBuffer);
			while (n > 0) {
				buffer.append(readBuffer, 0, n);
				n= in.read(readBuffer);
			}

			document.set(buffer.toString());

		} catch (IOException x) {
			String msg= x.getMessage() == null ? "" : x.getMessage(); //$NON-NLS-1$
			IStatus s= new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IStatus.OK, msg, x);
			throw new CoreException(s);
		} finally {
			try {
				if (in != null)
					in.close();
				else
					contentStream.close();
			} catch (IOException x) {
			}
		}
	}

	/**
	 * Checks whether the given file is synchronized with the the local file system.
	 * If the file has been changed, a <code>CoreException</code> is thrown.
	 *
	 * @exception CoreException if file has been changed on the file system
	 */
	private void checkSynchronizationState() throws CoreException {
		if (!isSynchronized()) {
			Status status= new Status(IStatus.ERROR, FileBuffersPlugin.PLUGIN_ID, IResourceStatus.OUT_OF_SYNC_LOCAL, FileBuffersMessages.FileBuffer_error_outOfSync, null);
			throw new CoreException(status);
		}
	}
}
