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

import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

/**
 * Starts the bridge when the workbench opens, and stops it when the workbench
 * closes.
 *
 * <p>
 * The plug-in contributes no command or view, so nothing would otherwise
 * trigger its lazy activation; a client must be able to find the port from the
 * moment Rodin is up.
 * </p>
 *
 * <p>
 * Stopping is hung off the workbench rather than left to the bundle's own
 * {@code stop}: quitting Rodin does not always reach a bundle stop, and a
 * client that never got {@code bridge/shutdown} would keep believing a
 * published port. A client still has to treat the port file as advisory,
 * because a killed Rodin leaves it behind and only connecting proves
 * anything.
 * </p>
 */
public class BridgeStartup implements IStartup, IWorkbenchListener {

	@Override
	public void earlyStartup() {
		final Activator plugin = Activator.getDefault();
		if (plugin == null) {
			return;
		}
		plugin.startServer();
		PlatformUI.getWorkbench().addWorkbenchListener(this);
	}

	@Override
	public boolean preShutdown(IWorkbench workbench, boolean forced) {
		final Activator plugin = Activator.getDefault();
		if (plugin != null) {
			plugin.stopServer();
		}
		return true;
	}

	@Override
	public void postShutdown(IWorkbench workbench) {
		// Nothing: preShutdown has already unpublished the port.
	}
}
