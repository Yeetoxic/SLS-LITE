# SLS-LITE Roadmap

## Product Goal

SLS-LITE is a Velocity plugin for running a small, dynamic Minecraft network
inside a single game-server hosting allocation. It is intended for users who
cannot operate a separate controller, daemon, VPS, or container platform.

The primary deployment must work with only:

- One Velocity process started by the hosting panel.
- Child Java processes launched by SLS-LITE.
- Writable storage inside the assigned server directory.
- Backend servers bound to loopback ports.
- The memory and CPU assigned to the original hosting allocation.

SLS-LITE must also continue to support conventional networks with separately
hosted backend and lobby servers.

## Hosting Feasibility

- [x] Document the minimum host capabilities:
  - Permission to launch child Java processes.
  - Permission to bind additional loopback ports.
  - Enough shared memory for Velocity and all active backends.
  - Writable directories for templates, instances, worlds, and logs.
- [ ] Add a startup capability check for Java, process creation, loopback ports,
      filesystem permissions, and available memory.
  - [x] Probe writable instance storage, loopback binding, and every configured
        child Java runtime before enabling managed features.
  - [x] Report the declared managed-memory budget without presenting it as
        measured provider memory.
  - [ ] Add provider-specific available-memory detection only where it is
        reliable and cannot escape panel or container limits.
- [x] Fail with clear diagnostics when the hosting environment blocks a required
      capability.
- [ ] Do not attempt to bypass hosting-panel limits, container restrictions, or
      provider terms.
- [ ] Test on common shared-host panel environments and document known-compatible
      configurations.

## Phase 1: Runnable Baseline

- [x] Resolve the root AGPL-3.0 and `LEGAL/LICENSE.txt` MIT license conflict.
- [x] Add `.gitignore` and remove generated `target/` files from version control.
- [x] Update the build to Java 21 bytecode and the current Velocity API target.
- [x] Remove obsolete registries and incomplete command references.
- [x] Add and relocate all runtime dependencies in the release JAR.
- [x] Initialize the blueprint repository from the Velocity initialization event.
- [x] Register the initial `/sls` command from the Velocity initialization event.
- [x] Initialize host configuration and software profile services from the
      Velocity initialization event.
- [x] Initialize process lifecycle services from the Velocity initialization
      event.
- [x] Shut down all managed child processes during proxy shutdown.
- [x] Replace static mutable globals with explicit services where practical.
- [x] Establish a passing `mvn test` and `mvn package` baseline.

## Phase 2: Configuration Model

- [x] Replace the legacy registry format with a small blueprint model.
- [x] Add software profiles containing:
  - Java executable or runtime selection.
  - Server JAR or base server directory.
  - Invocation arguments.
  - Readiness signal.
  - Graceful stop command.
- [ ] Add blueprints containing:
  - Stable ID, display name, and type.
  - Software profile and Minecraft version.
  - Base world or template directory.
  - Memory allocation.
  - Maximum players and maximum active instances.
  - Persistent or ephemeral state.
  - Idle shutdown behavior.
  - [x] Implement memory, player, and active-instance limits.
  - [x] Implement persistent/ephemeral and idle-shutdown policy fields.
  - [ ] Add a blueprint-level base world or template directory.
- [x] Validate all currently supported configuration before starting an instance.
- [x] Use platform-independent `Path` handling.
- [ ] Reload configuration without corrupting active instance state.
- [ ] Preserve a migration path from the existing registry YAML files.

## Phase 3: Process Supervisor

- [x] Introduce explicit instance states:
      `CREATED`, `PREPARING`, `STARTING`, `READY`, `STOPPING`, `STOPPED`,
      and `FAILED`.
- [x] Use a bounded executor for process and log supervision.
- [x] Give every instance a unique ID, directory, Velocity server name, and port.
- [ ] Implement a synchronized port allocator with startup retry handling.
- [x] Use one output reader per child process.
- [x] Add configurable readiness and startup timeout handling.
- [x] Send the configured stop command before forcefully terminating a process.
- [x] Add stop timeouts, crash detection, exit-code reporting, and cleanup.
- [x] Prevent duplicate starts and conflicting lifecycle actions.
- [x] Track total allocated memory and reject starts that exceed the configured
      single-host budget.
- [x] Recover or clean up incomplete instance state after a proxy restart.

## Phase 4: Instance Files

- [x] Never run multiple instances from the same writable server directory.
- [x] Create a separate writable directory for every instance.
- [x] Implement portable directory-copy isolation as the default mode.
- [x] Support ephemeral instances that are deleted after shutdown.
- [x] Support persistent instances that can be stopped and restarted.
- [x] Add an explicit reset operation that restores an instance from its template.
- [ ] Investigate optional copy-on-write optimizations:
  - Linux OverlayFS when mounting is permitted.
  - Reflinks when supported by the host filesystem.
  - Hard links only for files that the server will never modify.
- [ ] Automatically fall back to portable copying when an optimization is
      unavailable.
- [ ] Apply required `server.properties` values to the instance copy, including
      loopback address, allocated port, forwarding settings, and view distance.
  - [x] Apply loopback address, allocated port, offline backend mode, and
        blueprint player capacity.
  - [x] Apply modern Velocity forwarding to Paper and disable legacy BungeeCord
        forwarding when configured.
  - [ ] Add a configurable view-distance policy.

## Phase 5: Velocity Integration

- [x] Register and unregister managed backends with Velocity safely.
- [x] Queue players while an instance is preparing or starting.
- [x] Connect requested players only after the instance reaches `READY`.
- [x] Add queue timeout, cancellation, disconnect, and startup-failure handling.
- [x] Add `/sls list`, `/sls start`, `/sls stop`, `/sls reload`, and
      `/sls status`.
- [x] Add registry-aware `/sls join`.
- [x] Add `/sls dequeue` for self, named-player, `all`, and `local` queue
      cancellation.
- [x] Add `/sls logs` with the vSLS-compatible
      `<server> [page] [lines]` arguments and bounded local retention.
- [x] Require explicit permissions for administrative actions.
- [x] Add permissions for other-player join actions.
- [x] Support joining an existing instance or creating one from a blueprint.
- [x] Add automatic idle shutdown with a configurable delay.
- [ ] Add optional ViaVersion integration for backend protocol detection.
- [ ] Avoid requiring PacketEvents unless a retained feature needs packet-level
      control.

### Command and Permission Compatibility

- [x] Maintain a command compatibility table for the targeted vSLS release,
      covering syntax, permission, sender restrictions, tab completion,
      lifecycle behavior, and local support status.
- [x] Preserve `sls.command.admin` as the upstream-compatible umbrella permission
      for every administrative SLS-LITE command.
- [x] Keep implemented self-service operations public where vSLS does, including
      listing, version information, joining oneself, and finding a player.
- [x] Require administrative permission when an implemented operation targets
      `all`, `local`, or another player.
- [x] Require administrative permission for force operations that bypass
      blueprint capacity or lifecycle safeguards when those options are added.
- [x] Add optional granular SLS-LITE permission nodes only as additive aliases;
      users with `sls.command.admin` must retain access to all local
      administrative functionality.
- [x] Hide unauthorized subcommands and argument suggestions while still
      returning a clear permission error for directly entered commands.
- [x] Match vSLS player-only and console-capable behavior for the implemented
      join command, including
      requiring an explicit player target when a console sender cannot act on
      itself.
- [x] Resolve the vSLS `this` server selector consistently from a player's
      current Velocity backend for every implemented server-targeting command.
- [ ] Port locally meaningful vSLS commands and options, including `info`,
      `list`, `create`, `start`, `join`, `find`, `console`, `blueprint`, `debug`,
      `delete`, `logs`, `reload`, `stop`, `kill`, `dequeue`, `status`, `stats`,
      `reset`, `restart`, and `version`.
- [x] Match the pinned vSLS command presentation for implemented commands:
      prefix, usage grammar, list framing, status colors, player/server hover
      details, version metadata, and action-bar feedback.
- [x] Mirror the complete vSLS `v0.2.0` top-level tree, including `system` and
      `node`; keep SLS-LITE-only commands such as `registries` and `blueprints`
      as additive aliases rather than replacements for upstream commands.
- [ ] Match the remaining vSLS modifier behavior. Capacity-bypassing
      `/sls join player <player> --force` is implemented; daemon-backed
      `remote` remains unavailable. The implemented join paths support `all`,
      `local`, player names, and `player <player>`.
- [x] Add the initial versioned command-contract fixture for the pinned vSLS
      release, root command names, and public/admin help visibility.
- [ ] Extend the command-contract fixture across every argument branch,
      permission node, sender restriction, and completion case.
- [ ] Return `not available in local mode` with an explanation for distributed
      commands such as remote node administration instead of silently omitting
      or partially emulating them.
- [ ] Add command tests covering public, granular, administrator, console,
      player, other-player, force, invalid-usage, and tab-completion cases.
  - [x] Cover the shared `this` selector for console, external backends, and
        managed backends.

## Modern SLS Features

Port useful concepts from current SLS while keeping every feature local to the
Velocity host. SLS-LITE must not require Protocube, a daemon, Docker, an HTTP
controller, or a second machine.

### Compatibility Contract

SLS-LITE is an independent, single-host implementation of useful SLS behavior.
It does not run under full SLS. Compatibility means sharing terminology,
configuration, commands, lifecycle semantics, examples, and documentation where
the local implementation has equivalent behavior.

- [x] Use the vSLS human-readable composite instance ID format
      `<blueprint-id>.<six-character-id>` for commands, directories, logs, and
      Velocity registrations.
- [x] Keep SLS-LITE entirely operational inside Velocity and its locally managed
      child processes.
- [ ] Treat modern SLS as the behavioral and documentation reference when an
      equivalent feature exists in both projects.
- [ ] Implement every supported feature locally without forwarding work to
      Protocube, a daemon, S4J, Docker, or another SLS installation.
- [ ] Prefer direct support for compatible modern SLS configuration over a
      separate SLS-LITE-only schema or mandatory conversion step.
- [x] Use a local server-controller interface to separate commands, matchmaking,
      and lifecycle logic from Java process and filesystem management.
- [ ] Do not add a remote-controller implementation or make full SLS a runtime
      mode of SLS-LITE.
- [ ] Match upstream command names and argument behavior where the operation is
      available locally.
- [ ] Return a clear `not available in local mode` response for distributed-only
      operations instead of silently ignoring them.
- [ ] Match upstream lifecycle state names and event meanings where practical.
- [ ] Express SLS-LITE-specific behavior through namespaced annotations instead
      of changing the meaning of upstream fields.
- [ ] Define the local equivalent of each adopted distributed feature:
  - Daemon process creation becomes a locally supervised Java child process.
  - Node allocation becomes local memory, directory, and port admission.
  - Docker images become configured Java runtimes and prepared software
    directories.
  - Container limits become JVM limits, local accounting, and capability
    warnings.
  - Remote event streams become in-process lifecycle events.
  - Overlay volumes become portable copies or an optional supported local
    copy-on-write strategy.
- [ ] Label shared documentation and examples with one of:
  - `SLS and SLS-LITE`.
  - `Full SLS only`.
  - `SLS-LITE only`.
  - `Adapted for local mode`.
- [ ] Keep shared documentation text in one place where possible and use short
      product-specific sections only for operational differences.

### Upstream Alignment

- [x] Record the historical Velocity-only baseline at SLS commit
      `4f9b7ca7f6d857d43253076f1627ad4087f663ab` and separate its proven
      single-host behavior from implementation details that must be modernized.
- [ ] Treat modern SLS terminology and public configuration conventions as the
      preferred reference when the same concept exists in SLS-LITE.
- [x] Record the initial upstream SLS release and commit targeted by SLS-LITE
      release.
- [ ] Maintain a feature compatibility matrix with `supported`, `adapted`,
      `unsupported`, and `planned` states.
- [ ] Review each stable upstream SLS release for features that are useful in a
      single-host Velocity environment.
- [ ] Track announced or experimental upstream features separately and wait for
      their configuration and behavior to stabilize before adopting them.
- [ ] Document intentional differences when distributed SLS behavior cannot be
      reproduced safely on a shared host.
- [ ] Reuse upstream field names, status names, command names, and examples when
      their semantics remain equivalent.
- [ ] Add migration notes whenever SLS-LITE follows an upstream schema or
      behavior change.
- [ ] Keep upstream example blueprints and software definitions as compatibility
      fixtures where licensing permits.
- [ ] Add migration fixtures for the historical `minigames.yml`,
      `adventureMaps.yml`, and `archive.yml` formats.
- [ ] Decide whether historical `shutdown` and `config` forms remain as hidden
      or documented aliases without displacing modern vSLS `stop`, `reload`,
      and `blueprint` commands.
- [ ] Add an optional, validated compatibility listener for the historical
      `slimelabs:network` plugin message channel.
- [ ] Never introduce a runtime dependency on Protocube, the daemon, S4J, Docker,
      or another SLS installation.

### Blueprint Compatibility

- [ ] Publish a compatibility matrix showing which modern SLS blueprint fields
      SLS-LITE supports, translates, ignores, or rejects.
- [ ] Load supported modern SLS blueprints directly without requiring an import
      or conversion command.
- [ ] Support the useful subset of modern blueprint metadata:
  - `blueprint.id`, `blueprint.name`, and `blueprint.type`.
  - `server.software`, `server.version`, and resource limits.
  - Persistent or ephemeral `save` behavior.
  - State volumes, copied files, environment variables, and annotations.
- [ ] Add an optional conversion or validation command for users who want a
      standalone SLS-LITE-compatible copy with unsupported fields explained.
- [ ] Preserve unknown annotations during load and save so external tools can
      attach metadata without SLS-LITE deleting it.
- [x] Use stable blueprint IDs and short instance IDs similar to modern SLS.
- [ ] Keep the SLS-LITE schema smaller than the upstream schema and reject fields
      that imply unavailable isolation or host capabilities.

### Software Profiles and Installation

- [ ] Add version-to-Java mappings so a software profile can select the correct
      configured Java runtime for a Minecraft version.
- [ ] Cache prepared server software by software ID and version.
- [ ] Add provider-based installers for common software, beginning with Paper.
- [ ] Verify downloaded artifacts with metadata or checksums when available.
- [ ] Lock software installation so two instances cannot install the same version
      concurrently.
- [ ] Add installation state, timeout, progress, failure logs, and retry behavior.
- [ ] Support an optional warmup step that accepts the EULA, generates required
      base files, and stops before the template is used.
- [ ] Keep manually prepared server directories fully supported.
- [ ] Do not execute arbitrary shell installation scripts by default on shared
      hosts.

### Config Patches and State

- [ ] Port modern SLS-style startup config patches for Java properties files.
- [ ] Add structured patchers for YAML, JSON, and TOML only when a blueprint
      needs them.
- [ ] Support runtime placeholders such as instance ID, port, memory, world path,
      and player limit.
- [ ] Merge software defaults, blueprint patches, and per-start overrides in a
      documented order.
- [ ] Validate patch targets and prevent writes outside the instance directory.
- [ ] Support modern volume intentions using host-appropriate implementations:
  - `copy-on-write` maps to the best supported local isolation strategy.
  - `read-only` exposes immutable template data.
  - `read-write` is allowed only for explicitly shared persistent data.
- [ ] Add file-copy entries for plugins, configuration, resource packs, and other
      blueprint assets.
- [ ] Add optional environment variables without allowing blueprints to replace
      protected process or host variables.

### Events and Lifecycle

- [ ] Add an internal event bus for instance status, console, crash, deletion,
      player-count, and resource events.
- [ ] Expose status transitions to commands and other Velocity plugins.
- [ ] Add per-blueprint lifecycle annotations:
  - Do not stop when empty.
  - Empty-server stop delay.
  - Maximum concurrent instances.
  - Startup and stop timeout overrides.
- [ ] Add bounded crash restart policies with exponential backoff.
  - [x] Implement the managed-lobby policy with configurable attempts, bounded
        backoff, and retry-budget reset after a stable runtime.
- [ ] Add maintenance or drain mode that prevents new instance creation while
      allowing existing instances to finish.
- [x] Add startup reconciliation with durable ownership metadata so SLS-LITE
      removes confirmed dead ephemeral directories after an unclean shutdown
      while preserving persistent, live, malformed, and legacy directories.
- [x] Probe loopback ports during allocation so ports retained by surviving
      processes are skipped after restart.
- [ ] Reconcile plugin-owned Velocity registrations if supported plugin hot
      reload is added; registrations do not survive a normal proxy restart.
- [ ] Consider an optional local event stream or administration API only after the
      in-proxy API is stable and authenticated.

### Matchmaking and Join Actions

- [x] Treat `blueprint.type` as the user-defined registry namespace and
      `blueprint.id` as the server/blueprint within that registry.
- [x] Discover registries dynamically from loaded blueprint types; adding the
      first blueprint with a new type must make that registry available without
      a central registry declaration or plugin restart.
- [x] Support the SLS command shape `/sls join <registry> <server>` with
      registry-aware tab completion and validation that the selected server
      belongs to that registry.
- [ ] Add registry-aware variants of create, start, list, and administrative
      commands where modern vSLS exposes the same grouping.
- [ ] Port modern vSLS game types that group compatible blueprints for
      matchmaking.
- [ ] Add a pluggable blueprint selection strategy, starting with first-available
      and random selection.
- [ ] Prefer existing ready instances with capacity before creating another.
- [x] Enforce per-blueprint player and instance limits during allocation.
- [x] Cancel provisioning when its queue becomes empty when safe to do so.
- [ ] Support blueprint `on-join` console actions with safe placeholders such as
      player name and UUID.
- [x] Add a force-join permission that can bypass blueprint capacity for
      administrators.

### Operations and Observability

- [x] Add distinct startup and shutdown console banners with SLS-LITE artwork,
      credits, version, source, and AGPL-3.0 links.
- [x] Add server information views for blueprint, software version, state, port,
      players, uptime, and instance directory.
- [ ] Report process CPU time, current memory when measurable, configured memory,
      and disk usage without claiming container-level enforcement.
  - [x] Report process CPU time, uptime, and configured memory while explicitly
        labeling child memory, network, and disk measurements as unavailable.
  - [ ] Add portable or platform-specific measured child memory, network, and
        disk values where they can be obtained reliably.
- [ ] Add paginated recent logs and live console following.
  - [x] Add vSLS-compatible recent-log pagination backed by a fixed
        1,000-line per-instance ring buffer.
  - [x] Add configurable proxy-console mirroring and a bounded temporary
        per-instance log file that does not create unbounded archives.
  - [ ] Add an opt-in live console follow mode without blocking Velocity threads.
- [x] Add line-oriented console command execution through the supervised process
      input without competing with the process log reader.
- [ ] Add bounded output capture for `/sls console` so the invoking player sees
      the relevant child-console response without retaining unbounded logs.
- [ ] Add create-time overrides for memory, save mode, seed, view distance, and
      selected safe configuration values.
- [ ] Add per-instance reset, restart, delete, and force-kill operations.
  - [x] Add persistent restart with same-ID directory reuse and lobby evacuation.
  - [x] Add rollback-protected persistent reset from the current template.
  - [ ] Add persistent delete and explicit force-kill operations.
- [ ] Add software and blueprint reload commands with detailed validation errors.
- [ ] Add a public Java API so other Velocity plugins can:
  - Inspect blueprints and instances.
  - Request an instance and queue a player.
  - Subscribe to lifecycle events.
  - Stop or delete instances when authorized.
- [ ] Add optional metrics with collection disabled by default and no sensitive
      host, path, player, or blueprint data.

## Lobby Support

### Lobby Modes

- [x] Support `lobby.mode: external`.
  - Use a normal separately hosted lobby already registered with Velocity.
  - Never start, stop, copy, or otherwise manage that server.
  - Preserve this as the conventional and maximum-compatibility option.

- [ ] Support `lobby.mode: managed`.
  - [x] Start a Paper lobby as an SLS-LITE child process in the same hosting
    allocation as Velocity.
  - [x] Treat the active lobby as a reserved blueprint that normal stop commands
    cannot terminate.
  - [x] Start it during proxy initialization and wait for readiness before routing
    players.
  - [x] Keep it running independently of normal matchmaking cleanup.
  - [x] Restart it after a crash with bounded retry and backoff.
  - [x] Disconnect with a clear temporary-unavailability message while it is
        starting, recovering, or offline.
  - [x] Stop it gracefully during proxy shutdown.
  - [ ] Allow operators to disable automatic startup and manage it with commands.

- [ ] Investigate `lobby.mode: virtual` as an optional proxy-native lobby.
  - This is the only mode that would host the lobby experience directly in the
    Velocity process without a Paper child process.
  - Evaluate maintained virtual-server libraries such as LimboAPI or a compatible
    lightweight implementation; do not build the Minecraft protocol from scratch.
  - Verify compatibility with the selected Velocity version and supported client
    versions.
  - Review dependency licensing before integration.
  - Define the reduced feature set: spawn world, movement, chat, commands,
    scoreboard, menus, server selector, and transfer to managed backends.
  - Clearly document that Bukkit/Paper plugins and full server mechanics are not
    available in virtual mode.
  - Keep this feature isolated behind an interface so library or protocol changes
    do not affect the process supervisor.

### Lobby Routing

- [x] Add a lobby provider interface shared by external, managed, and virtual
      modes.
- [x] Route first joins to the configured lobby provider.
- [x] Route players back to the lobby when a managed game server stops or kicks
      them.
- [x] Handle lobby startup failure without creating a reconnect loop.
- [ ] Support forced-host and multiple-lobby configurations later without making
      them part of the first release.
- [x] Add health and readiness reporting for the active lobby provider.

## Phase 6: Reliability and Security

- [ ] Restrict all configured paths to the SLS-LITE data directory unless an
      operator explicitly allows an external path.
- [x] Validate Java commands and arguments without invoking a shell.
- [ ] Prevent path traversal in blueprint IDs, world names, and instance IDs.
- [ ] Redact secrets and sensitive paths from user-facing errors.
- [ ] Bound retained logs and rotate or delete old instance logs.
- [ ] Make all shared lifecycle and registry state thread-safe.
- [ ] Add structured errors for configuration, preparation, startup, connection,
      shutdown, and cleanup failures.
- [x] Add a diagnostic command that reports host capabilities and current resource
      allocations.
  - [x] Adapt `/sls system` to report the local runtime, JVM memory, CPU threads,
        managed memory budget, and active managed process count.
  - [x] Probe child-process creation, writable paths, and loopback-port binding
        with actionable pass/fail results.

## Testing

- [x] Replace the current `main()` test classes with JUnit tests.
- [x] Set up a local Pterodactyl Panel/Wings environment with a Java 25
      Velocity allocation and browser-accessible server console.
- [ ] Test memory parsing.
- [x] Test path validation, configuration validation, and ID generation.
- [x] Test lifecycle transitions and invalid concurrent actions.
- [x] Test port allocation and release.
- [x] Test process output parsing, startup timeout, graceful shutdown, and crash
      handling with a small fixture process.
- [x] Test queue success, timeout, cancellation, disconnect, failed-start
      behavior, duplicate requests, and orphan cleanup.
- [x] Test durable instance metadata and unclean-shutdown reconciliation.
- [ ] Test external and managed lobby routing.
- [ ] Add virtual lobby compatibility tests if that mode is adopted.
- [x] Add an integration fixture that launches Velocity and one lightweight
      backend in a constrained single-host environment.
- [ ] Add CI for compilation, tests, packaging, and dependency checks.

## First Usable Release

- [ ] One plugin JAR and one documented configuration.
- [x] One Velocity allocation can launch an isolated Paper lobby and at least one
      additional managed backend.
- [x] Players can join the lobby, request a blueprint, wait for startup, connect,
      and return to the lobby after the backend stops.
- [ ] External lobby mode works without SLS-LITE managing that lobby.
- [x] Every child process shuts down cleanly with the proxy.
- [x] Startup failures leave no registered ghost servers or corrupt instance
      directories.
- [ ] Installation and host-capability requirements are documented honestly.
