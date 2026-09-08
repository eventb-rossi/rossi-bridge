/*
 * Copyright (c) 2026 eventb-rossi. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eventb.rossi.bridge;

import java.util.Arrays;
import java.util.Comparator;

import org.rodinp.core.IAttributeValue;
import org.rodinp.core.IInternalElement;
import org.rodinp.core.IRodinElement;
import org.rodinp.core.IRodinFile;
import org.rodinp.core.RodinDBException;
import org.rodinp.internal.core.version.VersionManager;

/**
 * Serialise a Rodin element tree as the XML a client can parse.
 *
 * <p>
 * Rodin holds an open file as a DOM document and saves it verbatim, and that
 * document is reachable through {@code RodinDBManager}. Walking the element
 * handles instead is deliberate: this runs on a job thread while the user may
 * be typing, and the handle API reads through the database's element-info
 * machinery, whereas traversing the DOM directly would race the edit that
 * prompted the walk.
 * </p>
 *
 * <p>
 * The mapping is total, which is what makes the walk faithful: the tag is the
 * element type's id, an element's name is the {@code name} attribute, an
 * attribute's XML name is its type's id, and child order is document order.
 * The output need not be byte-identical to a save, only parse to the same
 * component: the client compares components, not bytes.
 * </p>
 */
final class ModelXml {

	/** Attributes come back in no particular order; emit them in a fixed one. */
	private static final Comparator<IAttributeValue> BY_ID = Comparator
			.comparing(value -> value.getType().getId());

	private ModelXml() {
		// utility class
	}

	/** The whole file, root element included. */
	static String of(IRodinFile file) throws RodinDBException {
		final StringBuilder out = new StringBuilder();
		out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		// The root carries the format version and, unlike every other element,
		// no name. The version is load-bearing: a client re-emits it, so an
		// absent one reads as a change. Ask the platform rather than the file,
		// which is both constant-time and right for a component created in
		// Rodin and not yet saved.
		final String version = Long.toString(
				VersionManager.getInstance().getVersion(file.getRootElementType()));
		write(file.getRoot(), version, out);
		return out.toString();
	}

	private static void write(IInternalElement element, String version,
			StringBuilder out) throws RodinDBException {
		final String tag = element.getElementType().getId();
		out.append('<').append(tag);
		if (version == null) {
			// Not the root: the name is the one attribute the element-type
			// registry filters out of the values, so it comes from the handle.
			attribute("name", element.getElementName(), out);
		} else {
			attribute("version", version, out);
		}

		final IAttributeValue[] values = element.getAttributeValues();
		Arrays.sort(values, BY_ID);
		for (final IAttributeValue value : values) {
			attribute(value.getType().getId(), raw(value), out);
		}

		final IRodinElement[] children = element.getChildren();
		if (children.length == 0) {
			out.append("/>\n");
			return;
		}
		out.append(">\n");
		for (final IRodinElement child : children) {
			if (child instanceof IInternalElement internal) {
				write(internal, null, out);
			}
		}
		out.append("</").append(tag).append(">\n");
	}

	/**
	 * An attribute's value in the spelling Rodin stores. The five raw forms
	 * are the ones {@code org.rodinp.internal.core.AttributeType} writes.
	 */
	private static String raw(IAttributeValue value) {
		if (value instanceof IAttributeValue.Boolean typed) {
			return java.lang.Boolean.toString(typed.getValue());
		}
		if (value instanceof IAttributeValue.Handle typed) {
			return typed.getValue().getHandleIdentifier();
		}
		if (value instanceof IAttributeValue.Integer typed) {
			return java.lang.Integer.toString(typed.getValue());
		}
		if (value instanceof IAttributeValue.Long typed) {
			return java.lang.Long.toString(typed.getValue());
		}
		return ((IAttributeValue.String) value).getValue();
	}

	private static void attribute(String name, String value, StringBuilder out) {
		out.append(' ').append(name).append("=\"");
		escape(value, out);
		out.append('"');
	}

	private static void escape(String value, StringBuilder out) {
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			switch (c) {
			case '&':
				out.append("&amp;");
				break;
			case '<':
				out.append("&lt;");
				break;
			case '>':
				out.append("&gt;");
				break;
			case '"':
				out.append("&quot;");
				break;
			case '\n':
				out.append("&#10;");
				break;
			case '\r':
				out.append("&#13;");
				break;
			case '\t':
				out.append("&#9;");
				break;
			default:
				out.append(c);
			}
		}
	}
}
