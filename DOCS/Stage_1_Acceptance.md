# Stage 1 Acceptance

This record closes the SLS-LITE Surface Level release gate. It verifies the
local network fixture with real world data inside one Velocity allocation.

## Fixture

- Status: `PASS`
- Velocity allocation: `SLS-LITE Velocity`
- Proxy address: `127.0.0.1:25565`
- Managed-memory admission budget: `4096 MiB`
- Managed backend port range: `127.0.0.1:25600-25610`
- Global ephemeral idle timeout: `20 seconds`
- Lifecycle fixture idle-timeout override: `60 seconds`
- Primary lobby: persistent managed `lobby/lobby`
- Lifecycle fixture: ephemeral `minigame/stage1_lifecycle`
- Lifecycle world source: `worlds/minigames/biome_run`
- Lifecycle limits: `1024 MiB`, `1` player, `2` instances

Observed runtime versions:

- SLS-LITE: `0.1.0-SNAPSHOT`
- Velocity: `4.0.0`
- Proxy Java: Eclipse Temurin `25.0.3`
- ViaVersion: `5.11.0`
- SLS-Limbo runtime: NanoLimbo `1.13.0`, revision `d192d57d`
- Managed lobby and lifecycle server: Paper `1.18.2` on bundled Java 17
- Verified Paper cache SHA-256:
  `0578f18f4d632b494b468ec56b3b414b5b56fea087ee7d39cf6dcdf4c9d01f05`

## Acceptance Results

| Check | Status | Evidence |
| --- | --- | --- |
| Baseline lobby and SLS-Limbo are healthy | PASS | SLS-Limbo became ready on port 25600 and persistent lobby `lobby.97f1ae` became ready on port 25601. |
| First lifecycle instance starts and accepts the player | PASS | `stage1_lifecycle.vued73` became ready on port 25602 and accepted Yeetoxic. |
| A full first instance causes a second instance to start | PASS | With the one-player first instance occupied, matchmaking created and transferred the player to `stage1_lifecycle.570v19`. |
| Both instances have unique IDs, ports, and directories | PASS | `vued73` used port 25602 and `570v19` used port 25603; each prepared its own two-volume instance directory. |
| A write in one instance does not alter its peer or source | PASS | A marker written under `stage1_lifecycle.ukvub4/world` was absent from `stage1_lifecycle.d9870f/world` and `worlds/minigames/biome_run`. |
| A third instance is rejected by `max_instances: 2` | PASS | Operator observed the expected rejection while both lifecycle instances were active. |
| Managed-memory over-allocation is rejected clearly | PASS | Operator observed the expected rejection when starting the 1536 MiB Combat Cube alongside the lobby and two 1024 MiB lifecycle instances. |
| Empty ephemeral instances stop after the idle timeout | PASS | `vued73` was detected empty, stopped after the 60-second fixture override, and exited with code 0. |
| Ephemeral processes, registrations, ports, and directories are removed | PASS | Both lifecycle processes and directories were removed; only Velocity, SLS-Limbo, and the persistent lobby remained, listening on ports 25565, 25600, and 25601. |
| Persistent lobby retains its ID and data across proxy restart | PASS | Startup preserved and resumed `lobby.97f1ae`; its three persistent volumes were reused in 4 ms rather than recopied. |
| Proxy shutdown stops all managed child JVMs | PASS | A Docker restart with both lifecycle instances active left no orphan child JVMs. The host's approximately 10-second stop window interrupted graceful cleanup, exercising unclean-shutdown recovery. |
| Startup reconciliation leaves no stale owned ephemeral instances | PASS | Reconciliation removed `stage1_lifecycle.ukvub4` and `stage1_lifecycle.d9870f`, preserved the persistent lobby, and reported zero failures. |
| Ports from stopped instances can be reused | PASS | Round 2 reused ports 25602 and 25603 after Round 1 released them; both ports were free again after reconciliation. |
| Players recover through SLS-Limbo and return to the lobby | PASS | Forced restart moved Yeetoxic to SLS-Limbo, restarted `lobby.97f1ae` with exit code 0, and automatically returned the player immediately after readiness. |
| Complete Maven test suite passes after the run | PASS | `mvn test` completed with 231 tests, 0 failures, 0 errors, and 0 skipped after adding protected-lobby restart/reset coverage. |

## In-Game Procedure

The operator follows the ordered checklist supplied for this acceptance run.
Host-side process, filesystem, port, and log observations are captured alongside
those player-facing results.

## Environment Notes

The local Pterodactyl stack runs through Docker Desktop on a Windows-backed bind
mount. Startup timings and transient file-copy behavior from this fixture are
not representative of native Linux hosting performance. Functional failures
remain release-blocking even when the environment is slow.

## Outcome

Stage 1 passed. The legacy-world network demonstrated isolated dynamic
instances, bounded capacity, cleanup, persistent-lobby reuse, unclean-shutdown
reconciliation, SLS-Limbo recovery, and successful client routing inside one
Velocity allocation.

## Observations

- The manual stop of `stage1_lifecycle.570v19` exceeded the Paper profile's
  30-second graceful-stop timeout and was forcibly terminated with exit code
  137. SLS-LITE still reached `STOPPED` and released the process, directory,
  registration, memory, and port. The earlier idle stop of the same software
  and world exited normally with code 0. The bounded force-termination fallback
  is accepted for Stage 1 on the Docker Desktop bind mount; native Linux
  performance and shutdown timing remain part of later constrained-host tests.
