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
| `instance` | Instance identity, files, metadata, lifecycle, matchmaking support, reconciliation, and logs. |
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

## Core Models

`Blueprint` is immutable launch intent: identity, registry, software/version,
limits, persistence, properties, annotations, and volumes.

`SoftwareProfile` is immutable execution policy: source, configurator, cache
path, Java executables, argument lists, readiness pattern, and stop behavior.

`ManagedInstance` owns one composite ID, blueprint snapshot, directory,
loopback port, resource reservation, lifecycle, process reference, readiness
future, and bounded logs.

`DefinitionCatalog` installs validated blueprint and software snapshots
together so requests do not observe a half-reloaded pair.

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

The current `cow` implementation is a transactional portable copy:

1. create a sibling temporary instance directory;
2. copy the exact software base;
3. copy validated volume sources into non-overlapping targets;
4. apply forwarding and server properties atomically;
5. write ownership metadata;
6. publish the complete directory;
7. remove incomplete staging on failure.

Persistent reset retains a rollback sibling until the replacement commits.
There is no active OverlayFS, reflink, hard-link, or Docker mount path.

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
