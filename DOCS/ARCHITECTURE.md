# Architecture

SLS-LITE runs as one Velocity plugin and supervises local child Java processes.
It has no daemon, remote controller, database, container runtime, or full-SLS
dependency.

## Runtime Flow

```text
Velocity events and /sls commands
              |
              v
      matchmaking/lobby policy
              |
              v
       ServerController API
              |
              v
        InstanceManager
   +----------+-----------+-------------+
   |                      |             |
software install     file prepare   resource admission
   |                      |             |
   +----------+-----------+-------------+
              |
              v
      ProcessSupervisor
              |
              v
   local Java child on 127.0.0.1
              |
              v
 readiness -> protocol sync -> Velocity registration
```

An instance is externally usable only after preparation, process readiness, and
backend protocol synchronization all succeed.

## Package Ownership

| Package | Responsibility |
| --- | --- |
| `blueprint` | Blueprint schema, recursive loading, lifecycle annotation policy. |
| `command` | `/sls` dispatch, permissions, vSLS presentation, selectors, and completions. |
| `config` | Host configuration, validation, immutable definition catalogs, reload. |
| `host` | Startup capability probes and diagnostics. |
| `install` | Provider-backed software acquisition and bounded installation state. |
| `instance` | Public local-server facade and orchestration. |
| `instance.configuration` | Instance-confined forwarding, properties, YAML, text-file edits, and launch configuration assembly. |
| `instance.diagnostics` | Bounded output, temporary logs, failed-start reports, and process-resource sampling. |
| `instance.lifecycle` | State transitions, phase timing, idle admission, and idle-instance reaping. |
| `instance.model` | Immutable instance identity, definition fingerprint, metadata, and state values. |
| `instance.metadata` | Confined, versioned instance-metadata persistence. |
| `instance.reconcile` | Startup recovery, persistent normalization, and ownership-safe stale cleanup. |
| `instance.storage` | Transactional preparation plus portable copy, reflink, Btrfs, OverlayFS, FUSE, and snapshot-hook lifecycles. |
| `lobby` | Primary-lobby abstraction, SLS-Limbo runtime, fallback routing, and recovery. |
| `network` | Synchronized loopback port reservation. |
| `process` | Shell-free process specifications, supervision, input, output, and termination. |
| `resource` | Managed-memory admission accounting. |
| `security` | Built-in administrators and short-lived claim codes. |
| `software` | Software-profile schema, Java selection, launch/configurator/source policy. |
| `velocity` | Dynamic backend registration, player transfer UI, and ViaVersion synchronization. |

`SLSLite` is the composition root. It creates services in dependency order,
registers Velocity listeners and commands, starts lobby providers, and closes
children during proxy shutdown.

### Target Package Map

Stage 3.4 is moving the current broad packages toward the following ownership
boundaries. These are dependency boundaries, not only directory names.
The storage, model, metadata, reconciliation, configuration, diagnostics, and
lifecycle boundaries are complete:

| Target package | Owns | May depend on |
| --- | --- | --- |
| `instance` | Public local-server façade and orchestration (`ServerController`, `InstanceManager`). | The focused instance packages below plus blueprint, install, network, process, resource, software, and Velocity ports. |
| `instance.model` | Instance identity, definition identity, metadata value objects, state, and externally readable snapshots. | Blueprint/config model types; no filesystem or process implementations. |
| `instance.metadata` | Versioned metadata serialization, confined atomic persistence, persistent discovery, resume validation, and legacy identity migration. | `instance.model`, the read-only instance facade, and filesystem/process identity primitives. |
| `instance.lifecycle` | State transitions, admission coordination, idle policy, and phase timing. | `instance.model` and narrow service interfaces. |
| `instance.storage` | Transactional preparation and all copy, reflink, Btrfs, OverlayFS, FUSE, and snapshot-hook lifecycles. | Blueprint volume/config models and filesystem/process primitives; never Velocity or command code. |
| `instance.reconcile` | Startup recovery, persistent reuse/reset decisions, and ownership-safe stale cleanup. | `instance.model`, `instance.storage`, and metadata persistence. |
| `instance.configuration` | Instance-confined properties, YAML, text, forwarding edits, and launch configuration assembly. | Configuration models, process-spec construction, and filesystem primitives. |
| `instance.diagnostics` | Bounded logs, failed-start records, process resource readings, and lifecycle summaries. | `instance.model` and process read-only views. |
| `host.storage` | Read-only per-path storage capability probes and automatic strategy selection. | Storage configuration plus probe process/filesystem primitives; no instance mutation. |
| `command.handler` | One focused handler per command family, sharing authorization, target resolution, messages, and the pinned command contract. | Public service interfaces; never concrete storage or process internals. |

Dependency flow is inward through models and interfaces: command/Velocity
adapters call orchestration; orchestration coordinates focused services;
storage and process implementations do not call presentation code. Package
moves must preserve operator paths, YAML keys, command behavior, serialized
metadata, and the public `ServerController` boundary.

The initial inventory found 35 classes in `instance`, with storage,
configuration editing, lifecycle state, reconciliation, and diagnostics mixed
beside orchestration. The root package is now reduced to five production
classes: the public controller/orchestration facade, its managed-instance
facade, and the two shared operation exceptions. `SLSCommand` now dispatches
to focused command-family handlers. The remaining large pressure points are
`InstanceDirectoryPreparer` and `InstanceManager`; they should be decomposed
only along tested ownership boundaries, not during unrelated behavioral
rewrites.

`InstanceDirectoryPreparer` is the public preparation facade and composes the
selected storage services. `BlueprintContentResolver` separately owns normalization,
containment, overlap detection, symlink rejection, and resolution of untrusted
blueprint volume/copy declarations. `DirectoryCopyEngine` owns safe directory
traversal, bounded parallel copy execution, retry/backoff, cancellation
polling, and merge/replace copy semantics. `VolumeApplicator` owns selected
portable, reflink-backed portable, Btrfs, OverlayFS/FUSE, and snapshot-hook
volume materialization. `PreparedStorageLifecycle` owns strategy-aware
resume/suspend handling, mount-safety validation, and cleanup of prepared
storage. `PersistentInstanceTransaction` owns persistent reset swaps,
rollback, committed-backup cleanup, and crash-recovery sequencing.
`InstanceDirectoryPreparer` remains the public facade that validates requests
and composes these storage services.

`InstanceManager` now delegates metadata persistence and persistent-instance
compatibility to `InstanceMetadataService`, lifecycle timing presentation to
`InstanceTimingReporter`, storage transactions to `InstanceDirectoryPreparer`,
software override/install-on-demand resolution to
`SoftwareBaseDirectoryResolver`, instance-confined launch configuration to
`InstanceLaunchConfigurator`, and child execution to `ProcessSupervisor`. It
still owns the synchronized active-instance registry, admission reservations,
asynchronous start/stop coordination, and Velocity registration. Those
stateful responsibilities must move only with tests that exercise cancellation
and callback races; package organization alone is not a reason to split the
state machine.

`SLSCommand` is being reduced to dispatch and shared presentation. The
`command.handler` package owns complete command families, including their
execution and completion behavior; `AdminCommandHandler` and
`InstallationCommandHandler` own their respective families, while
`InspectionCommandHandler` is a small stable facade over focused catalog,
instance/log, and host-diagnostics components. `PlayerRoutingCommandHandler`
owns join, dequeue, find, connection reporting, selectors, and their
completions. `InstanceLifecycleCommandHandler` owns start, stop, restart, reset,
protected-lobby cycling, and lifecycle completions. `CommandInstanceAccess`
centralizes active/persistent lookup, player membership, and the `this` alias
shared by handlers. `SLSCommand` retains dispatch and the small cross-family
surface. A family must move with its permission, sender, usage, output, and
completion tests so dispatch cannot drift from suggestions.

`LocalJoinService` retains the synchronized queue, matchmaking selection,
draining, cancellation, and queue-owned instance cleanup as one race-sensitive
state machine. `TransferActionBar` owns transfer UI, while
`JoinTimingReporter` owns first-player and connection timing presentation.

The lobby providers keep separate lifecycle ownership:
`LocalLobbyProvider` owns the managed or external primary,
`SLSLimboProvider` owns the isolated fallback process and its reserved
resources, and `FallbackLobbyProvider` coordinates routing and evacuation
between them. `LobbyRecoveryPolicy` is the only shared recovery abstraction;
it defines the bounded retry ceiling, exponential backoff, and stable-runtime
reset window without combining the providers' distinct cleanup, registration,
or generation-guarded state machines.

## Core Models

`Blueprint` is immutable launch intent: identity, registry, software/version,
limits, persistence, properties, annotations, and volumes.

`BlueprintParser` validates and normalizes one YAML document into immutable
launch intent. `BlueprintRepository` owns recursive discovery, bundled-template
installation, duplicate detection, snapshots, and catalog publication.

`SoftwareProfile` is immutable execution policy: source, configurator, cache
path, Java executables, argument lists, readiness pattern, and stop behavior.

`SLSConfigRepository` owns installation and atomic publication of the host
configuration snapshot. Its strict YAML validation, defaults, compatibility
alias handling, and confined-path resolution remain together until the planned
operator-YAML normalization defines stable section boundaries; splitting that
single parse transaction earlier would duplicate key and path context.

`SoftwareInstallationService` keeps the active-installation registry,
consumer cancellation, staging transaction, integrity metadata, and bounded
history together because they share one publication boundary. Individual
download and version-resolution behavior belongs to the provider classes.

`ManagedInstance` owns one composite ID, blueprint snapshot, directory,
loopback port, resource reservation, lifecycle, process reference, readiness
future, and bounded logs.

`DefinitionCatalog` installs validated blueprint and software snapshots
together so requests do not observe a half-reloaded pair.

`HostStorageCapabilityChecker` coordinates the bounded storage report,
cache use, severity for explicit versus automatic requests, and final strategy
selection. Kernel/filesystem operations remain in the focused Btrfs, OverlayFS,
FUSE, snapshot-hook, and probe-cache collaborators; the coordinator is kept
intact so capability evidence and automatic-selection policy remain readable
together.

`SLSCommand` retains root dispatch, help, host/reload/version presentation, and
shared failure formatting. Command-family behavior belongs to
`command.handler`. `InstanceDirectoryPreparer` likewise remains the storage
transaction facade; content resolution, copying, volume materialization,
prepared lifecycle, and persistent replacement are already delegated to
focused collaborators.

## Important Invariants

- No command shell is used for managed server launch.
- Managed backends bind to loopback.
- Configured and generated paths stay within controlled roots.
- Resource admission happens before child launch and is released once.
- Velocity registration happens only after readiness and protocol sync.
- Queue cleanup covers every terminal path.
- Ephemeral deletion requires valid SLS-LITE ownership metadata.
- Persistent definition drift blocks silent directory reuse.
- Stop during installation cancels only that consumer, not a shared download.
- Lobby recovery and SLS-Limbo recovery have independent bounded budgets.
- Player routing never selects an arbitrary game server as a fallback lobby.

## Storage Preparation

The `cow` implementation uses transactional reflink cloning, eligible Btrfs
subvolume snapshots, managed kernel OverlayFS, or managed fuse-overlayfs when
the selected storage passes its isolation probe; an explicitly configured
snapshot helper covers provider-managed storage. Otherwise SLS-LITE uses
transactional portable copy:

1. create a sibling temporary instance directory;
2. copy the exact software base;
3. copy validated volume sources or mount them as immutable OverlayFS lowers
   into non-overlapping targets;
4. apply forwarding and server properties atomically;
5. write ownership metadata;
6. publish the complete directory;
7. remove incomplete staging on failure.

Persistent reset retains a rollback sibling until the replacement commits.
An auto-selected reflink may fall back to copying for an individual
cross-filesystem source; explicitly required reflink fails the transaction.
Overlay-backed instances persist private upper/work layers and a manifest
through stop/restart, and lifecycle cleanup verifies mount ownership before
unmounting or traversing an instance.
On non-Windows hosts, the portable file copier samples only large files for zero
runs and preserves qualifying runs as sparse extents; Windows and all
small/ordinary files retain the platform native copy path.
Btrfs snapshots apply to `cow` volume sources that are subvolumes without
nested subvolumes. A durable instance manifest drives replacement, stale
ephemeral reconciliation, and deepest-first deletion. Under `auto`, ineligible
sources use portable copy; explicit `btrfs` rejects them transactionally.
Kernel and FUSE overlays share the durable layer manifest and transactional
lifecycle. The FUSE adapter also verifies the exact daemon arguments before
unmounting, including after proxy restart; `/dev/fuse` and an installed binary
are only prerequisites, and the contained mount probe makes the final
eligibility decision.
The explicit snapshot-helper backend invokes only an executable confined below
the SLS-LITE data directory. Its versioned argv protocol has bounded output and
timeouts; durable source/target manifests drive provider suspend, resume,
replacement, deletion, and stale-ephemeral reconciliation. It is never
auto-selected.
Full directory copies use a bounded pool of at most four workers with no more
than twice that many tasks in flight. The batch drains all started work before
transactional cleanup can remove a destination. Ordered first-wins merges stay
sequential so precedence never depends on task scheduling.
There is no active hard-link or Docker mount path.

## Concurrency

Lifecycle transitions are explicit and guarded. Ports, resource reservations,
installation operations, queues, and registries have single-owner or
thread-safe services. Asynchronous work completes through futures rather than
high-frequency polling.

When changing lifecycle code, tests must cover concurrent stop/start,
cancellation, failure cleanup, duplicate requests, and shutdown. A successful
future alone is not proof that reservations and filesystem state were released.

## Extension Points

Typical changes belong in:

- commands: `SLSCommand`, `CommandMessages`, `VSLSCommandContract`, and command
  surface tests;
- blueprint fields: `Blueprint`, `BlueprintRepository`, identity hashing, and
  parser tests;
- software sources: `SoftwareInstallationProvider` and
  `SoftwareInstallationService`;
- lifecycle: `InstanceManager`, `ManagedInstance`, metadata/reconciliation, and
  focused lifecycle tests;
- lobby routing: `LobbyProvider`, `FallbackLobbyProvider`,
  `LocalLobbyProvider`, or SLS-Limbo services;
- Velocity integration: `VelocityBackendRegistry`, `LocalJoinService`, and
  protocol synchronization.

There is not yet a public versioned Java API. Other plugins must not depend on
internal classes as a stable contract.

## Dependency Boundary

- Velocity API and ViaVersion API are provided by the proxy at runtime.
- SnakeYAML is relocated and shaded into the plugin.
- The pinned NanoLimbo JAR runs as a verified child process.
- Paper, vanilla, custom server software, and Java runtimes are external
  operator/provider artifacts and are not bundled.

License and source provenance are recorded under `THIRD_PARTY/` and packaged in
the shaded artifact where required.
