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

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.rodinp.core.ElementChangedEvent;
import org.rodinp.core.IElementChangedListener;
import org.rodinp.core.IRodinElement;
import org.rodinp.core.IRodinElementDelta;
import org.rodinp.core.IRodinFile;
import org.rodinp.core.RodinCore;
import org.rodinp.core.RodinDBException;

/**
 * Report a component's model as it is edited, before it is saved.
 *
 * <p>
 * The Rodin Editor writes each keystroke through to the database rather than
 * holding it in the text widget, so an edit is visible here the moment it is
 * typed. That is what lets a client see a rename without waiting for Ctrl+S.
 * </p>
 *
 * <p>
 * A save is deliberately not reported. It arrives as {@code F_CONTENT} on the
 * file with nothing below it, and a client watching the workspace directory
 * sees the write itself; reporting it too would only race that.
 * </p>
 */
final class ModelListener implements IElementChangedListener {

	/** Quiet period before a burst of keystrokes is reported as one push. */
	private static final int DEBOUNCE_MS = 250;

	private final BridgeServer server;

	private final Set<IRodinFile> pending = new LinkedHashSet<>();

	private final Job push = new Job("Reporting Event-B model changes") {
		@Override
		protected IStatus run(IProgressMonitor monitor) {
			publish();
			return Status.OK_STATUS;
		}
	};

	ModelListener(BridgeServer server) {
		this.server = server;
		push.setSystem(true);
	}

	void start() {
		RodinCore.addElementChangedListener(this, ElementChangedEvent.POST_CHANGE);
	}

	void stop() {
		RodinCore.removeElementChangedListener(this);
		push.cancel();
	}

	@Override
	public void elementChanged(ElementChangedEvent event) {
		// Live sync is off by default, and the per-operation connections never
		// subscribe, so most sessions have nobody to tell. Ask before walking
		// the delta, let alone serialising a component.
		if (!server.hasSubscribers()) {
			return;
		}
		// A delta is only valid for the length of this call, so take what is
		// needed from it here and do the reading afterwards.
		final Set<IRodinFile> touched = new LinkedHashSet<>();
		collect(event.getDelta(), touched);
		if (touched.isEmpty()) {
			return;
		}
		synchronized (pending) {
			pending.addAll(touched);
		}
		// Restarting the job coalesces a burst into one push per file.
		push.cancel();
		push.schedule(DEBOUNCE_MS);
	}

	private void collect(IRodinElementDelta delta, Set<IRodinFile> out) {
		final IRodinElement element = delta.getElement();
		if (element instanceof IRodinFile file) {
			if (delta.getKind() != IRodinElementDelta.REMOVED
					&& ProjectOps.isComponentFile(file.getElementName())
					&& !isSaveOnly(delta)) {
				out.add(file);
			}
			return;
		}
		for (final IRodinElementDelta child : delta.getAffectedChildren()) {
			collect(child, out);
		}
	}

	/**
	 * Whether this delta says only "the file's bytes changed", which is how a
	 * save, or an external write, reaches us. An edit made in the editor
	 * carries the elements it touched underneath.
	 */
	private static boolean isSaveOnly(IRodinElementDelta delta) {
		return (delta.getFlags() & IRodinElementDelta.F_CONTENT) != 0
				&& delta.getAffectedChildren().length == 0;
	}

	private void publish() {
		final Set<IRodinFile> files;
		synchronized (pending) {
			files = new LinkedHashSet<>(pending);
			pending.clear();
		}
		for (final IRodinFile file : files) {
			try {
				// Saved between the delta and here: the client's own watcher
				// has it, and pushing now would only duplicate that.
				if (!file.exists() || !file.hasUnsavedChanges()) {
					continue;
				}
				server.broadcast(Methods.DIRTY, Json.map(
						"project", file.getRodinProject().getElementName(),
						"file", file.getElementName(),
						"xml", ModelXml.of(file)));
			} catch (RodinDBException e) {
				Activator.log(IStatus.INFO, "cannot report " + file.getElementName()
						+ ": " + e.getMessage(), null);
			}
		}
	}
}
