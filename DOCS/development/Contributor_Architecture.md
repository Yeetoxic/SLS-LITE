# Contributor Architecture Guide

[Documentation home](../README.md)

This guide answers the practical question: **which files must change together?**
It supplements [Architecture](Architecture.md), which explains ownership, and
[Internal Invariants](Internal_Invariants.md), which defines behavior that a
refactor must preserve.

SLS-LITE exposes only `net.slimelabs.slslite.api` as its versioned Java
extension contract. Treat every other class below as an internal modification
point, not as a compatibility promise to other plugins. Changes to the public
package require contract tests and [Java API](../extensions/README.md) updates.

## Change Workflow

1. Find the owning package and the public orchestration boundary.
2. Read the focused tests before changing the implementation.
3. Change the model, parser or adapter, implementation, and presentation as one
   contract where the table below requires it.
4. Update bundled defaults and public documentation when operator-visible
   behavior changes.
5. Run the focused tests while iterating, then `mvn verify`.
6. Use the relevant Velocity/Pterodactyl or real-kernel fixture when the change
   crosses a process, mount, protocol, or player-routing boundary.

Run `mvn spotless:apply` before final verification. `mvn verify` checks the
pinned formatter and the high-priority SpotBugs gate in addition to tests and
packaging.

Do not move stateful code merely to make packages look symmetrical. Lifecycle,
matchmaking, provider recovery, and storage transactions contain synchronized
or rollback-sensitive state that must move with their tests.

## Java Extension API

Start at:

- `api/SLSLiteApi` for the versioned operation surface;
- the immutable records and enums directly under `api/` for public values;
- `api/event/` for lifecycle notifications and subscriptions;
- `api/internal/DefaultSLSLiteApi` for the adapter over internal services;
- `SLSLite` for provider discovery, activation, and shutdown wiring.

Never expose internal coordinators, mutable repositories, process handles,
filesystem paths, or Velocity result objects through the public package. Additive
features require a capability flag, immutable public values, sanitized failures,
and bounded asynchronous behavior. Breaking signatures or record shapes require
a new API major version.

Pair changes with `PublicApiContractTest`, the applicable adapter/event test,
and an extension compiled only against the API classifier. Update
[Java API](../extensions/README.md) and inspect the packaged classifier before handoff.

## Commands

Start at:

- `command/SLSCommand.java` for dispatch and the small shared surface;
- `command/handler/` for execution and completion of a command family;
- `command/VSLSCommandContract.java` for the reviewed vSLS command shape;
- `CommandPermissions`, `CommandAuthorizer`, and `CommandMessages` for access
  and presentation;
- `CommandInstanceAccess` and `InstanceTargetResolver` for instance lookup,
  selectors, player membership, and the `this` alias.

A command change must keep dispatch, permission, sender restrictions, usage,
output, and tab completion synchronized. Do not expose a command in suggestions
when that sender cannot execute it.

Pair changes with `SLSCommandSurfaceTest`, the applicable
`command/handler/*Test`, `CommandPermissionsTest`, `CommandAuthorizerTest`, and
`CommandMessagesTest`. Forced termination also requires
`SLSCommandForcedStopTest`. Update [Commands](../operations/Commands.md) and, when matching or
intentionally differing from vSLS, [SLS Command Compatibility](../compatibility/SLS_Commands.md).

## Blueprints

Start at:

- `blueprint/Blueprint.java` and related immutable values for launch intent;
- `BlueprintParser` for field validation and normalization;
- `BlueprintRepository` for recursive discovery, duplicate handling, bundled
  template installation, and catalog publication;
- `SLSLiteBlueprintAnnotations`, `VSLSBlueprintAnnotations`, and the focused
  policy records for annotation decoding and lifecycle behavior;
- `instance/model/InstanceDefinitionIdentity` for persistent-instance
  compatibility.

Structural keys are validated strictly. Unknown annotations remain preserved
metadata unless the compatibility policy explicitly gives them behavior.
Fields that change persistent launch semantics must participate in definition
identity so an incompatible instance is not silently reused.

Pair changes with `BlueprintParserTest`, `BlueprintRepositoryTest`,
`InstanceDefinitionIdentityTest`, and the applicable corpus compatibility
integration test. Update [Blueprints](../blueprints/Schema.md),
[Blueprint Volumes](../blueprints/Volumes.md), the bundled
`defaults/blueprints/template.yml.example`, and the compatibility matrix when needed.

## Software Profiles and Installers

Start at:

- `software/SoftwareProfile` and `SoftwareProfileRepository` for execution
  policy and profile parsing;
- `ModernSLSSoftwareAdapter` for accepted modern-SLS software definitions;
- `install/SoftwareInstallationProvider` for a source-specific provider;
- `PaperInstallationProvider` or `VanillaInstallationProvider` for existing
  source behavior;
- `SoftwareInstallationService` for shared installation state, cache reuse,
  retry, and cancellation;
- `process/JavaJarProcessSpecFactory` for the shell-free Java launch boundary.

Keep exact-version selection, integrity checks, staged publication, EULA
handling, cache reuse, failure retry, and cancellation explicit. A cancelled
waiter must not accidentally cancel a shared installation still needed by
another instance.

Pair changes with the applicable provider test,
`SoftwareInstallationServiceTest`, `SoftwareProfileRepositoryTest`, and
`JavaJarProcessSpecFactoryTest`. Update [Software Installation](../setup/Software_Installation.md)
and the applicable file under `defaults/software/`.

## Instance Lifecycle

Start at:

- `instance/ServerController` for the public orchestration boundary;
- `InstanceManager` for the active registry, admission reservations,
  asynchronous start/stop coordination, and backend registration;
- `ManagedInstance` for the intentional read/control facade;
- `instance/lifecycle/InstanceLifecycle` for legal state transitions;
- `process/ProcessSupervisor` for child execution and termination;
- `instance/metadata` and `instance/reconcile` for persistence and startup
  recovery;
- `InstanceLaunchConfigurator` and `SoftwareBaseDirectoryResolver` for launch
  preparation delegated by the manager.

Preserve admission ordering, cancellation checkpoints, terminal state,
resource/port release, backend unregister, and prepared-storage cleanup.
Callbacks may race with stop or shutdown; do not replace generation or
ownership checks with timing assumptions.

Pair changes with `InstanceManagerTest`,
`InstanceManagerInstallationCancellationTest`, `InstanceLifecycleTest`,
`ProcessSupervisorTest`, the metadata/reconciliation tests, and focused
configuration tests. Consult [Internal Invariants](Internal_Invariants.md) and
update [Operations](../operations/README.md) for operator-visible behavior.

## Storage and COW Strategies

Start at:

- `instance/storage/InstanceDirectoryPreparer` for the public preparation
  facade;
- `BlueprintContentResolver` for confined resolution of untrusted paths;
- `DirectoryCopyEngine` and `BoundedCopyBatch` for portable copying;
- `VolumeApplicator` for strategy-specific materialization;
- `PreparedStorageLifecycle` for resume, suspend, unmount, and cleanup;
- `PersistentInstanceTransaction` for reset swap, rollback, and crash recovery;
- the applicable layer manager or copy operation for OverlayFS, FUSE,
  reflink, Btrfs, or snapshot-hook behavior;
- `host/StorageStrategySelector` and capability probes for `auto` selection.

Storage sources must remain immutable. Preserve path containment, symlink
rejection, overlap checks, cancellation, mount ownership, rollback, and
idempotent cleanup. Capability detection is read-only; it must not leave probe
mounts or select a strategy that cannot complete its lifecycle.

Pair changes with the exact manager/preparer test, the resolver/copy/lifecycle
transaction tests, and `StorageStrategySelectorTest`. Kernel-dependent claims
require the matching `*RealKernelHarness`; portable behavior must continue to
pass on hosts without mount privileges. Update [Blueprint Volumes](../blueprints/Volumes.md),
[Configuration](../setup/Configuration.md), [Data Layout](../setup/Data_Layout.md), and
[Operations](../operations/README.md) as applicable.

## Lobbies and Player Routing

Start at:

- `lobby/LobbyProvider` for the primary-lobby abstraction;
- `LocalLobbyProvider` for Velocity-native, managed, or external primary policy;
- `SLSLimboProvider` for the isolated fallback process;
- `FallbackLobbyProvider` for fallback routing and evacuation;
- `LobbyRecoveryPolicy` for bounded recovery behavior;
- `velocity/LocalJoinService` for the synchronized matchmaking queue;
- `BlueprintJoinActionService`, `VelocityBackendRegistry`, and
  `SLSLimboHandoffService` for routing boundaries;
- `instance/lifecycle/IdleInstanceReaper` for idle cleanup policy.

Keep each provider's lifecycle ownership separate. Preserve generation guards,
bounded retries, resource release, queue cancellation, queue-owned instance
cleanup, and protection against reconnect loops.

Pair changes with the applicable lobby provider/recovery tests,
`LocalJoinServiceTest`, `BlueprintJoinActionServiceTest`,
`VelocityBackendRegistryTest`, and the protocol test when the wire path changes.
Update [SLS-Limbo](../networking/README.md), [Operations](../operations/README.md), and
[Protocol Compatibility](../networking/Protocol_Compatibility.md).

## Messages, Logs, and Timing

Start at:

- `command/CommandMessages` and the owning handler for command output;
- `log/ConsoleBanner` for the startup summary;
- `velocity/TransferActionBar` for transfer UI;
- `velocity/JoinTimingReporter` and
  `instance/diagnostics/InstanceTimingReporter` for timing presentation;
- `instance/diagnostics/InstanceOutput`, `InstanceLogBuffer`,
  `TemporaryInstanceLog`, and `FailedStartDiagnostics` for bounded instance
  diagnostics.

Keep player-facing text as Adventure components. Keep console summaries
actionable and bounded; detailed per-instance output belongs behind the
configured logging controls and bounded diagnostic stores. Avoid leaking
credentials, forwarding secrets, or full unbounded child output.

Pair changes with `CommandMessagesTest`, `ConsoleBannerTest`,
`TransferActionBarTest`, `JoinTimingReporterTest`, and the applicable
`instance/diagnostics/*Test`. Update [Commands](../operations/Commands.md),
[Configuration](../setup/Configuration.md), or [Operations](../operations/README.md) for visible
changes.

## Host Configuration and Bundled Defaults

Start at `config/SLSConfig`, its focused immutable configuration values,
`SLSConfigRepository`, `YamlValues`, and `ConfigurationValidator`. Reload
behavior belongs in `DefinitionReloader`; composition and service wiring belong
in `SLSLite`.

Keep field paths, defaults, generated comments, validation diagnostics, restart
requirements, and the bundled `defaults/host/config.yml` synchronized. A new
value needs valid, missing/default, malformed, and boundary coverage in
`SLSConfigRepositoryTest` or `ConfigurationValidatorTest`.

## Test Selection

Use the narrowest test during development:

```powershell
mvn "-Dtest=BlueprintParserTest" test
mvn "-Dtest=InstanceManagerTest,InstanceLifecycleTest" test
```

Before handoff, run:

```powershell
mvn verify
```

The Maven suite is the deterministic baseline, not proof of kernel, process,
or player behavior. Use:

- the real-kernel storage harnesses for mount/snapshot lifecycle changes;
- the synthetic Velocity fixture for narrow proxy integration;
- the local Pterodactyl/Velocity historical-world fixture for lifecycle,
  commands, routing, and operator workflow;
- the protocol clients for changes that affect SLS-Limbo or protocol routing.

Follow [Testing](Testing.md) for exact commands and verification requirements.
Fixtures under `src/test/resources/fixtures/` are test input; production
defaults under `src/main/resources/defaults/` are operator-facing artifacts.

## Before Declaring a Change Complete

- The owning implementation and every coupled contract were updated.
- Focused success, rejection, cancellation, and cleanup cases pass as relevant.
- `mvn verify` passes.
- Spotless is clean and no high-priority SpotBugs findings remain.
- The required external fixture was exercised, or the missing environment is
  recorded as a blocker rather than converted into a compatibility claim.
- Bundled examples, public docs, compatibility records, and `todo.md` reflect
  implemented behavior.
- Generated data, credentials, logs, caches, and test allocations remain
  uncommitted.
