# rossi-bridge

An Eclipse plug-in that lets [Rossi](https://github.com/eventb-rossi/rossi)'s
Event-B language server talk to a **running** Rodin.

Rossi already builds Event-B text sources into a shared Rodin workspace and
watches that directory for what Rodin writes back. That works without any
plug-in, but everything has to go through the file system, so two manual steps
survive: an already-open Rodin editor does not reload after Rossi rebuilds a
component (F5 inside the editor, or close and reopen), and a project cannot be
registered at all while Rodin holds the workspace.

This plug-in opens a loopback socket inside Rodin's JVM and answers a small
JSON-RPC protocol, so the language server can reach into the live instance.

## Protocol

The plug-in listens on `127.0.0.1` on an ephemeral port and publishes it as
`<workspace>/.rossi-bridge/port`:

```json
{"port":41235,"pid":12345,"protocol":1,"rodin":"3.10.0",
 "bundle":"1.0.0.qualifier","workspace":"/home/u/.rossi/rodin",
 "caps":["project/register","project/reveal","workspace/refresh",
         "model/subscribe","model/dirty","model/reload","ui/perspective"],
 "token":"9f86d081884c7d65…"}
```

`<workspace>` is Eclipse's instance location: the directory Rodin was launched
with as `-data`, which is exactly the one the language server computes. The file
is written owner-only and removed when the plug-in stops.

Messages are JSON-RPC 2.0 with LSP-style `Content-Length` framing. A client
starts with `bridge/hello` and then uses whatever `caps` reports, so the two
sides can be upgraded independently.

| Method | Params | Effect |
| --- | --- | --- |
| `bridge/hello` | `client`, `version`, `token` | handshake; returns `protocol`, `rodin`, `bundle`, `workspace`, `caps` |
| `project/register` | `path` | load the `.project` descriptor, create/open the project, refresh it |
| `project/reveal` | `project`, `component?` | focus Rodin, select the project in the Event-B Explorer, optionally open the component |
| `workspace/refresh` | `project`, `files?`, `build?` | `refreshLocal` the project or the named files, optionally build |
| `model/subscribe` | none | ask for `model/dirty` on this connection |
| `model/dirty` | `project`, `file`, `xml` | sent by the plug-in: a component changed in Rodin and is not saved, carrying the in-memory model |
| `model/reload` | `project`, `files?` | `refreshLocal` the named files, then reload any editor open on them |
| `ui/perspective` | `id` | switch the workbench to the perspective with that id |

`model/reload` skips an editor whose component has unsaved changes. Keystrokes
reach the Rodin database before a save, so they sit in the buffer a reload would
drop, and dropping them would resolve a two-sided conflict silently in the
caller's favour. Saving or reverting in Rodin settles it instead.

`ui/perspective` exists because the perspective a workspace opens in is
otherwise Eclipse's `defaultPerspectiveId` preference to decide, and Eclipse
reads that only when opening a window with no perspective state to restore.
A workspace Rodin has opened before restores the perspective last active there,
so a client outside the process has no say at all; from inside, one call has.

When Rodin closes, the sessions are dropped and the port file removed; a client
sees the connection end. Nothing is written at that point on purpose, because
shutdown runs on the UI thread and the JDK gives a socket write no timeout, so a
client that had stopped reading could hang Rodin's exit.

## Why there is a token

Binding to `127.0.0.1` keeps the socket off the network, but that is not a
security boundary. Loopback TCP has no owner check, so on a shared machine
another user can connect; and a web page can be made to POST to any local port,
which is enough to compose a bridge message unless the reader is careful about
what a header line is. The methods here open projects and run Eclipse builders,
so neither is acceptable.

Each session mints a fresh random token and publishes it in the (owner-only)
port file. `bridge/hello` must present it, nothing else is answered until it
has, and the handshake never echoes it back. Reading a file inside the user's
own workspace is a proof a remote origin cannot fake, and it never had to be
anything more than that.

## Building

Part of the [Rodin-Bundle](https://github.com/eventb-rossi/Rodin-Bundle) Tycho
reactor, which is where it is normally built. Standalone:

```sh
mvn clean verify -DrodinTargetSiteUrl=<p2 url of a Rodin site>
```

Requires JDK 21 and Maven 3.9+ with a `jdk`/`21` toolchain, same as the rest of
the bundle.

## Licence

EPL-2.0.
