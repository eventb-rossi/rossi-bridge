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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.IStatus;

/**
 * The bridge's loopback listener.
 *
 * <p>
 * Binds an ephemeral port on the loopback interface and publishes it, together
 * with the protocol descriptor, as {@code <workspace>/.rossi-bridge/port}. That
 * directory is Eclipse's instance area, the {@code -data} directory, which is
 * exactly the path Rossi's language server computes for the workspace it builds
 * into, so no other rendezvous is needed. The file is removed on
 * {@link #stop()}.
 * </p>
 */
public final class BridgeServer {

	/** Directory holding the port file, inside the Eclipse workspace. */
	public static final String DIRECTORY = ".rossi-bridge";

	/** The port file's name inside {@link #DIRECTORY}. */
	public static final String PORT_FILE = "port";

	private final ServerSocket serverSocket;
	private final String token;
	private final File portFile;
	private final List<BridgeSession> sessions = new CopyOnWriteArrayList<>();
	private final Thread acceptor;
	private final ModelListener models = new ModelListener(this);
	private volatile boolean stopping;

	/**
	 * Bind, publish the descriptor and start accepting clients.
	 *
	 * @param workspaceDir
	 *            the Eclipse instance area
	 * @param token
	 *            the secret a client must present in {@code bridge/hello}; it
	 *            is published in the port file and given to each session
	 */
	public static BridgeServer start(File workspaceDir, String token)
			throws IOException {
		final ServerSocket socket = new ServerSocket(0, 8,
				InetAddress.getLoopbackAddress());
		final BridgeServer server = new BridgeServer(socket, token,
				new File(new File(workspaceDir, DIRECTORY), PORT_FILE));
		try {
			server.publish();
		} catch (IOException e) {
			socket.close();
			throw e;
		}
		server.acceptor.start();
		server.models.start();
		return server;
	}

	private BridgeServer(ServerSocket serverSocket, String token,
			File portFile) {
		this.serverSocket = serverSocket;
		this.token = token;
		this.portFile = portFile;
		this.acceptor = new Thread(this::accept, "rossi-bridge-acceptor");
		this.acceptor.setDaemon(true);
	}

	/** The port clients connect to. */
	public int getPort() {
		return serverSocket.getLocalPort();
	}

	/**
	 * Whether any client asked to be sent model changes. Serialising a
	 * component is the expensive part of a push, and live sync is off by
	 * default, so the listener asks before doing any of it.
	 */
	boolean hasSubscribers() {
		for (final BridgeSession session : sessions) {
			if (session.isSubscribed()) {
				return true;
			}
		}
		return false;
	}

	/** Push a notification to every client that subscribed. */
	void broadcast(String method, Map<String, Object> params) {
		for (final BridgeSession session : sessions) {
			session.notify(method, params);
		}
	}

	/**
	 * Drop the clients and unpublish the port.
	 *
	 * <p>
	 * This runs on the UI thread, from the workbench's pre-shutdown, so it may
	 * only do bounded work. Closing a socket is a syscall; writing a farewell
	 * to one is not -- the JDK gives a socket write no timeout, so a client
	 * that has stopped reading would hang Rodin's shutdown. The client learns
	 * the same thing from the connection ending.
	 * </p>
	 */
	public void stop() {
		stopping = true;
		models.stop();
		for (final BridgeSession session : sessions) {
			session.close();
		}
		sessions.clear();
		try {
			serverSocket.close();
		} catch (IOException e) {
			// Shutting down anyway.
		}
		unpublish();
	}

	private void publish() throws IOException {
		final Map<String, Object> published = Methods.descriptor();
		published.put("port", Long.valueOf(getPort()));
		published.put("pid", Long.valueOf(ProcessHandle.current().pid()));
		published.put("token", token);

		final File directory = portFile.getParentFile();
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IOException("cannot create " + directory);
		}
		// Write a sibling and rename it over the target, so a client never
		// reads a half-written descriptor.
		final File temporary = new File(directory, PORT_FILE + ".tmp");
		Files.write(temporary.toPath(),
				Json.write(published).getBytes(StandardCharsets.UTF_8));
		// The descriptor carries the token, so it is only as good as its
		// permissions. Where the file system has no POSIX view -- Windows --
		// the file inherits the directory's ACL and this is skipped.
		try {
			Files.setPosixFilePermissions(temporary.toPath(),
					Set.of(PosixFilePermission.OWNER_READ,
							PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException | IOException e) {
			Activator.log(IStatus.INFO,
					"could not restrict " + PORT_FILE + " to its owner: "
							+ e.getMessage(),
					null);
		}
		Files.move(temporary.toPath(), portFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING);
	}

	private void unpublish() {
		try {
			Files.deleteIfExists(portFile.toPath());
			// Best effort: the directory holds nothing else of ours.
			portFile.getParentFile().delete();
		} catch (IOException e) {
			Activator.log(IStatus.WARNING,
					"could not remove " + portFile + ": " + e.getMessage(), e);
		}
	}

	private void accept() {
		while (!stopping) {
			final Socket socket;
			try {
				socket = serverSocket.accept();
			} catch (IOException e) {
				if (!stopping) {
					Activator.log(IStatus.ERROR,
							"bridge stopped accepting clients: "
									+ e.getMessage(),
							e);
				}
				return;
			}
			// One client that fails to set up must not take the listener down
			// with it. The port file stays published either way, so a dead
			// acceptor would leave every later client connecting into the
			// backlog and then waiting out its handshake timeout.
			try {
				socket.setTcpNoDelay(true);
				// One Methods per connection: the handshake gate it holds is
				// true of that client alone.
				final BridgeSession session = new BridgeSession(socket,
						new Methods(token), sessions::remove);
				sessions.add(session);
				final Thread thread = new Thread(session,
						"rossi-bridge-session");
				thread.setDaemon(true);
				thread.start();
			} catch (IOException e) {
				Activator.log(IStatus.WARNING,
						"bridge could not start a session: " + e.getMessage(),
						e);
				try {
					socket.close();
				} catch (IOException closing) {
					// Nothing left to do with it.
				}
			}
		}
	}
}
