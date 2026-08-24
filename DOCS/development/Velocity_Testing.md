# Velocity Test Environment

[Documentation home](../README.md)

The primary local integration fixture is the preserved SLS v2.1.2 minigame
network. It validates SLS-LITE against real worlds, exact historical Paper
versions, modern organized blueprints, and a current Velocity proxy.

The synthetic `test-server/` fixture remains available for isolated development:

```powershell
.\scripts\setup-velocity-test.ps1
```

It is not the primary historical-world regression network.

## Primary Network

The Pterodactyl allocation stores active definitions in:

```text
plugins/sls-lite/blueprints/lobbies/
plugins/sls-lite/blueprints/minigames/
plugins/sls-lite/blueprints/adventures/
```

Imported source archives, rollback copies, and retired smoke blueprints stay
outside the live `blueprints/` tree. SLS-LITE discovers blueprints recursively,
but commands continue to use each blueprint's declared registry and ID.

The active network contains:

```text
lobby/lobby
minigame/biome_run
minigame/blastoff
minigame/block_hunt
minigame/chunk_runner
minigame/combat_cube
minigame/meteor_miners
minigame/missile_wars
minigame/the_floor_is_lava
minigame/wildfire
adventure/undead
```

Hover every row from `/sls blueprints [registry]`. The tooltip must show its
registry and ID, exact software version, memory, player and instance limits,
persistence, active instances, and mounted world volumes. Clicking a row should
place the matching `/sls join <registry> <id>` command in chat.

## Baseline Run

Start the Velocity allocation and connect to `127.0.0.1:25565`.

1. Confirm the historical managed lobby reaches `READY` and receives the first
   connection.
2. Run `/sls registries`, `/sls blueprint lobby`, `/sls blueprints lobby`,
   `/sls blueprints minigame`, and `/sls blueprints adventure`.
3. Run `/sls info`, `/sls system`, and `/sls list`.
4. Join `minigame/biome_run`, then return to `lobby/lobby`.
5. Confirm the player remains on the current healthy server while a destination
   starts and sees the vSLS-style action-bar animation.

## Version Matrix

Run every blueprint without upgrading its source world. SLS-LITE must install
and reuse the exact Paper version declared by the blueprint.

Start with the Paper 1.18.2 group, then test `wildfire` on 1.15.2,
`missile_wars` on 1.16.5, and the 1.11.2 blueprints `blastoff` and
`chunk_runner`. This ordering establishes a known-good baseline before testing
the oldest Paperclip bootstraps.

For each blueprint:

```text
/sls join <registry> <id>
/sls list
/sls info this
/sls status this
/sls logs this
```

Expected behavior:

1. A missing exact software version enters installation once and is cached.
2. The proxy logs a short install, prepare, process, readiness, and connection
   sequence without copying the child server's full output.
3. A successful instance becomes `READY`, registers with Velocity, and accepts
   the queued player.
4. A failed instance reports the root cause and at most three recent child
   output lines in the proxy console.
5. `/sls install info` and `/sls install logs <software> <version>` retain the
   detailed installation state.

Historical Paperclip can take several minutes during its first bootstrap on a
constrained host. The default Paper readiness upper bound is ten minutes; the
instance becomes available immediately after its readiness marker and does not
wait for that limit.

## Queue And Capacity

1. Request the same cold blueprint twice while it starts. The second request
   must report that the player is already queued.
2. Run `/sls dequeue`; the pending transfer must be cancelled.
3. Disconnect while queued; the request must be removed.
4. Test a blueprint at capacity and confirm matchmaking starts another instance
   when its `max_instances` permits one.
5. With a one-player blueprint, connect the target player and verify a second
   ordinary matchmaking or `/server` route is rejected at the public limit.
   Then run `/sls join player <target> --force` as an authorized administrator:
   the administrator (not the target) must enter that same instance. Repeat as
   an unauthorized player and verify permission denial. Confirm the generated
   backend `max-players` supplies bounded technical headroom without changing
   the blueprint's advertised or matchmaking capacity.
6. Cancel the last request for a queue-created instance and confirm startup is
   stopped only when it reaches a safe lifecycle point.

The repository includes a bounded offline-client check for steps 5 and the
ordinary direct-join portion of step 4:

```powershell
node tools/protocol-smoke/force-join-smoke.js `
  --registry test --blueprint smoke --version 1.21.11
```

Use it only against the disposable offline fixture. The selected blueprint
must have `annotations.sls-lite.max-players: 1` and
`server.limits.max_instances: 1`, `SLS_FORCE_ADMIN` must be a
built-in test administrator, and the client protocol must be supported by the
fixture backend or its compatible Via plugins. The script keeps the target
connected while it verifies ordinary denial, unauthorized force denial,
authorized force transfer, and target identity. A real-client `/server` attempt
remains required for the native server-selection route; unit tests cover its
race-safe admission gate but do not replace that live check.

## Lifecycle And Cleanup

1. Leave an ephemeral minigame empty and confirm idle cleanup stops it.
2. Rejoin during the idle delay and confirm cleanup is cancelled.
3. Confirm the managed lobby remains running while empty.
4. Start a server and immediately stop it while preparing. The port, memory,
   process, and temporary directory must be released.
5. Stop a player-occupied minigame and confirm evacuation completes before its
   stop command is sent.
6. Verify protected lobby stop requires `/sls stop this --force`, drains new
   arrivals to SLS-Limbo, and suppresses automatic lobby recovery.
7. Restart Velocity uncleanly and verify marked ephemeral instances are
   reconciled while unverified directories are preserved and reported.

## Console And Observability

Exercise:

```text
/sls console this list
/sls logs this
/sls logs this 1 100
/sls stats this
/sls system
```

The proxy console should show only bounded SLS-LITE state changes:

- accepted administrative and matchmaking requests;
- software installation start, completion, and failure;
- instance preparation, child PID, readiness, and exit;
- player connection success or failure;
- stop, restart, and reset requests.

Managed Paper output remains available through `/sls logs` and the bounded
temporary instance log. It is mirrored to the proxy only when
`managed_output.mirror_to_proxy_console` is explicitly enabled.

## Regression Gate

The regression run passes when the historical lobby and every imported game can
install, start, join, stop, and clean up without modifying its source world.
Record game-specific plugin or data incompatibility separately from an
SLS-LITE lifecycle failure. The modern compatibility regression uses
unmodified SLS blueprints as its input.
