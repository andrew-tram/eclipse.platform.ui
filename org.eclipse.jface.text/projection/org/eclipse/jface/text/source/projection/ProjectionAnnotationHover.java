/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jface.text.source.projection;

import java.util.Iterator;

import org.eclipse.swt.widgets.Shell;

import org.eclipse.jface.resource.JFaceResources;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.IAnnotationHover;
import org.eclipse.jface.text.source.IAnnotationHoverExtension;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.ISourceViewerExtension2;

/**
 * Annotation hover for projection annotations.
 * <p>
 * Internal class. Do not use. Public for testing purposes only.
 * 
 * @since 3.0
 */
public class ProjectionAnnotationHover implements IAnnotationHover, IAnnotationHoverExtension {
	
	/*
	 * @see org.eclipse.jface.text.source.IAnnotationHover#getHoverInfo(org.eclipse.jface.text.source.ISourceViewer, int)
	 */
	public String getHoverInfo(ISourceViewer sourceViewer, int lineNumber) {
		// this is a no-op as semantics is defined by the implementation of the annotation hover extension
		return null;
	}
	
	/*
	 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension#getLineRange(org.eclipse.jface.text.source.ISourceViewer, int, int, int)
	 */
	public ITextSelection getLineRange(ISourceViewer viewer, int line, int first, int number) {
		return null; // go for the default behavior
	}

	/*
	 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension#getInformationControlCreator()
	 */
	public IInformationControlCreator getInformationControlCreator() {
		return new IInformationControlCreator() {
			public IInformationControl createInformationControl(Shell parent) {
				return new SourceViewerInformationControl(parent, JFaceResources.TEXT_FONT);
			}
		};
	}

	/*
	 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension#getHoverInfo(org.eclipse.jface.text.source.ISourceViewer, int, int, int)
	 */
	public String getHoverInfo(ISourceViewer sourceViewer, int lineNumber, int first, int number) {
		return getProjectionTextAtLine(sourceViewer, lineNumber);
	}
	
	private int compareRulerLine(Position position, IDocument document, int line) {
		if (position.getOffset() > -1 && position.getLength() > -1) {
			try {
				int startLine= document.getLineOfOffset(position.getOffset());
				if (line == startLine)
					return 1;
				if (startLine <= line && line <= document.getLineOfOffset(position.getOffset() + position.getLength()))
					return 2;
			} catch (BadLocationException x) {
			}
		}
		return 0;
	}
	
	private String getProjectionTextAtLine(ISourceViewer viewer, int line) {
		
		IAnnotationModel model= null;
		if (viewer instanceof ISourceViewerExtension2) {
			ISourceViewerExtension2 viewerExtension= (ISourceViewerExtension2) viewer;
			IAnnotationModel visual= viewerExtension.getVisualAnnotationModel();
			if (visual instanceof IAnnotationModelExtension) {
				IAnnotationModelExtension modelExtension= (IAnnotationModelExtension) visual;
				model= modelExtension.getAnnotationModel(ProjectionViewer.PROJECTION_ANNOTATION_MODEL);
			}
		}
		
		if (model != null) {
			try {
				IDocument document= viewer.getDocument();
				Iterator e= model.getAnnotationIterator();
				while (e.hasNext()) {
					ProjectionAnnotation annotation= (ProjectionAnnotation) e.next();
					if (!annotation.isFolded())
						continue;
					
					Position position= model.getPosition(annotation);
					if (position == null)
						continue;
					
					if (1 == compareRulerLine(position, document, line))
						return document.get(position.getOffset(), position.getLength());
				}
			} catch (BadLocationException x) {
			}
		}
		
		return null;
	}	
}
