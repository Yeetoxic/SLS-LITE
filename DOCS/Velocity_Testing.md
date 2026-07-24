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
```

The final command should reuse the ready `smoke` instance. To test provisioning
from the join command, stop the existing instance from the Velocity console,
connect through a configured lobby, and run `/sls join test smoke`. The player
must remain connected to a server while Paper prepares; automatic queue and
lobby provisioning are not implemented yet.

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
```

Console may use `/sls join <registry> <server> <player>`. A player may target
another player only with `sls.command.admin` or `sls.command.join.others`.
Unauthorized administrative commands and arguments are omitted from tab
completion.

This fixture intentionally uses offline mode and no player forwarding. It binds
only to loopback and is not a production configuration.
