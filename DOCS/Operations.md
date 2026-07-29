# Operations And Recovery

SLS-LITE owns local child processes, loopback registrations, admission
reservations, instance directories, and queued transfers. Use SLS-LITE commands
or normal Velocity shutdown so those resources are released coherently.

## Lifecycle

Managed instances move through explicit states:

```text
CREATED -> PREPARING -> STARTING -> READY -> STOPPING -> STOPPED
                                      |
                                      `-> FAILED
```

An instance is registered with Velocity only after its configured readiness
pattern is observed and backend protocol synchronization completes. Startup
failure, cancellation, and timeout unregister the backend and release owned
memory, process slots, ports, and ephemeral files.

## Starting And Joining

`/sls start` creates an instance immediately. `/sls join` prefers a ready
instance with capacity and creates one only when necessary. Multiple requests
for the same cold destination share its startup.

Players stay on their current healthy backend while queued. SLS-Limbo is used
only when no normal backend is safe. Queue entries are removed on success,
timeout, cancellation, disconnect, startup failure, and shutdown.

## Stopping

Ordinary stop:

1. prevents new admission to the target;
2. moves connected players to the primary lobby when possible;
3. sends the software profile's stop command;
4. waits for its stop timeout;
5. force-terminates only when graceful shutdown fails;
6. releases registrations and resource reservations;
7. removes the directory only when it is verified ephemeral.

Stopping during installation or preparation cancels that instance's wait
without cancelling a shared download required by another instance.

## Idle Cleanup

Empty, ready, ephemeral instances stop after
`lifecycle.idle_shutdown_seconds`. Rejoining or queueing during the delay
cancels the drain. Persistent instances, the active lobby, keep-alive
blueprints, and disabled policies are excluded.

## Persistent Instances

Persistent directories have `.sls-lite-instance.properties` ownership
metadata. Normal restart verifies the recorded definition identity. Reset uses
sibling staging and backup directories so an interrupted replacement can be
rolled back during startup reconciliation.

Never manually edit ownership metadata or remove a reset backup while Velocity
is running. Back up persistent content before `/sls reset`.

## Startup Reconciliation

On startup SLS-LITE:

- resolves interrupted reset transactions;
- validates owned instance metadata;
- removes confirmed stale ephemeral directories;
- preserves persistent directories;
- verifies PID and process start time before treating a child as owned;
- preserves malformed, unknown, or unverifiable directories for operator
  inspection.

Reconciliation is deliberately conservative. Unknown data costs disk space but
is not silently deleted.

## Lobby Recovery

A managed primary lobby and SLS-Limbo each have independent bounded recovery
budgets with exponential backoff. A healthy period resets the used budget.

Protected lobby stop/restart/reset requires its matching `--force` permission.
Players are evacuated to SLS-Limbo before the process changes. Restart/reset
restores routing only after the lobby is ready and returns tracked players from
SLS-Limbo.

If both normal lobby and SLS-Limbo are unavailable, SLS-LITE remains loaded for
console administration and recovery. Players without a safe backend receive a
clear unavailability message rather than entering a reconnect loop.

## Logs And Diagnostics

Use:

```text
/sls info
/sls info <server>
/sls status <server>
/sls stats <server>
/sls logs <server> [page] [lines]
/sls install info
/sls install logs <software> <version>
/sls system
```

Each managed instance retains 1,000 recent lines in memory for `/sls logs`.
Temporary file output stops at its configured hard cap. Files are not rotated.
Proxy mirroring is disabled by default to prevent child-console spam.

The proxy console records bounded state changes: accepted operations,
installation state, preparation, process start, readiness, player connection,
recovery, stop, and failure summaries. Failed starts include a small recent
child-output excerpt; full retained output remains in `/sls logs` and the
temporary file.

## Common Failures

**Managed initialization failed**

Run `/sls system` from console where available and inspect startup capability
failures. Check child Java paths, writable storage, loopback ports, memory/process
budget, and forwarding consistency.

**Insufficient managed memory**

Stop unused instances, reduce safe blueprint reservations, or increase both the
real panel allocation and SLS-LITE admission budget. Do not raise only the
declared budget beyond the real host limit.

**Server remains in STARTING**

Inspect `/sls logs <instance>` for first-run Paperclip work, EULA rejection,
wrong Java, plugin errors, or a readiness pattern that does not match the
software output.

**Player remains in SLS-Limbo**

Run `/sls info` and `/sls system`. Verify the primary lobby is `READY`, its
protocol is compatible, and ViaVersion mappings are available where required.

**World or plugin is slow on the local fixture**

Docker Desktop and Windows-backed bind mounts heavily penalize copies and
region-file access. Reproduce on native Linux storage before treating timing as
a release performance baseline. Functional failures still count everywhere.

**Resource pack does not load**

Confirm `resource-pack` is a client-reachable HTTP(S) URL and its SHA-1 matches
the exact served ZIP. A path inside the game container cannot be downloaded by
the client.

## Constrained Hosts

- Keep child console mirroring off.
- Use small safe `-Xms` values and honest `memory_limit` reservations.
- Limit simultaneous instances and managed ports.
- Prefer exact cached software and avoid deleting reusable caches.
- Keep source worlds immutable and reasonably sized.
- Use native Linux storage where possible.
- Measure startup and loaded memory for the actual plugins and worlds.

SLS-LITE currently performs full portable copies for `cow` volumes. Reflink and
overlay optimizations are roadmap work, not active behavior.

