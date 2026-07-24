# Historical Single-Host Baseline

The last known working predecessor to SLS-LITE is:

- Repository: `jessefaler/SLS`
- Commit: `4f9b7ca7f6d857d43253076f1627ad4087f663ab`
- Plugin version in source: `2.1.2`
- [Historical source tree](https://github.com/jessefaler/SLS/tree/4f9b7ca7f6d857d43253076f1627ad4087f663ab)

This commit is the behavioral baseline for running a small network entirely
under Velocity. Current vSLS remains the command and terminology target. The
historical code establishes which single-host workflows were proven useful; it
is not an implementation to copy unchanged.

## Proven Behavior To Preserve

- Launch backend Java processes directly from the Velocity allocation without a
  controller, daemon, Docker, or second machine.
- Bind launched servers to dynamically selected local ports and register them
  with Velocity.
- Start a server on demand when a player requests it.
- Queue players until the child server reports readiness.
- Connect the player after readiness and report startup failure or timeout.
- Remove disconnected players from the queue.
- Stop an in-progress server when its queue becomes empty.
- Support self, named-player, `all`, and `local` join targets.
- Send commands to the child process through standard input.
- Select a custom Java executable and memory allocation per configured server.
- Restore resettable worlds from a clean template.
- Unregister backends and remove resettable runtime world data after exit.
- Provide normal and per-player debug startup feedback.
- Accept requests from a backend plugin over the `slimelabs:network` plugin
  message channel.

## Behavior To Modernize

- Replace hard-coded `minigames`, `AdventureMaps`, and `archives` registry
  classes with dynamic `blueprint.type` registries.
- Replace writable shared server folders with isolated instance directories.
- Replace random check-then-release port selection with synchronized port
  reservation and bounded retries.
- Register a backend only after it reaches `READY`.
- Replace polling every 70 or 250 milliseconds with lifecycle futures and queue
  events.
- Replace global static mutable state and unsynchronized collections with
  explicit thread-safe services.
- Replace `Process.destroy()`-only shutdown with the configured stop command,
  timeout, and force-termination fallback.
- Bound queue timeouts explicitly and clean every queue entry on timeout,
  cancellation, disconnect, failure, and shutdown.
- Validate plugin messages, player presence, argument counts, paths, and process
  state instead of relying on casts, `Optional.get()`, or ignored exceptions.
- Keep child processes on loopback and prevent configured paths from escaping
  the permitted data directory.

## Compatibility Mapping

| Historical concept | SLS-LITE equivalent |
| --- | --- |
| Registry class | Dynamically discovered `blueprint.type` |
| Registry world entry | Blueprint |
| `ServerInstance` | `ManagedInstance` plus process supervisor |
| `ServerRegistry` | `ServerController` / `InstanceManager` |
| `PlayerConnector` polling task | Matchmaking queue plus readiness future |
| `server-folder-path` | Software base plus isolated instance/template paths |
| `ram-allocation` | Blueprint memory limit and shared `ResourceBudget` |
| `reset-world` | Ephemeral instance or explicit template reset |
| Custom Java path | Software-profile Java executable/runtime selection |
| `shutdown` command | Modern vSLS-compatible `stop` command |
| `config reload/view` | Modern `reload` and `blueprint` command forms |

## Reference Priority

When the two references differ:

1. Current vSLS defines user-facing command names, arguments, permissions,
   terminology, and documentation.
2. This historical commit defines the minimum single-host workflows SLS-LITE
   should recover.
3. SLS-LITE uses the safer current architecture when the historical
   implementation has concurrency, lifecycle, path, or process-management
   weaknesses.
