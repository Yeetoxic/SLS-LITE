# Internal Invariants

This document records the implementation rules that must remain true while
SLS-LITE evolves. It describes current code, not planned behavior. Changes to
these rules require focused failure-path tests and corresponding updates here.

## Instance Lifecycle

`InstanceLifecycle` is the authoritative state machine:

```text
CREATED -> PREPARING -> STARTING -> READY -> STOPPING -> STOPPED
    |          |           |          |          |
    +----------+-----------+----------+----------+-> FAILED
                                      FAILED -> STOPPING -> STOPPED
```

Transitions are synchronized, same-state transitions are rejected, and
`STOPPED` is terminal. A `ManagedInstance` is published in the active registry
only after memory and a loopback port have both been reserved and its lifecycle
has entered `PREPARING`.

Starting an instance has two distinct parts:

1. `InstanceManager` resolves one immutable blueprint/software snapshot and,
   under its manager monitor, checks limits, reserves memory, allocates a port,
   creates the instance, and publishes it to the active registry.
2. The bounded operation executor performs installation, transactional file
   preparation, instance-confined configuration, process launch, readiness,
   protocol synchronization, and Velocity registration.

The instance is externally usable only after the child readiness pattern has
matched, ViaVersion synchronization has completed when available, and the
loopback backend has been registered. Completing the process readiness future
alone is not sufficient.

A stop during `PREPARING` or `STARTING` records a cancellation request. File
preparation observes that request and rolls back staging; process startup uses
its dedicated cancellation path rather than waiting for the graceful-stop
deadline. Stop, failed start, unexpected exit, and proxy shutdown converge on
cleanup. Cleanup is identity-checked and performs, in order:

- Velocity unregistration;
- output/log closure;
- port and memory release;
- storage suspension or unmount;
- deletion of owned ephemeral storage, or metadata persistence for durable
  storage;
- exceptional completion of unresolved readiness;
- removal of that exact instance object from the active registry.

Persistent restart preserves the composite instance ID and directory. It
requires valid ownership metadata and an equivalent definition identity.
Definition drift and a persistence-mode change block reuse until the operator
performs an explicit reset. Reset uses a sibling staging directory and retains
the old directory as rollback state until the replacement commits.

At proxy startup, reconciliation runs before new instances are admitted. It
recovers interrupted reset/delete transactions, validates ownership and process
identity, preserves known persistent data, and removes stale ephemeral data
only when SLS-LITE ownership is proven. Unknown directories are preserved.

## Concurrency Boundaries

| Owner | Protected state | Rule |
| --- | --- | --- |
| `InstanceManager` monitor | Active instances, pending restarts, admission, shutdown flag. | Hold only for short registry/admission changes. Never perform installation, filesystem work, process waiting, or player transfer while holding it. |
| `ManagedInstance` monitor | Stop request, process attachment, registration flag, and terminal cleanup for one instance. | Resolve the instance first, then lock it. Code that needs both locks takes the instance monitor before briefly checking the manager registry. |
| `InstanceLifecycle` monitor | State transitions. | All changes use `transitionTo`; callers do not assign state directly. |
| `ProcessSupervisor` monitor | Active child registry and supervisor shutdown. | Duplicate IDs and the process ceiling are rejected before launch. |
| `SupervisedProcess` monitor | Process handle, readiness/stop deadlines, input, and terminal completion. | Readiness, timeout, cancellation, stop, and exit callbacks must complete futures once and cancel obsolete deadlines. |
| `LocalJoinService` monitor | Player queue, queue-owned instances, and draining instances. | A player has at most one queue entry. Network futures run outside the monitor and terminal callbacks remove the same entry before orphan cleanup. |
| Lobby-provider monitors | Provider status, generation, retry tasks, and stable-window tasks. | Generation tokens make callbacks from superseded attempts no-ops. Primary and limbo recovery budgets remain independent. |
| `InstanceCrashRecovery` monitor | Per-persistent-ID retry count, retry task, and stable-runtime reset task. | Only opted-in non-lobby persistent instances recover; administrative termination cancels state, and recovery cannot pass a pending restart/reset/delete. |
| `DefinitionCatalog` atomic reference | Matched blueprint and software maps. | Reload validates candidates first and publishes both maps in one immutable snapshot. Readers never observe a half-reload. |
| Installation maps/futures | One active installation per normalized target. | Compatible consumers share one future. Cancelling one instance's wait does not cancel the shared installation. |

Do not block Velocity event threads on child readiness or process exit. Public
operations return futures, and callbacks must re-check object identity or
generation before mutating current state. Duplicate cleanup is expected to be
possible and must be harmless; resource releases return an empty/zero result
when ownership has already been released.

## Resource Accounting

Resource settings are admission controls, not operating-system enforcement.

| Resource | Acquired by | Ownership key | Released by |
| --- | --- | --- | --- |
| Managed memory | `InstanceManager` or `SLSLimboProvider` before launch. | Instance ID or `sls-limbo`. | Terminal instance cleanup or terminal limbo cleanup, exactly once logically. |
| Loopback port | The same owner after memory admission. | Reserved port number. | Terminal cleanup after backend/process use ends. |
| Managed process slot | `ProcessSupervisor.start`. | Process instance ID. | Supervisor exit handling removes the process; shutdown stops the remaining snapshot. |
| Velocity backend name | Backend registry after readiness and protocol sync. | Instance ID or `sls-limbo`. | Instance/provider cleanup before the port is returned. |
| Instance directory and mounts | Storage preparation transaction. | Valid ownership metadata and strategy manifest. | Suspend on stop; owned ephemeral delete, persistent preservation, reset, or startup reconciliation. |
| Queue-created instance | `LocalJoinService` when no ready capacity exists. | Instance ID in `queueOwnedInstances`. | Ownership transfers away after a successful connection; otherwise the last terminal queue path stops the orphan. |
| Output worker and temporary log | Process/output initialization. | Managed process/instance. | Process exit and instance cleanup; output workers have bounded idle lifetime. |

`resources.total_memory_mib` is a configured ledger shared by managed instances
and SLS-Limbo. It does not measure or replace the Pterodactyl/container memory
limit. `resources.max_managed_processes` is enforced by `ProcessSupervisor`;
the configured loopback range is a separate hard ceiling because each child
needs one reserved port. Configuration validation ensures the managed lobby
and enabled limbo can be admitted together.

Admission order is memory, port, active instance, asynchronous operation.
Failure unwinds in reverse. A new acquisition must either join this order or
provide tests proving that every subsequent failure releases all earlier
reservations.

## Path And Filesystem Security

Paths cross several trust boundaries and are validated by the component that
owns the resulting filesystem operation:

| Input | Boundary |
| --- | --- |
| Host paths in `config.yml` | Must be relative to their documented data or proxy root; normalization may not escape that root. |
| Blueprint volume/copy sources | Resolve below the managed content root, use real-path containment, may not point into managed instances, and reject symbolic-link traversal. |
| Blueprint volume/copy targets | Resolve below the staging instance, may not target the instance root or internal layer directory, and may not overlap another distinct target. |
| Software cache, JAR, Java, and override paths | Version/JAR fragments are relative and confined to the selected software or data root. Java arguments are an argv list, not shell text. |
| YAML, properties, and text patch targets | Resolve below the instance, reject symbolic-link ancestors and non-regular targets, and publish through sibling temporary files. |
| Metadata and reconciliation paths | Require a valid instance ID, a directory below the configured instances root, and regular ownership metadata without symlink following. |
| Overlay/Btrfs/snapshot-hook manifests | Every stored source, target, layer, mount, and helper path is revalidated before resume, unmount, replacement, or deletion. |

Lexical normalization is not proof of filesystem identity. Operations involving
existing paths use `toRealPath`, `NOFOLLOW_LINKS`, explicit symbolic-link
checks, or a combination appropriate to the operation. Recursive deletion must
never follow links and must not begin until mount ownership has been validated
and mounted descendants have been safely unmounted.

Managed server launch never passes through a command shell. Snapshot helpers
are explicit-only, must be executable below the SLS-LITE data directory, use a
versioned argument protocol, and have bounded time and output. No blueprint may
inject shell syntax, a host mount, or shared writable storage.

## Compatibility Adaptations

Compatibility is intentional and bounded:

- Modern SLS structural fields are accepted only when SLS-LITE implements
  their local intent. Unknown structural keys fail with their YAML path;
  unknown annotation trees are preserved because annotations are extension
  data.
- vSLS command names and permissions remain pinned by
  `VSLSCommandContract`. Unsupported distributed operations return explicit
  compatibility responses rather than silently succeeding.
- `lobby.emergency` remains a deprecated read alias for `lobby.limbo`; defining
  both is rejected. Generated configuration uses only `lobby.limbo`.
- Schema-1 and schema-2 instance metadata can be adopted into the current
  schema only when the current definition is still persistent and compatible.
  Migration never invents ownership for an unknown directory.
- Modern software invocation is adapted to a shell-free argv model. Unsupported
  configuration targets and shell syntax are rejected instead of approximated.
- Protocol support is derived from the checksum-pinned NanoLimbo runtime.
  ViaVersion synchronization is optional integration, not proof that an
  untested client path is supported.
- Automatic COW selection chooses only a strategy that passed its capability
  and isolation probe. Snapshot helpers are never auto-selected, and safe
  portable copy remains the universal fallback.

Compatibility code may be removed only after its migration boundary is
documented and tests prove that no supported fixture, persistent schema,
operator key, command response, or generated path still depends on it.

## Required Review Evidence

Lifecycle, storage, or concurrency changes should include focused coverage for
the affected success path and all ownership-transfer points. Depending on the
change, this includes:

- stop during installation, preparation, or readiness;
- duplicate start/stop/restart/reset requests;
- process start failure, timeout, crash, and proxy shutdown;
- memory, process-slot, and port exhaustion;
- queue timeout, dequeue, failed transfer, disconnect, and orphan cleanup;
- persistent definition drift, interrupted reset, and restart reconciliation;
- path traversal, symlink, overlap, and ambiguous ownership;
- lobby retry exhaustion, stable-window reset, and superseded callbacks.

The full Maven suite and the local Pterodactyl/Velocity smoke test remain the
acceptance baseline after production lifecycle changes.
