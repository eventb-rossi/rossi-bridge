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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.part.ISetSelectionTarget;
import org.rodinp.core.IRodinFile;
import org.rodinp.core.RodinDBException;
import org.rodinp.core.emf.api.itf.ILFile;

import fr.systerel.editor.internal.editors.RodinEditor;

/** The workspace and workbench operations the bridge exposes. */
final class ProjectOps {

	/** The Event-B Explorer, where a revealed project is selected. */
	private static final String EXPLORER_VIEW = "fr.systerel.explorer.navigator.view";

	/** Rodin's unchecked machine and context files, in lookup order. */
	private static final String[] COMPONENT_EXTENSIONS = { "bum", "buc" };

	/** Whether a file name is one of Rodin's unchecked component files. */
	static boolean isComponentFile(String name) {
		for (final String extension : COMPONENT_EXTENSIONS) {
			if (name.endsWith("." + extension)) {
				return true;
			}
		}
		return false;
	}

	private ProjectOps() {
		// utility class
	}

	/** The Eclipse instance area, which is also the Rodin workspace. */
	static File workspaceDirectory() {
		return ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
	}

	/**
	 * Register a project directory with the running workspace: load its
	 * {@code .project} descriptor, create and open the project if it is not
	 * there yet, and refresh it.
	 *
	 * <p>
	 * This is what Rossi otherwise does through Eclipse's headless Ant runner
	 * before launching Rodin, which cannot work at all once Rodin holds the
	 * workspace, because the two would need the same instance area.
	 * </p>
	 */
	static Map<String, Object> register(String path) throws BridgeException {
		final File directory;
		try {
			directory = new File(path).getCanonicalFile();
		} catch (IOException e) {
			throw new BridgeException(BridgeException.INVALID_PARAMS,
					"cannot resolve " + path + ": " + e.getMessage(), e);
		}
		final File dotProject = new File(directory, ".project");
		if (!dotProject.isFile()) {
			throw new BridgeException(BridgeException.INVALID_PARAMS,
					"no .project descriptor in " + directory);
		}

		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IProjectDescription description;
		try {
			description = workspace.loadProjectDescription(
					new Path(dotProject.getAbsolutePath()));
		} catch (CoreException e) {
			throw new BridgeException(BridgeException.INTERNAL_ERROR,
					"cannot read " + dotProject + ": " + e.getMessage(), e);
		}

		final IProject project = workspace.getRoot()
				.getProject(description.getName());
		final boolean[] created = { false };
		run(project, monitor -> {
			if (!project.exists()) {
				project.create(description, monitor);
				created[0] = true;
			}
			if (!project.isOpen()) {
				project.open(monitor);
			}
			// Opening a project the workspace has just been told about walks
			// its tree already, because create() marks the children unknown
			// when the directory has content. Only a project that was open
			// before this call still needs telling.
			if (!created[0]) {
				project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			}
		}, "cannot register " + description.getName());

		return Json.map("project", description.getName(), "created",
				Boolean.valueOf(created[0]));
	}

	/**
	 * Bring Rodin forward, select the project in the Event-B Explorer and, when
	 * a component is named, open its editor.
	 *
	 * <p>
	 * The workbench work is scheduled on the UI thread and this returns at
	 * once: waiting for it would block a bridge session thread on a thread the
	 * user is driving.
	 * </p>
	 */
	static Map<String, Object> reveal(String projectName, String component)
			throws BridgeException {
		final IProject project = project(projectName);
		final IResource target = component == null ? project
				: componentFile(project, component);
		final IWorkbench workbench = PlatformUI.getWorkbench();
		workbench.getDisplay()
				.asyncExec(() -> revealInWorkbench(workbench, target));
		return Json.map("project", projectName, "component", component);
	}

	/**
	 * Switch the workbench to a perspective.
	 *
	 * <p>
	 * Which perspective a workspace opens in is otherwise Eclipse's
	 * {@code defaultPerspectiveId} preference to decide, and it reads that
	 * only when opening a window with no perspective state to restore. Every
	 * workspace Rodin has opened before restores the perspective that was
	 * last active there instead, so nothing outside the running instance can
	 * change it. This can, being inside it.
	 * </p>
	 *
	 * <p>
	 * Scheduled on the UI thread and answered at once, like {@link #reveal}:
	 * an unknown or unopenable perspective is logged there rather than
	 * reported back, since by then the caller has been answered.
	 * </p>
	 */
	static Map<String, Object> showPerspective(String id) {
		final IWorkbench workbench = PlatformUI.getWorkbench();
		workbench.getDisplay()
				.asyncExec(() -> showPerspectiveInWorkbench(workbench, id));
		return Json.map("perspective", id);
	}

	private static void showPerspectiveInWorkbench(IWorkbench workbench,
			String id) {
		final IWorkbenchWindow window = activeWindow(workbench);
		if (window == null) {
			return;
		}
		try {
			workbench.showPerspective(id, window);
		} catch (WorkbenchException e) {
			Activator.log(IStatus.WARNING, "cannot show perspective " + id
					+ ": " + e.getMessage(), e);
		}
	}

	/**
	 * Re-read a project's files from disk, so writes made by another process
	 * enter the workspace now rather than whenever Eclipse's polling
	 * auto-refresh next looks.
	 *
	 * @param files
	 *            project-relative names to refresh, or {@code null} for the
	 *            whole project
	 * @param build
	 *            also schedule an incremental build
	 */
	static Map<String, Object> refresh(String projectName, List<Object> files,
			boolean build) throws BridgeException {
		final IProject project = project(projectName);
		final List<String> names = names(files);
		run(project, monitor -> {
			if (names.isEmpty()) {
				project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			} else {
				for (final String name : names) {
					project.getFile(name).refreshLocal(IResource.DEPTH_ONE,
							monitor);
				}
			}
		}, "cannot refresh " + projectName);
		if (build) {
			scheduleBuild(project);
		}
		return Json.map("project", projectName, "buildScheduled",
				Boolean.valueOf(build));
	}

	/**
	 * Re-read `files` from disk and reload any editor open on them.
	 *
	 * <p>
	 * Refreshing alone is not enough. Eclipse notices the write and the
	 * database re-reads the file, but an editor already open on a component
	 * keeps rendering what it loaded, which is why the user otherwise reaches
	 * for F5. Reloading the editor's resource is what {@code RefreshHandler}
	 * does for that key, and this does the same on request.
	 * </p>
	 */
	static Map<String, Object> reload(String projectName, List<Object> files)
			throws BridgeException {
		refresh(projectName, files, false);
		final IProject project = project(projectName);
		final List<String> names = names(files);
		final IWorkbench workbench = PlatformUI.getWorkbench();
		workbench.getDisplay()
				.asyncExec(() -> reloadEditors(workbench, project, names));
		return Json.map("project", projectName, "files",
				Long.valueOf(names.size()));
	}

	private static void reloadEditors(IWorkbench workbench, IProject project,
			List<String> names) {
		for (final IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
			final IWorkbenchPage page = window.getActivePage();
			if (page == null) {
				continue;
			}
			for (final IEditorReference reference : page.getEditorReferences()) {
				// `false`: an editor that has not been restored has nothing
				// stale on screen, so leave it closed.
				final IEditorPart editor = reference.getEditor(false);
				if (editor == null || !isOn(editor, project, names)) {
					continue;
				}
				reloadEditor(editor);
			}
		}
	}

	private static boolean isOn(IEditorPart editor, IProject project,
			List<String> names) {
		// Adapter-aware, so this also matches an editor whose input is not a
		// plain file input.
		final IFile file = ResourceUtil.getFile(editor.getEditorInput());
		return file != null && project.equals(file.getProject())
				&& names.contains(file.getName());
	}

	/** The file names in a request's `files` member, never null. */
	private static List<String> names(List<Object> files) {
		final List<String> names = new ArrayList<>();
		if (files != null) {
			for (final Object name : files) {
				final String file = Json.asString(name);
				if (file != null) {
					names.add(file);
				}
			}
		}
		return names;
	}

	/**
	 * Reload one editor, unless the user has typed into it since the last
	 * save.
	 *
	 * <p>
	 * Reloading replaces what the editor shows with what is on disk, and
	 * keystrokes reach the Rodin database before any save, so they sit in the
	 * very buffer a reload drops. Leave such an editor alone; saving or
	 * reverting in Rodin settles which version wins, and does it where the
	 * user can watch.
	 * </p>
	 */
	private static void reloadEditor(IEditorPart editor) {
		if (!(editor instanceof RodinEditor rodinEditor)) {
			return;
		}
		// The question Rodin's own dirty tracking asks, of the same file:
		// RodinDocumentProvider does exactly this to decide whether the editor
		// can be saved. Asking it rather than IEditorPart.isDirty() skips a
		// cached flag that is not refreshed while the provider synchronizes.
		final IRodinFile file = rodinEditor.getInputRoot().getRodinFile();
		try {
			if (file.hasUnsavedChanges()) {
				Activator.log(IStatus.INFO,
						"not reloading " + file.getElementName()
								+ ": it has unsaved changes in Rodin",
						null);
				return;
			}
		} catch (RodinDBException e) {
			// Unable to tell, so do not gamble with the user's typing.
			return;
		}
		// An overlay edit in flight holds a position into the document about
		// to be replaced, and writes back when it closes; abandoning it first
		// is what keeps it from resurrecting what the reload just dropped.
		rodinEditor.abordEdition();
		final ILFile resource = rodinEditor.getResource();
		if (resource != null) {
			resource.reload();
		}
	}

	/**
	 * The window to act on: the active one, or the first that exists when
	 * nothing is active. {@code null} when the workbench has no window at all.
	 */
	private static IWorkbenchWindow activeWindow(IWorkbench workbench) {
		final IWorkbenchWindow active = workbench.getActiveWorkbenchWindow();
		if (active != null) {
			return active;
		}
		final IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
		return windows.length == 0 ? null : windows[0];
	}

	private static void revealInWorkbench(IWorkbench workbench,
			IResource target) {
		final IWorkbenchWindow window = activeWindow(workbench);
		if (window == null) {
			return;
		}
		final Shell shell = window.getShell();
		if (shell != null) {
			shell.setMinimized(false);
			shell.forceActive();
		}
		final IWorkbenchPage page = window.getActivePage();
		if (page == null) {
			return;
		}
		try {
			final IViewPart explorer = page.showView(EXPLORER_VIEW);
			if (explorer instanceof ISetSelectionTarget) {
				((ISetSelectionTarget) explorer)
						.selectReveal(new StructuredSelection(target));
			}
		} catch (PartInitException e) {
			Activator.log(IStatus.WARNING,
					"cannot show the Event-B Explorer: " + e.getMessage(), e);
		}
		if (target instanceof IFile) {
			try {
				IDE.openEditor(page, (IFile) target);
			} catch (PartInitException e) {
				Activator.log(IStatus.WARNING,
						"cannot open " + target.getName() + ": "
								+ e.getMessage(),
						e);
			}
		}
	}

	private static IProject project(String name) throws BridgeException {
		final IProject project = ResourcesPlugin.getWorkspace().getRoot()
				.getProject(name);
		if (!project.exists()) {
			throw new BridgeException(BridgeException.INVALID_PARAMS,
					"no project named " + name);
		}
		return project;
	}

	private static IFile componentFile(IProject project, String component)
			throws BridgeException {
		for (final String extension : COMPONENT_EXTENSIONS) {
			final IFile file = project.getFile(component + "." + extension);
			if (file.exists()) {
				return file;
			}
		}
		throw new BridgeException(BridgeException.INVALID_PARAMS,
				"no component named " + component + " in " + project.getName());
	}

	private static void scheduleBuild(IProject project) {
		final WorkspaceJob job = new WorkspaceJob(
				"Building " + project.getName()) {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor)
					throws CoreException {
				project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD,
						monitor);
				return Status.OK_STATUS;
			}
		};
		job.setRule(project);
		job.schedule();
	}

	/**
	 * Run `runnable` scheduled against `project` alone. The two-argument
	 * {@code IWorkspace.run} takes the workspace root instead, which would make
	 * every refresh here block the auto-builder, Rodin's own builders and any
	 * editor save for the length of a tree walk.
	 */
	private static void run(IProject project, IWorkspaceRunnable runnable,
			String failure) throws BridgeException {
		try {
			ResourcesPlugin.getWorkspace().run(runnable, project,
					IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
		} catch (CoreException e) {
			throw new BridgeException(BridgeException.INTERNAL_ERROR,
					failure + ": " + e.getMessage(), e);
		}
	}
}
