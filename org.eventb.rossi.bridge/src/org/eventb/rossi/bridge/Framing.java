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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * LSP-style message framing: a {@code Content-Length} header, a blank line, and
 * that many bytes of UTF-8 payload.
 *
 * <p>
 * The language server on the other end already speaks this framing for its own
 * stdio transport, so reusing it costs neither side anything.
 * </p>
 */
public final class Framing {

	private static final String CONTENT_LENGTH = "Content-Length";

	/**
	 * The largest message this reader will accept. The protocol's requests run
	 * to a few hundred bytes; the cap is what keeps anything that reaches the
	 * port from making Rodin allocate whatever it cares to claim.
	 */
	private static final int MAX_CONTENT_LENGTH = 1 << 20;

	/**
	 * The most header lines, and the longest one, a message may carry. Without
	 * these a peer that never sends the blank line holds a session thread for
	 * ever, and one that never sends a newline grows the line buffer without
	 * bound. This side is the one an untrusted local process reaches first.
	 */
	private static final int MAX_HEADER_LINES = 32;

	private static final int MAX_HEADER_LINE_BYTES = 1 << 13;

	private Framing() {
		// utility class
	}

	/**
	 * Read one message, or {@code null} at end of stream.
	 */
	public static String read(InputStream in) throws IOException {
		int contentLength = -1;
		for (int seen = 0;; seen++) {
			if (seen >= MAX_HEADER_LINES) {
				throw new IOException("too many header lines");
			}
			final String line = readLine(in);
			if (line == null) {
				return null;
			}
			if (line.isEmpty()) {
				break;
			}
			final int colon = line.indexOf(':');
			if (colon < 0) {
				throw new IOException("malformed header line: " + line);
			}
			final String name = line.substring(0, colon).trim();
			// A header name is a token. Without this an HTTP request line
			// carrying a colon in its path -- "POST /a:b HTTP/1.1", which a
			// browser will send to any port on request -- reads as a header,
			// and the request's own Content-Length and body then frame a
			// message the sender never had to be a bridge client to compose.
			if (!isHeaderName(name)) {
				throw new IOException("malformed header line: " + line);
			}
			if (CONTENT_LENGTH.equalsIgnoreCase(name)) {
				final String value = line.substring(colon + 1).trim();
				try {
					contentLength = Integer.parseInt(value);
				} catch (NumberFormatException e) {
					throw new IOException("bad Content-Length: " + value, e);
				}
			}
		}
		if (contentLength < 0) {
			throw new IOException("message without a Content-Length header");
		}
		if (contentLength > MAX_CONTENT_LENGTH) {
			throw new IOException("Content-Length too large: " + contentLength);
		}
		final byte[] payload = in.readNBytes(contentLength);
		if (payload.length < contentLength) {
			return null; // the peer went away mid-message
		}
		return new String(payload, StandardCharsets.UTF_8);
	}

	/** Write one message. */
	public static void write(OutputStream out, String payload)
			throws IOException {
		final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
		out.write(("Content-Length: " + bytes.length + "\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
		out.write(bytes);
		out.flush();
	}

	/** Whether this is a header field name: non-empty, and a token throughout. */
	private static boolean isHeaderName(String name) {
		if (name.isEmpty()) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			final char c = name.charAt(i);
			if (c <= ' ' || c >= 0x7f || c == '/') {
				return false;
			}
		}
		return true;
	}

	/**
	 * One header line without its terminator, or {@code null} if the stream
	 * ended before any byte of it arrived.
	 */
	private static String readLine(InputStream in) throws IOException {
		final ByteArrayOutputStream line = new ByteArrayOutputStream();
		while (true) {
			final int b = in.read();
			if (b < 0) {
				return line.size() == 0 ? null
						: line.toString(StandardCharsets.US_ASCII);
			}
			if (b == '\n') {
				final String text = line.toString(StandardCharsets.US_ASCII);
				return text.endsWith("\r")
						? text.substring(0, text.length() - 1)
						: text;
			}
			if (line.size() >= MAX_HEADER_LINE_BYTES) {
				throw new IOException("header line too long");
			}
			line.write(b);
		}
	}
}
