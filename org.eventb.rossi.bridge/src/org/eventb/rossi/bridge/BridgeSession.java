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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IStatus;

/**
 * One client connection: reads framed JSON-RPC messages, dispatches them and
 * writes the answers back. Runs on its own thread; {@link #notify(String, Map)}
 * may be called from any other.
 */
final class BridgeSession implements Runnable {

	/** How long a new connection has to send its first message. */
	private static final int GREETING_TIMEOUT_MS = 10_000;

	private final Socket socket;
	private final OutputStream out;
	private final Methods methods;
	private final Consumer<BridgeSession> onClose;
	private final Object writeLock = new Object();

	BridgeSession(Socket socket, Methods methods,
			Consumer<BridgeSession> onClose) throws IOException {
		this.socket = socket;
		this.out = socket.getOutputStream();
		this.methods = methods;
		this.onClose = onClose;
	}

	@Override
	public void run() {
		try (InputStream in = new BufferedInputStream(
				socket.getInputStream())) {
			// Until something arrives, this connection is only a claim on a
			// thread and a socket: a port scan, a stalled client, a browser
			// probe. Once it has spoken, a real client may idle as long as it
			// likes between requests.
			socket.setSoTimeout(GREETING_TIMEOUT_MS);
			while (true) {
				final String message = Framing.read(in);
				if (message == null) {
					return;
				}
				socket.setSoTimeout(0);
				handle(message);
			}
		} catch (IOException e) {
			// A client that disconnects is routine, not an error.
			Activator.log(IStatus.INFO,
					"bridge session ended: " + e.getMessage(), null);
		} finally {
			close();
			onClose.accept(this);
		}
	}

	/** Drop the connection; the reading thread unblocks and exits. */
	void close() {
		try {
			socket.close();
		} catch (IOException e) {
			// Already gone; nothing left to do.
		}
	}

	private void handle(String message) {
		final Object parsed;
		try {
			parsed = Json.parse(message);
		} catch (IllegalArgumentException e) {
			sendError(null, BridgeException.PARSE_ERROR, e.getMessage());
			return;
		}
		final Map<String, Object> request = Json.asMap(parsed);
		if (request == null) {
			sendError(null, BridgeException.INVALID_REQUEST,
					"a request must be a JSON object");
			return;
		}
		// An absent id makes this a notification: answer nothing, report
		// failures to the log instead.
		final Object id = request.get("id");
		final String method = Json.asString(request.get("method"));
		if (method == null) {
			sendError(id, BridgeException.INVALID_REQUEST,
					"a request must carry a method name");
			return;
		}
		final Map<String, Object> params = request.get("params") == null
				? Collections.<String, Object> emptyMap()
				: Json.asMap(request.get("params"));
		if (params == null) {
			sendError(id, BridgeException.INVALID_PARAMS,
					"params must be a JSON object");
			return;
		}
		try {
			final Object result = methods.handle(method, params);
			if (id != null) {
				send(Json.map("jsonrpc", "2.0", "id", id, "result", result));
			}
		} catch (BridgeException e) {
			fail(id, method, e.getCode(), e.getMessage(), e);
		} catch (RuntimeException e) {
			fail(id, method, BridgeException.INTERNAL_ERROR,
					String.valueOf(e), e);
		}
	}

	private void fail(Object id, String method, int code, String message,
			Throwable cause) {
		if (id != null) {
			sendError(id, code, message);
		} else {
			Activator.log(IStatus.WARNING,
					"bridge notification " + method + " failed: " + message,
					cause);
		}
	}

	private void sendError(Object id, int code, String message) {
		send(Json.map("jsonrpc", "2.0", "id", id, "error",
				Json.map("code", Long.valueOf(code), "message",
						String.valueOf(message))));
	}

	private void send(Map<String, Object> message) {
		final String payload = Json.write(message);
		synchronized (writeLock) {
			try {
				Framing.write(out, payload);
			} catch (IOException e) {
				Activator.log(IStatus.INFO,
						"bridge write failed: " + e.getMessage(), null);
				close();
			}
		}
	}
}
