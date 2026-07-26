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
and installs managed `lobby/lobby`, `test/smoke`, `test/smoke2`, and
`test/persistent-smoke` blueprints.

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
3. If the child process survived, SLS-LITE must stop it only after matching both
   its recorded PID and process start time.
4. The startup reconciliation summary must report the ephemeral instance as
   removed before the managed lobby starts.
5. A verified surviving `save: true` child must be stopped while its directory
   remains and its metadata is normalized to `STOPPED`.
6. An unmarked legacy directory and live processes without verifiable identity
   data must remain and be reported as preserved.

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
bypasses the target instance's blueprint player limit. It still requires the
target player to be on a ready, registered SLS-LITE instance. Verify the same
command without `--force` returns `Instance is full` when that instance has
reached `max_players`.

### Forced Managed-Lobby Stop

Use this only while testing a managed primary lobby and a healthy SLS-Limbo:

1. Join the managed lobby and confirm `/sls info this` identifies its exact
   `<blueprint>.<id>` instance.
2. Run `/sls stop this` and confirm SLS-LITE rejects the protected stop.
3. Run `/sls stop this --force` as a built-in administrator or with
   `sls.command.stop.force`.
4. While evacuation is in progress, connect another player and confirm that
   player routes to SLS-Limbo instead of the draining managed lobby.
5. Confirm the player already on the managed lobby transfers to SLS-Limbo
   before Paper receives its stop command.
6. If evacuation fails, confirm the stop is cancelled and the managed lobby
   becomes the preferred lobby again.
7. Confirm the proxy log records the sender, target, and final exit code.
6. Wait longer than the configured managed-lobby recovery backoff and confirm
   no replacement lobby starts.
7. Restart Velocity and confirm normal managed-lobby startup resumes.

If SLS-Limbo is unavailable while players remain on the managed lobby, the
command must cancel the stop and report that no alternate lobby is ready.

## Persistent Lifecycle Test

Start and join the persistent fixture:

```text
/sls start test persistent-smoke
/sls join test persistent-smoke
```

Record the composite instance ID, place a block, and run:

```text
/sls restart <instance-id>
```

After reconnecting, verify the server keeps the same ID and the placed block
still exists. Then place a different block and run:

```text
/sls reset <instance-id>
```

Reset is destructive. Verify players move to the lobby, the same instance ID
returns, and both player changes are gone because the directory was restored
from the prepared Paper template. Restart Velocity while the persistent server
is stopped, then run `/sls restart <instance-id>` to verify metadata recovery.

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
sls logs <instance-id>
sls logs <instance-id> 1 100
sls stats <instance-id>
sls system
```

The first form works from players or the Velocity console. The `this` selector
is player-only and resolves the managed backend the sender currently occupies.
The target must be a ready SLS-LITE instance and the sender must have
`sls.command.admin` or `sls.command.console`.

From a player connected to a managed backend, `this` must resolve consistently
for `console`, `info`, `logs`, `stats`, `status`, and `stop`. Console senders
must provide an explicit composite instance ID. A player connected to an
external or otherwise unmanaged backend must receive `is not an SLS server`
instead of treating `this` as a literal instance ID.

`sls logs` uses the vSLS `<server> [page] [lines]` grammar. Page 1 contains the
newest retained output, pages preserve chronological line order, `lines`
defaults to 50 and accepts `max`, and each instance retains at most 1,000 lines.
The `this` selector works for player senders on a managed backend.

`sls stats` reports only values available from the local Java supervisor.
Configured child memory is not presented as measured usage. `sls system`
reports Velocity JVM pressure separately from the managed child-memory budget.
It also reports startup capability results for writable storage, loopback port
binding, and child Java execution. Hover a result in-game for its probe detail.

With the default managed-output policy, Paper output should not be repeated in
the Velocity console after the short SLS-LITE lifecycle messages. Verify that:

1. `/sls logs <instance-id>` still shows the Paper output.
2. `<instance-directory>/logs/sls-lite-console.log` exists.
3. The temporary file never exceeds `managed_output.temporary_file_max_kib`.
4. Setting `mirror_to_proxy_console: true` and restarting Velocity restores the
   prefixed `[instance-id]` console stream.

## Permissions

Self-service `list`, `registries`, `version`, `find`, and `join` commands are
public. Administrative commands accept the upstream-compatible
`sls.command.admin` umbrella node. Granular aliases are:

```text
sls.command.blueprints
sls.command.info
sls.command.logs
sls.command.reload
sls.command.start
sls.command.stats
sls.command.status
sls.command.stop
sls.command.system
sls.command.join.others
sls.command.dequeue.others
```

Console may use `/sls join <registry> <server> <player>`. A player may target
another player only with `sls.command.admin` or `sls.command.join.others`.
Unauthorized administrative commands and arguments are omitted from tab
completion.

This fixture intentionally uses offline mode and no player forwarding. It binds
only to loopback and is not a production configuration.

For a production-style forwarding test, configure Velocity with modern
forwarding, place its secret in `forwarding.secret`, and set:

```yaml
forwarding:
  mode: modern
  online_mode: true
  secret_file: forwarding.secret
```

After starting a managed Paper instance, verify `spigot.yml` has BungeeCord
forwarding disabled and `config/paper-global.yml` has Velocity forwarding
enabled with the matching online-mode value. Do not publish either secret file.
