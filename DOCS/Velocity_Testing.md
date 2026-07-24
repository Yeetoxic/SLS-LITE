# Velocity Test Environment

The local smoke environment verifies the first complete SLS-LITE lifecycle
against real server software:

- Velocity 4.0.0 build 6 (stable).
- Paper 26.1.2 build 74 (stable).
- Eclipse Temurin Java 25.

The downloaded server files and generated worlds are stored under the ignored
`test-server/` directory.

## Setup

From the repository root:

```powershell
.\scripts\setup-velocity-test.ps1
```

The script builds SLS-LITE, downloads the pinned official PaperMC artifacts,
configures a loopback-only proxy, accepts the Paper EULA for the local fixture,
and installs an ephemeral `smoke` blueprint.

## Manual Test

Start the proxy:

```powershell
cd test-server
.\start.ps1
```

Run these commands in the Velocity console:

```text
sls registries
sls blueprints test
sls start test smoke
sls list
sls status <instance-id>
```

Expected behavior:

1. The `test` registry and its `smoke` server are listed.
2. Paper starts on one of `127.0.0.1:25600-25610`.
3. The instance reaches `READY` before Velocity registration.
4. `sls list` and `sls status` show an ID shaped like `smoke.x82odk`.

After the instance reports `READY`, connect Minecraft to `localhost:25565`.
When Velocity has no external initial server configured, SLS-LITE routes the
first join to an already-ready managed backend. In game, run:

```text
/sls version
/sls registries
/sls list
/sls find <your-player-name>
/sls join test smoke
/sls dequeue
```

The join command should reuse the ready `smoke` instance. To test queued
provisioning, connect through a separately configured lobby, stop the smoke
instance, and run `/sls join test smoke`. SLS-LITE should keep the player on the
lobby, start Paper, and transfer the player only after the instance reaches
`READY`.

Queue checks:

1. Run `/sls join test smoke` twice while Paper starts. The second request must
   report that the player is already queued.
2. Run `/sls dequeue`; the pending transfer must be cancelled.
3. Disconnect while queued; the request must be removed automatically.
4. With admin permission, test `/sls join test smoke all`,
   `/sls join test smoke local`, and a specific player name.
5. When the last request for a queue-created instance is cancelled, the instance
   should stop after it reaches a safely stoppable state.

Managed lobby provisioning is not implemented yet, so queued provisioning needs
an external test lobby in this release.

## Two-Server Join Test

The fixture includes `test/smoke` and `test/smoke2`. They share the prepared
Paper software but receive separate isolated instance directories and Velocity
registrations. Start one instance of each:

```text
sls start test smoke
sls start test smoke2
sls list
```

Join the proxy with one Minecraft client, then:

1. Run `/sls join test smoke2`. The player must move to the `smoke2.xxxxxx`
   instance.
2. Run `/sls join test smoke`. The player must move back to the
   `smoke.xxxxxx` instance.
3. Repeat both commands and verify that no additional instances are created.

For the separate `join player` test, use two clients on different instances and
run `/sls join player <other-player>`.

`/sls join player <player> --force` is restricted to administrators. It
currently follows the same route because blueprint capacity enforcement has not
been implemented yet.

Finish from the Velocity console:

```text
sls stop <instance-id>
end
```

Expected cleanup:

1. `sls stop` exits with code `0`, unregisters the backend, releases memory and
   port reservations, and removes the ephemeral instance directory.
2. Velocity exits with no managed child processes remaining.

## Permissions

Self-service `list`, `registries`, `version`, `find`, and `join` commands are
public. Administrative commands accept the upstream-compatible
`sls.command.admin` umbrella node. Granular aliases are:

```text
sls.command.blueprints
sls.command.info
sls.command.reload
sls.command.start
sls.command.status
sls.command.stop
sls.command.join.others
sls.command.dequeue.others
```

Console may use `/sls join <registry> <server> <player>`. A player may target
another player only with `sls.command.admin` or `sls.command.join.others`.
Unauthorized administrative commands and arguments are omitted from tab
completion.

This fixture intentionally uses offline mode and no player forwarding. It binds
only to loopback and is not a production configuration.
