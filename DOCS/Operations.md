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

Each instance also emits one bounded provisioning timing summary. It separates
operation-executor dispatch, software resolution, file preparation,
configuration, child launch, readiness detection, and Velocity/protocol
registration from total elapsed time. A termination summary reports graceful
or forced shutdown, cleanup, and total instance lifetime. Timings use a
monotonic clock and are emitted for successful, failed, and cancelled starts;
they contain no player names, host paths, or child-console content.

Matchmaking emits a separate bounded join summary. `queue` measures from
accepted matchmaking request until the Velocity connection request begins;
`transfer` measures that request's completion. Successful, rejected, failed,
timed-out, and cancelled outcomes are retained in the summary. The first
`ServerConnectedEvent` for each managed instance records `First-player timing`
from backend readiness, and each proxy startup records `Proxy restart recovery
timing` until the primary lobby is ready. These measurements also use a
monotonic clock, report once per lifecycle, and omit player identity.

On Linux, `/sls stats <server>` also reads the verified live child PID's
`/proc/<pid>/status` and `/proc/<pid>/io`. It reports current RSS, logical
characters read/written, and kernel-accounted storage bytes when those files
are accessible. These are cumulative process counters; cached I/O can make
storage reads smaller than logical reads. Hardened providers and non-Linux
hosts receive explicit `not measurable` or `unavailable` values. Per-process
network use remains unavailable because all managed children share the
host/container network namespace. Recursive instance disk measurement is kept
out of this synchronous command because it can block for seconds on translated
storage; use the opt-in storage benchmark for logical and allocated size.

`/sls system` reports the instance filesystem, usable space, supported
attribute views, same-filesystem atomic directory moves, reflink clone support,
Btrfs detection, Linux OverlayFS kernel/privilege prerequisites,
fuse-overlayfs prerequisites, process identity support, and the requested and
selected COW strategies for the configured instance path. Storage probes create
and remove contained temporary entries under the instances directory. Reflinks
are tested through a shell-free `cp --reflink=always` invocation where that
interface is available. Btrfs is tested by creating, snapshotting, mutating,
and deleting contained subvolumes on the exact instance filesystem. FUSE
eligibility requires a successful contained
mount/write-isolation/unmount/cleanup probe, not merely `/dev/fuse`.

`storage.strategy` accepts `auto`, `copy`, `reflink`, `btrfs`, `overlay`,
`fuse-overlay`, and `snapshot-hook`. Reflink and Btrfs preparation are active
after the configured instance path passes the corresponding contained probe.
`auto` then considers kernel OverlayFS and fuse-overlayfs after their
exact-path probes and finally selects `portable-copy`. An incompatible reflink
source falls back safely.
When Btrfs is selected, eligible `cow` source subvolumes are snapshotted;
ordinary or nested-subvolume sources fall back under `auto`. Explicit
`reflink` or `btrfs` fails and rolls back when its source is ineligible.
Snapshot helpers are explicit-only and excluded from automatic selection.
The executable receives `--protocol sls-snapshot-helper-v1`, an operation
(`probe`, `prepare`, `suspend`, `resume`, or `delete`), and absolute
`--source`/`--target` or `--instances-root` arguments. It must print exactly
`sls-snapshot-helper-v1 ok` and exit zero. SLS-LITE invokes no shell, caps
stdout/stderr, enforces the configured timeout, writes a durable manifest
before prepare, and requires delete to remove its target and mounts. Provider
operations must be idempotent: recovery can repeat an operation when the helper
succeeded but the process stopped before SLS-LITE could persist the resulting
state.

The general automatic priority is reflink, Btrfs snapshot, kernel OverlayFS,
rootless fuse-overlayfs, then portable copy. Btrfs eligibility is evaluated
per `cow` source after the storage strategy is selected; ordered merges retain
their declaration-order portable semantics.

Expected optional capabilities that are unavailable under `auto` are reported
as `INFO`, not warnings. An explicitly requested unavailable strategy remains a
warning at the individual probe and a startup `FAILURE` at strategy selection.
Configured but unused Java runtimes and the managed-memory budget explanation
are informational for the same reason.

When `auto` or kernel OverlayFS is requested and the kernel driver plus
`CAP_SYS_ADMIN` are available, SLS-LITE performs a contained probe beneath the
instance-storage path. It mounts one immutable lower directory with private
upper/work directories, verifies reads and write isolation, unmounts, verifies
upper-layer persistence, and removes the probe. An unmount failure preserves
the probe path for operator recovery instead of traversing a potentially live
mount.

Selected OverlayFS instances store private upper/work directories and a durable
manifest inside the instance directory. Persistent instances are unmounted
after stop and remounted on reuse. Reset, deletion, rollback, and startup crash
reconciliation suspend managed layers before traversing or moving directories.
Unmount refuses a live filesystem unless its type and upper/work paths match
the manifest.

## Common Failures

**Managed initialization failed**

Run `/sls system` from console where available and inspect startup capability
failures. Check child Java paths, writable storage, loopback ports, memory/process
budget, and forwarding consistency.

**Insufficient managed memory**

Stop unused instances, reduce safe blueprint reservations, or increase both the
real panel allocation and SLS-LITE admission budget. Do not raise only the
declared budget beyond the real host limit.

The blueprint `memory` value and `resources.total_memory_mib` are admission
limits for child Java maximum heaps; they are not measurements of total
resident memory. JVM native memory, thread stacks, mapped files, Velocity,
SLS-Limbo, and the operating system all require additional panel/host
headroom. Use `/sls stats` on a representative loaded instance and container
metrics when sizing a real allocation. The measured local-fixture values and
their limitations are recorded in
[Pterodactyl Local Testing](Pterodactyl_Local_Testing.md#representative-storage-and-resource-samples).

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

Prefer a native Linux filesystem for `paths.instances`, blueprint sources, and
the software cache whenever the provider exposes one. Docker Desktop's
Windows-translated `9p` storage is useful for compatibility checks, but the
Stage 3 samples measured roughly 19-46 times slower median preparation and
roughly 10-142 times slower median cleanup than WSL2 ext4 across the sampled
profiles. Remote or translated filesystems can have different caching and
durability behavior, so benchmark the exact provider path before selecting
capacity or timeout thresholds.

For repeatable copy samples, compile test classes and run
`StoragePerformanceBenchmarkHarness` with an immutable source directory, an
existing empty disposable target root, a profile label, and an optional repeat
count. Two further optional arguments set preparation and cleanup p95 limits in
milliseconds; both must be supplied, and an exceeded limit returns a failing
process. The harness uses the production portable preparer, verifies file count
and logical bytes, deletes every target, and reports preparation/cleanup
distributions, allocated bytes, peak harness RSS, and `/proc/self/io` counters
where available. It does not drop host caches or claim durable-write latency.

**Resource pack does not load**

Confirm `resource-pack` is a client-reachable HTTP(S) URL and its SHA-1 matches
the exact served ZIP. A path inside the game container cannot be downloaded by
the client.

## Constrained Hosts

- Keep child console mirroring off.
- Use small safe `-Xms` values and honest `memory_limit` reservations.
- Limit simultaneous instances and managed ports.
- Prefer exact cached software and avoid deleting reusable caches.
- Installed server artifacts are reused only after provider size/checksum
  verification. Files written by a running child, including Paper `cache/` and
  `libraries/`, are never promoted into the shared software base because the
  child is not a trusted artifact source.
- Keep source worlds immutable and reasonably sized.
- Use native Linux storage where possible.
- Measure startup and loaded memory for the actual plugins and worlds.

The Stage 3 squeezed-allocation audit found:

| Area | Bounded behavior | Operator responsibility |
| --- | --- | --- |
| Memory and processes | Heap admission is synchronized; SLS-Limbo participates; process count, ports, blueprint instance count, and the 32-entry preparation queue are bounded. | Keep the admission budget below real panel capacity and include JVM native memory, Velocity, and the OS. |
| Idle instances | Non-lobby, non-keepalive instances stop after their configured empty interval; a pending/new join cancels the drain. | Use a nonzero timeout on constrained hosts and reserve keepalive only for required services. |
| Software cache | Installed artifacts are reused only after provider verification; mutable child output is never promoted into the shared base. Installer history and output are bounded in memory. | Verified artifacts are retained indefinitely because automatic eviction could remove the only runnable version. Remove obsolete version directories only during a maintenance window after confirming no blueprint or instance needs them. |
| Child output | In-memory output retains 1,000 lines; command pages return at most 100; the optional file has a per-instance hard cap and is truncated on the next process start. | Lower the file cap or disable it where disk is scarce. |
| Instance storage | Ephemeral instances are deleted after stop; persistent instances remain; recognized stale ephemerals and interrupted reset siblings are reconciled at startup. Unknown or ambiguously owned directories are preserved. | Investigate preserved-unknown warnings instead of deleting blindly. No automatic persistent-instance quota or cache eviction exists. |

This policy favors recoverability over aggressive reclamation: when ownership
or process identity is uncertain, SLS-LITE leaves the bytes in place and names
the directory in diagnostics.

On Linux, startup also reports a finite cgroup v1/v2 hard limit and current
usage when the container exposes trustworthy controller files. This measurement
is diagnostic: SLS-LITE does not raise its configured managed-memory admission
budget from it, and an absent or unbounded cgroup limit is reported as
unavailable rather than guessed from host RAM.

SLS-LITE uses reflink clones, eligible Btrfs subvolume snapshots, kernel
OverlayFS, or fuse-overlayfs for `cow` volumes when the selected storage
supports them, with transactional portable copying as the universal fallback.
Explicit snapshot helpers extend the same lifecycle to operator-managed ZFS,
LVM-thin, or provider storage without auto-discovery. On non-Windows hosts, the
portable fallback preserves
sampled large zero runs as sparse extents while small and ordinary files retain
native Java copying. Windows retains native copying because seek-created holes
do not guarantee NTFS sparse allocation without platform-specific controls.
Full directory snapshots use bounded parallel copying with no more than four
workers and two in-flight tasks per worker. Ordered first-wins volume merges
remain sequential. Failure and cancellation stop queued work, drain every
started worker, and only then allow transactional rollback to remove the
partial destination. Stage 3 also tracks safe reuse of verified immutable
artifacts and avoiding unnecessary reconstruction of valid persistent
instances. Mutable world files are never hard-linked.

In the disposable Linux baseline, a 64 MiB logical sparse file used 64 MiB
after ordinary Java copying and 2 MiB through the sparse-aware fallback; source
and target content matched byte-for-byte.

The enabled actual-preparer path was benchmarked against its sequential form.
On Windows/NTFS, 1,000 16 KiB files improved from 2,221 ms to 1,141 ms and
eight 8 MiB files from 49 ms to 23 ms. In disposable Linux Docker storage, the
same workloads improved from 176 ms to 162 ms and from 66 ms to 12 ms.

Valid persistent instances reuse their existing directory on restart and do
not reapply a changed software template. Only an explicit reset constructs a
new sibling staging directory and swaps it after initialization commits.
