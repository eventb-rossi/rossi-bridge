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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * The bridge's method table, one instance per connection.
 *
 * <p>
 * A connection may ask for nothing until it has presented the token from the
 * port file in {@code bridge/hello}. The socket is loopback-only, but that is
 * not a boundary: on a shared machine another user can reach it, and a web
 * page can be made to POST to any local port. Reading the port file, which
 * lives in the user's own workspace, is what the token asks a client to prove.
 * </p>
 */
public final class Methods {

	/**
	 * The protocol revision. Bumped only for a change no {@link #CAPS} entry can
	 * describe; new methods are announced through the capability list instead,
	 * so the two sides can be upgraded independently.
	 */
	public static final long PROTOCOL = 1;

	/** The handshake, which every connection must complete first. */
	public static final String HELLO = "bridge/hello";

	/** The methods this build answers, in the order they were introduced. */
	private static final List<Object> CAPS = Arrays.<Object> asList(
			"project/register", "project/reveal", "workspace/refresh");

	private final String token;

	private boolean greeted;

	/**
	 * @param token
	 *            the secret published in the port file, which a client must
	 *            present to be answered
	 */
	public Methods(String token) {
		this.token = token;
	}

	/**
	 * Answer one call.
	 *
	 * @param params
	 *            the request's {@code params}, never {@code null} (an omitted
	 *            member arrives as an empty map)
	 * @return the value to send back as {@code result}
	 */
	Object handle(String method, Map<String, Object> params)
			throws BridgeException {
		if (!greeted && !HELLO.equals(method)) {
			throw new BridgeException(BridgeException.INVALID_REQUEST,
					HELLO + " must come first");
		}
		switch (method) {
		case HELLO:
			return hello(params);
		case "project/register":
			return ProjectOps.register(required(params, "path"));
		case "project/reveal":
			return ProjectOps.reveal(required(params, "project"),
					Json.asString(params.get("component")));
		case "workspace/refresh":
			return ProjectOps.refresh(required(params, "project"),
					Json.asList(params.get("files")),
					Json.asBoolean(params.get("build"), false));
		default:
			throw new BridgeException(BridgeException.METHOD_NOT_FOUND,
					"unknown method: " + method);
		}
	}

	/**
	 * What {@code bridge/hello} answers: enough for a client to know what it is
	 * talking to and what it may ask for. A fresh map each time, so the port
	 * file's publisher can add what only it knows -- the port, the pid, and the
	 * token the handshake deliberately never echoes back.
	 */
	static Map<String, Object> descriptor() {
		return Json.map("protocol", Long.valueOf(PROTOCOL), "rodin",
				version("org.rodinp.platform", "org.rodinp.core"), "bundle",
				version("org.eventb.rossi.bridge"), "workspace",
				ProjectOps.workspaceDirectory().getAbsolutePath(), "caps",
				CAPS);
	}

	private Object hello(Map<String, Object> params) throws BridgeException {
		// Constant-time so the comparison itself says nothing about how much
		// of a guess was right.
		if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
				String.valueOf(Json.asString(params.get("token")))
						.getBytes(StandardCharsets.UTF_8))) {
			throw new BridgeException(BridgeException.INVALID_REQUEST,
					"bad token: read it from the port file in the workspace");
		}
		greeted = true;
		final String client = Json.asString(params.get("client"));
		if (client != null) {
			Activator.log(IStatus.INFO,
					"bridge client connected: " + client + " "
							+ Json.asString(params.get("version")),
					null);
		}
		return descriptor();
	}

	/** The version of the first of these bundles that is installed. */
	private static String version(String... symbolicNames) {
		for (final String symbolicName : symbolicNames) {
			final Bundle bundle = Platform.getBundle(symbolicName);
			if (bundle != null) {
				return bundle.getVersion().toString();
			}
		}
		return "unknown";
	}

	private static String required(Map<String, Object> params, String key)
			throws BridgeException {
		final String value = Json.asString(params.get(key));
		if (value == null || value.isEmpty()) {
			throw new BridgeException(BridgeException.INVALID_PARAMS,
					"missing '" + key + "'");
		}
		return value;
	}
}
