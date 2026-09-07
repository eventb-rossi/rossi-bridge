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

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.BundleContext;

/** The bundle, and the bridge server's lifetime. */
public class Activator extends Plugin {

	public static final String PLUGIN_ID = "org.eventb.rossi.bridge";

	private static volatile Activator plugin;

	private BridgeServer server;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		stopServer();
		plugin = null;
		super.stop(context);
	}

	public static Activator getDefault() {
		return plugin;
	}

	/**
	 * Start listening. Called once the workbench is up; a failure is logged and
	 * leaves Rodin working exactly as it would without this plug-in.
	 */
	public synchronized void startServer() {
		if (server != null) {
			return;
		}
		// A fresh secret per session: a client proves it can read the port
		// file, which lives in the user's own workspace, rather than merely
		// having found the port.
		final byte[] secret = new byte[16];
		new SecureRandom().nextBytes(secret);
		try {
			server = BridgeServer.start(ProjectOps.workspaceDirectory(),
					HexFormat.of().formatHex(secret));
			log(IStatus.INFO, "Rossi bridge listening on 127.0.0.1:"
					+ server.getPort(), null);
		} catch (IOException e) {
			log(IStatus.ERROR,
					"could not start the Rossi bridge: " + e.getMessage(), e);
		}
	}

	public synchronized void stopServer() {
		if (server == null) {
			return;
		}
		server.stop();
		server = null;
	}

	public static void log(int severity, String message, Throwable cause) {
		final Activator instance = plugin;
		if (instance == null) {
			return;
		}
		instance.getLog()
				.log(new Status(severity, PLUGIN_ID, message, cause));
	}
}
