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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and writer.
 *
 * <p>
 * Rodin bundles no JSON library, and the bridge protocol is small enough that
 * pulling one in would cost more than it saves. Parsed documents use
 * {@link Map}, {@link List}, {@link String}, {@link Long}, {@link Double},
 * {@link Boolean} and {@code null}; the writer accepts the same. Malformed
 * input raises {@link IllegalArgumentException}.
 * </p>
 */
public final class Json {

	private Json() {
		// utility class
	}

	/** Parse a complete JSON document. */
	public static Object parse(String text) {
		final Parser parser = new Parser(text);
		parser.skipWhitespace();
		final Object value = parser.value();
		parser.skipWhitespace();
		if (!parser.atEnd()) {
			throw new IllegalArgumentException(
					"trailing content at offset " + parser.offset);
		}
		return value;
	}

	/** Render a value as JSON. */
	public static String write(Object value) {
		final StringBuilder out = new StringBuilder();
		writeValue(value, out);
		return out.toString();
	}

	/** Build a map from alternating key/value arguments, preserving order. */
	public static Map<String, Object> map(Object... keysAndValues) {
		if (keysAndValues.length % 2 != 0) {
			throw new IllegalArgumentException("odd number of arguments");
		}
		final Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			result.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return result;
	}

	/** The value as a map, or {@code null} if it is not one. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object value) {
		return value instanceof Map ? (Map<String, Object>) value : null;
	}

	/** The value as a list, or {@code null} if it is not one. */
	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object value) {
		return value instanceof List ? (List<Object>) value : null;
	}

	/** The value as a string, or {@code null} if it is not one. */
	public static String asString(Object value) {
		return value instanceof String ? (String) value : null;
	}

	/** The value as a boolean, or {@code fallback} if it is not one. */
	public static boolean asBoolean(Object value, boolean fallback) {
		return value instanceof Boolean ? ((Boolean) value).booleanValue()
				: fallback;
	}

	private static void writeValue(Object value, StringBuilder out) {
		if (value == null) {
			out.append("null");
		} else if (value instanceof String) {
			writeString((String) value, out);
		} else if (value instanceof Boolean || value instanceof Long) {
			out.append(value);
		} else if (value instanceof Map) {
			writeObject(asMap(value), out);
		} else if (value instanceof List) {
			writeArray(asList(value), out);
		} else {
			throw new IllegalArgumentException(
					"not a JSON value: " + value.getClass().getName());
		}
	}

	private static void writeObject(Map<String, Object> members,
			StringBuilder out) {
		out.append('{');
		boolean first = true;
		for (final Map.Entry<String, Object> member : members.entrySet()) {
			if (!first) {
				out.append(',');
			}
			first = false;
			writeString(member.getKey(), out);
			out.append(':');
			writeValue(member.getValue(), out);
		}
		out.append('}');
	}

	private static void writeArray(List<Object> elements, StringBuilder out) {
		out.append('[');
		for (int i = 0; i < elements.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			writeValue(elements.get(i), out);
		}
		out.append(']');
	}

	private static void writeString(String text, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			switch (c) {
			case '"':
				out.append("\\\"");
				break;
			case '\\':
				out.append("\\\\");
				break;
			case '\b':
				out.append("\\b");
				break;
			case '\f':
				out.append("\\f");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\t':
				out.append("\\t");
				break;
			default:
				// Control characters must be escaped; everything else, including
				// non-ASCII, goes out verbatim because the transport is UTF-8.
				if (c < 0x20) {
					out.append(String.format("\\u%04x", Integer.valueOf(c)));
				} else {
					out.append(c);
				}
			}
		}
		out.append('"');
	}

	/** A recursive-descent parser over one document. */
	private static final class Parser {

		private final String text;
		int offset;

		Parser(String text) {
			this.text = text;
		}

		boolean atEnd() {
			return offset >= text.length();
		}

		void skipWhitespace() {
			while (!atEnd()) {
				final char c = text.charAt(offset);
				if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
					return;
				}
				offset++;
			}
		}

		Object value() {
			if (atEnd()) {
				throw new IllegalArgumentException("unexpected end of input");
			}
			final char c = text.charAt(offset);
			switch (c) {
			case '{':
				return object();
			case '[':
				return array();
			case '"':
				return string();
			case 't':
				expect("true");
				return Boolean.TRUE;
			case 'f':
				expect("false");
				return Boolean.FALSE;
			case 'n':
				expect("null");
				return null;
			default:
				return number();
			}
		}

		private Map<String, Object> object() {
			offset++; // '{'
			final Map<String, Object> members = new LinkedHashMap<>();
			skipWhitespace();
			if (peek() == '}') {
				offset++;
				return members;
			}
			while (true) {
				skipWhitespace();
				final String key = string();
				skipWhitespace();
				require(':');
				skipWhitespace();
				members.put(key, value());
				skipWhitespace();
				final char c = peek();
				offset++;
				if (c == '}') {
					return members;
				}
				if (c != ',') {
					throw new IllegalArgumentException(
							"expected ',' or '}' at offset " + (offset - 1));
				}
			}
		}

		private List<Object> array() {
			offset++; // '['
			final List<Object> elements = new ArrayList<>();
			skipWhitespace();
			if (peek() == ']') {
				offset++;
				return elements;
			}
			while (true) {
				skipWhitespace();
				elements.add(value());
				skipWhitespace();
				final char c = peek();
				offset++;
				if (c == ']') {
					return elements;
				}
				if (c != ',') {
					throw new IllegalArgumentException(
							"expected ',' or ']' at offset " + (offset - 1));
				}
			}
		}

		private String string() {
			require('"');
			final StringBuilder out = new StringBuilder();
			while (true) {
				if (atEnd()) {
					throw new IllegalArgumentException(
							"unterminated string at offset " + offset);
				}
				final char c = text.charAt(offset++);
				if (c == '"') {
					return out.toString();
				}
				if (c != '\\') {
					out.append(c);
					continue;
				}
				if (atEnd()) {
					throw new IllegalArgumentException(
							"unterminated escape at offset " + offset);
				}
				final char escape = text.charAt(offset++);
				switch (escape) {
				case '"':
				case '\\':
				case '/':
					out.append(escape);
					break;
				case 'b':
					out.append('\b');
					break;
				case 'f':
					out.append('\f');
					break;
				case 'n':
					out.append('\n');
					break;
				case 'r':
					out.append('\r');
					break;
				case 't':
					out.append('\t');
					break;
				case 'u':
					if (offset + 4 > text.length()) {
						throw new IllegalArgumentException(
								"truncated \\u escape at offset " + offset);
					}
					out.append((char) Integer
							.parseInt(text.substring(offset, offset + 4), 16));
					offset += 4;
					break;
				default:
					throw new IllegalArgumentException(
							"bad escape '\\" + escape + "' at offset "
									+ (offset - 1));
				}
			}
		}

		private Object number() {
			final int start = offset;
			if (peek() == '-') {
				offset++;
			}
			boolean fractional = false;
			while (!atEnd()) {
				final char c = text.charAt(offset);
				if (c >= '0' && c <= '9') {
					offset++;
				} else if (c == '.' || c == 'e' || c == 'E' || c == '+'
						|| c == '-') {
					fractional = true;
					offset++;
				} else {
					break;
				}
			}
			if (offset == start) {
				throw new IllegalArgumentException(
						"expected a value at offset " + start);
			}
			final String literal = text.substring(start, offset);
			try {
				return fractional ? (Object) Double.valueOf(literal)
						: (Object) Long.valueOf(literal);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(
						"bad number '" + literal + "' at offset " + start, e);
			}
		}

		private char peek() {
			if (atEnd()) {
				throw new IllegalArgumentException("unexpected end of input");
			}
			return text.charAt(offset);
		}

		private void require(char expected) {
			if (peek() != expected) {
				throw new IllegalArgumentException("expected '" + expected
						+ "' at offset " + offset);
			}
			offset++;
		}

		private void expect(String literal) {
			if (!text.startsWith(literal, offset)) {
				throw new IllegalArgumentException(
						"expected '" + literal + "' at offset " + offset);
			}
			offset += literal.length();
		}
	}
}
