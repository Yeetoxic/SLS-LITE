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
and installs managed `lobby/lobby`, `test/smoke`, and `test/smoke2` blueprints.

## Manual Test

Start the proxy:

```powershell
cd test-server
.\start.ps1
```

Run these commands in the Velocity console:

```text
sls registries
sls blueprints lobby
sls blueprints test
sls list
```

Expected behavior:

1. SLS-LITE automatically starts `lobby.xxxxxx` during proxy initialization.
2. The lobby reaches `READY` before Velocity registration.
3. The `lobby` and `test` registries are listed.
4. `sls list` shows the reserved managed lobby.

After the instance reports `READY`, connect Minecraft to `localhost:25565`.
SLS-LITE must route the first join to `lobby.xxxxxx`. In game, run:

```text
/sls version
/sls registries
/sls list
/sls find <your-player-name>
/sls join test smoke
/sls dequeue
```

The join command should keep the player on the lobby while provisioning
`smoke.xxxxxx`, then transfer only after the game server reaches `READY`.

Queue checks:

1. Run `/sls join test smoke` twice while Paper starts. The second request must
   report that the player is already queued.
2. Run `/sls dequeue`; the pending transfer must be cancelled.
3. Disconnect while queued; the request must be removed automatically.
4. With admin permission, test `/sls join test smoke all`,
   `/sls join test smoke local`, and a specific player name.
5. When the last request for a queue-created instance is cancelled, the instance
   should stop after it reaches a safely stoppable state.

Idle cleanup checks:

1. Leave `smoke.xxxxxx` so it has no connected players.
2. The fixture's `lifecycle.idle_shutdown_seconds` is `20`; within the delay
   plus the five-second scan interval, the console must report
   `Stopping idle instance smoke.xxxxxx`.
3. Rejoin or queue for the instance before the delay expires and confirm the
   shutdown is cancelled.
4. Leave the managed lobby empty and confirm it remains running.
5. Start a server and immediately run `sls stop <instance-id>` while it is
   preparing; startup must cancel and release its port, memory, and directory.

Unclean-shutdown reconciliation checks:

1. Confirm a running managed instance contains
   `.sls-lite-instance.properties`.
2. Terminate the test allocation without a graceful proxy shutdown, then start
   it again.
3. The startup reconciliation summary must report the dead ephemeral instance
   as removed before the managed lobby starts.
4. An unmarked legacy directory and any `save: true` directory must remain and
   be reported as preserved.
5. A loopback port still owned by a surviving process must be skipped by the
   allocator.

Managed-lobby recovery checks:

1. Wait for the managed lobby to reach `READY`, then forcibly terminate only its
   Paper process.
2. `/sls info` must report `RECOVERING`, and the console must show recovery
   attempt `1/3` after the fixture's two-second delay.
3. A new `lobby.xxxxxx` instance must start, register with Velocity, and reach
   `READY`; the crashed instance directory must be removed.
4. Repeat failures and confirm delays increase to four and eight seconds, then
   stop after the third failed recovery attempt.
5. Restart the proxy normally and confirm shutdown does not schedule a lobby
   recovery.

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

1. A player on the stopped game instance moves to `lobby.xxxxxx` before
   shutdown.
2. `sls stop` exits with code `0`, unregisters the backend, releases memory and
   port reservations, and removes the ephemeral instance directory.
3. Attempting `sls stop <lobby-instance-id>` is rejected.
4. Velocity shutdown stops the lobby and leaves no managed child processes.

Console command checks:

```text
sls console <instance-id> say SLS-LITE console test
sls console this list
```

The first form works from players or the Velocity console. The `this` selector
is player-only and resolves the managed backend the sender currently occupies.
The target must be a ready SLS-LITE instance and the sender must have
`sls.command.admin` or `sls.command.console`.

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
