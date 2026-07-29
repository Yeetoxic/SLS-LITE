# SLS-LITE Roadmap

## Scope Classification

Stage tags identify the gate that requires an item:

- `[S1]` Surface Level: prove the core SLS-LITE network model with our own
  blueprints and worlds.
- `[S2]` Compatibility Run: load and run the supported subset of modern SLS
  configuration and blueprints.
- `[S3]` Full Stack: validate the complete cohesive network and its failure
  behavior.
- `[S4]` Release Candidate: prepare and distribute an externally testable build.
- `[S5]` Release: approve and publish the first public release.

Work proceeds in stage order. An unchecked item without a stage tag is deferred
unless it becomes necessary to satisfy the active stage. Stage 4 remains
provisional until the project owner provides its final notes.

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

Core SLS-LITE operation must require only the SLS-LITE plugin JAR. Optional
integrations may improve interoperability, but lifecycle management, SLS-Limbo
access, and SLS-LITE administration must not require another plugin.

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
- [x] Reload blueprint and software definitions as one validated, immutable
      catalog revision without changing active instance definitions.
- [ ] Reload host-wide `config.yml` services without corrupting active instance
      state; until then require a Velocity restart for host configuration.
- [ ] Preserve a migration path from the existing registry YAML files.

## Phase 3: Process Supervisor

- [x] Introduce explicit instance states:
      `CREATED`, `PREPARING`, `STARTING`, `READY`, `STOPPING`, `STOPPED`,
      and `FAILED`.
- [x] Use a bounded executor for process and log supervision.
- [x] Give every instance a unique ID, directory, Velocity server name, and port.
- [x] Implement a synchronized port allocator that probes availability, skips
      occupied ports, and releases reservations during cleanup.
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
- [ ] Implement optional true copy-on-write volume preparation:
  - Define `auto`, `copy`, `reflink`, and `overlay` storage strategies without
    changing the meaning of modern SLS copy-on-write volume intent.
  - Probe capabilities per configured storage location and filesystem, not only
    once per host, and repeat the probe when the path or filesystem identity
    changes.
  - Verify candidate reflink support by proving that a write to the clone does
    not alter its source; do not trust command success alone.
  - Probe Linux OverlayFS or rootless FUSE overlays only when explicitly enabled,
    including mount, write isolation, unmount, and cleanup checks.
  - Keep every shared lower/source world immutable while instances are active.
  - Cache successful capability results and report the selected strategy in
    startup diagnostics and `/sls system`.
  - Let operators require a strategy or permit automatic fallback through
    validated configuration.
  - Reconcile stale overlay mounts and writable layers after an unclean proxy
    shutdown without deleting the immutable source.
  - Never hard-link files that a managed server may modify.
- [ ] Automatically fall back to tested portable copying when an optimization is
      unavailable, unless configuration explicitly requires that strategy.
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
- [x] Match modern vSLS matchmaking action-bar feedback: animate the gold
      loading wave while queued, replace it with the green joining message
      before transfer, and clear it on dequeue, timeout, failure, or disconnect.
- [x] Add `/sls dequeue` for self, named-player, `all`, and `local` queue
      cancellation.
- [x] Add `/sls logs` with the vSLS-compatible
      `<server> [page] [lines]` arguments and bounded local retention.
- [x] Require explicit permissions for administrative actions.
- [x] Add permissions for other-player join actions.
- [x] Add a small built-in SLS-LITE administrator store so core administration does
      not require LuckPerms or another permission plugin.
  - [x] When no administrator exists, print a random, single-use, short-lived claim
    code to the proxy console.
  - [x] Let an online player claim initial administration with
    `/sls admin claim <code>` from SLS-Limbo or another backend.
  - [x] Store administrator identity by UUID internally, without requiring operators
    to find or enter UUIDs themselves.
  - [x] Add `/sls admin add <online-player>`, `remove`, and `list` with player-name
    completion for normal administration.
  - [x] Let the proxy console regenerate a recovery claim code and add or remove
    administrators without already having an in-game administrator.
  - [x] Continue honoring permissions supplied by Velocity permission providers.
  - [x] Do not attempt to replace a general-purpose network permission plugin.
  - [x] Invalidate claim codes after one successful use or their configured expiry,
    never persist them, and omit them from user-facing diagnostics.
  - [x] Warn that username-derived offline UUIDs are not a secure administrator
    identity when the proxy permits unverified clients; require an explicit
    insecure override before granting persistent in-game administration in
    offline mode.
  - [x] Manually verify claim, list, add/remove, and administrative command access
    through the local Pterodactyl Velocity fixture.
  - [ ] Add granular built-in node assignments only if real SLS-LITE use cases
    require more than the administrator role and external permission providers.
- [x] Support joining an existing instance or creating one from a blueprint.
- [x] Add automatic idle shutdown with a configurable delay.
- [x] Add optional ViaVersion integration for backend protocol detection.
  - [x] Let SLS-Limbo advertise one explicitly tested baseline
    protocol and use proxy-installed ViaVersion, when available, to translate
    newer supported clients to that baseline.
  - Detect ViaVersion through its public API without making it a required
    dependency; preserve native SLS-Limbo operation when it is absent.
  - Treat ViaBackwards and ViaRewind as optional operator choices for older
    clients, not SLS-LITE requirements.
  - Never report a newly released client as compatible until the installed
    Velocity and ViaVersion versions both understand it and the complete
    SLS-Limbo transfer path has passed testing.
- [x] Avoid requiring PacketEvents unless a retained feature needs packet-level
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
- [x] Add operational blueprint hover details and click-to-suggest join actions,
      including software, limits, persistence, active instances, and volumes.
- [x] Add concise proxy-console lifecycle events for accepted start, join,
      stop, restart, reset, console, software-install, readiness, connection,
      and process-exit operations without mirroring managed server output.
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
  - [x] Cover protected-stop rejection, granular force permission, built-in
        administrator `this` resolution, evacuation failure, and force
        completion.
  - [x] Drain protected lobbies before evacuation so new arrivals use SLS-Limbo,
        reject overlapping forced stops, and restore primary routing when an
        evacuation is cancelled.
  - [x] Add permission-gated `--force` modifiers for protected managed-lobby
        restart and reset, with SLS-Limbo evacuation and lobby-provider-owned
        replacement tracking.
  - [x] Automatically track every player who actually enters SLS-Limbo so
        command-driven lobby evacuation returns them when the primary is ready.

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

### Full Review Gate

- [x] Pause feature expansion and complete a full project review before beginning
      automatic Paper downloads or any other provider-backed installer work.
  - [x] Resolve the first review's lifecycle and resource findings:
    reclaim verified orphan children, enforce `max_instances` in the controller,
    cross-validate definition reloads before installation, and release terminal
    SLS-Limbo reservations.
  - [x] Resolve the second review's configuration and consistency findings:
    validate mandatory lobby/Limbo capacity and Velocity forwarding mode, reject
    unknown structural YAML keys, install definitions through one atomic catalog,
    and replace the hidden process ceiling with a configured, reported limit.
  - [x] Review architecture, lifecycle ownership, concurrency, cleanup, path
    security, resource accounting, configuration compatibility, commands,
    permissions, diagnostics, and public documentation.
  - [ ] Re-run the documented manual Pterodactyl
    workflows for external lobby, managed lobby, SLS-Limbo, matchmaking,
    recovery, shutdown, and protocol compatibility before the first public
    release.
  - [x] Re-run the complete automated suite after every review correction.
  - [x] Classify every remaining TODO as required for the first usable release,
    intentionally deferred, or removed from scope.
  - [x] Require explicit project-owner approval before checking off or starting the
    provider-based Paper installer tasks below.

### Software Profiles and Installation

- [x] Add version-to-Java mappings so a software profile can select the correct
      configured Java runtime for a Minecraft version.
- [x] Cache prepared server software by software ID and version.
- [x] Add provider-based installers for Paper and vanilla while preserving
      manually prepared custom Java software.
  - [x] Resolve the blueprint's exact Paper game version and allow explicit
        stable, beta, or alpha selection without version fallback.
- [x] Verify downloaded artifacts with provider size and checksum metadata.
- [x] Lock software installation so two instances cannot install the same version
      concurrently.
- [x] Add installation state, timeout, progress, bounded failure logs, and retry
      behavior.
- [x] Require explicit profile-level EULA acceptance and write `eula.txt` only
      after the operator opts in.
- [ ] Add an operator-triggered software-cache cleanup command with dry-run
      output, a configurable minimum age, and explicit confirmation. Treat
      versions referenced by loaded blueprints, active or persistent instances,
      and in-progress installations as live; never purge them automatically.
- [ ] Evaluate verified Java-runtime acquisition for hosts that provide only one
      Java version. Cache runtimes by exact major/version, record checksums and
      upstream terms, retain manual runtime paths, and require an explicit
      operator action before downloading or pruning a runtime.
- [ ] Add an optional warmup process that generates required base files and
      stops before a reusable template is published.
- [x] Keep manually prepared server directories fully supported.
- [x] Do not execute arbitrary shell installation scripts by default on shared
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
- [ ] Add first-class per-blueprint client resource-pack configuration:
  - Reuse modern SLS fields where an upstream contract exists.
  - Validate a client-reachable HTTP(S) URL and SHA-1 without downloading or
    silently modifying the operator's pack.
  - Support required/optional behavior and a prompt only on Minecraft versions
    that implement those fields.
  - Apply, replace, or clear the active pack during Velocity server transfers so
    a pack from one minigame does not leak into another.
  - Keep normal `server.properties` resource-pack keys supported as the portable
    fallback.
  - Document that Minecraft clients fetch packs over HTTP(S); SLS-LITE cannot
    make a panel-private file client-reachable without an exposed web endpoint.
  - Do not require a separate Velocity or Paper plugin for core pack switching.

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
      and reclaims surviving children only when PID and process start time both
      match, while preserving persistent data, ambiguous live processes,
      malformed metadata, and legacy directories.
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
- [x] Prefer existing ready instances with capacity before creating another.
- [x] Enforce per-blueprint player and instance limits during allocation.
- [x] Cancel provisioning when its queue becomes empty when safe to do so.
  - [x] Request cancellation for queue-owned PREPARING and STARTING instances
        immediately when the last queued player leaves instead of allowing an
        orphan to finish booting.
  - [x] Make volume preparation cooperatively cancellable between files and
        retry waits so PREPARING cancellation does not finish copying an entire
        world before rollback.
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
- [x] Retain bounded failed-start diagnostics outside ephemeral instance
      directories, including the failure phase and recent managed output, and
      prune old reports automatically.
- [x] Add line-oriented console command execution through the supervised process
      input without competing with the process log reader.
- [ ] Add bounded output capture for `/sls console` so the invoking player sees
      the relevant child-console response without retaining unbounded logs.
- [ ] Add create-time overrides for memory, save mode, seed, view distance, and
      selected safe configuration values.
- [ ] Add per-instance reset, restart, delete, and force-kill operations.
  - [x] Add persistent restart with same-ID directory reuse and lobby evacuation.
  - [x] Add rollback-protected persistent reset from the current template.
  - [x] Allow explicit forced restart and reset of the protected managed lobby
        without bypassing SLS-Limbo evacuation or lobby-provider ownership.
  - [ ] Add persistent delete and explicit force-kill operations.
- [ ] Add software and blueprint reload commands with detailed validation errors.
- [ ] Add a public Java API so other Velocity plugins can:
  - Inspect blueprints and instances.
  - Request an instance and queue a player.
  - Subscribe to lifecycle events.
  - Stop or delete instances when authorized.
  - Compile against a small versioned API artifact without depending on internal
    process, configuration, or virtual-lobby implementation classes.
  - Detect capabilities and API versions so optional integrations fail cleanly.
- [ ] Add optional metrics with collection disabled by default and no sensitive
      host, path, player, or blueprint data.

### Constrained-Host Performance

- [x] Log instance preparation and readiness durations separately so slow file
      preparation can be distinguished from slow server-software startup.
- [x] Start managed JVMs with a small initial heap while preserving the
      blueprint memory limit as `-Xmx` and as the SLS-LITE admission budget.
- [x] Promote reusable Paper runtime libraries and caches from completed
      instances back into the versioned software cache.
- [x] Avoid unnecessary source-attribute preservation during portable instance
      copying and report software resolution, file preparation, and process
      readiness as separate timings.
- [x] Resume a persistent managed lobby by its existing instance ID instead of
      copying its world again after every Velocity restart.
- [ ] Benchmark representative small, medium, and large worlds on native Linux
      storage and on the supported local test environment. Record preparation,
      process readiness, idle memory, loaded memory, and first-player transfer
      time.
- [ ] Document practical minimum and recommended memory allocations for
      Velocity, SLS-Limbo, legacy Paper, current Paper, and common small-network
      layouts. Treat these as measured guidance rather than enforced guarantees.
- [ ] Document that Docker Desktop bind mounts and other remote or translated
      filesystems can heavily penalize world copying and Paper region-file
      startup, and recommend native Linux storage where the operator controls it.
- [ ] Add optional per-blueprint warm pools with a configurable minimum number
      of ready instances, strict memory and process-budget accounting, bounded
      replenishment, and no warm instance creation for unused blueprints by
      default.
- [ ] Add blueprint-level view-distance and simulation-distance settings, safe
      version-aware property application, validation, and constrained-host
      recommendations.
- [ ] Build an explicit offline world-optimization workflow that can copy and
      prune unused legacy regions without modifying the operator's source world
      or archive. Require a dry run, backup destination, and manual approval
      before deleting data from the optimized copy.
- [ ] Complete the true copy-on-write implementation in Phase 4, benchmark
      reflink and overlay preparation where supported, compare startup time and
      physical disk usage against portable copies, and retain portable copying
      as the tested fallback.
- [ ] Add a repeatable startup-performance acceptance test and define regression
      thresholds only after native-Linux baseline measurements exist.

## Lobby Support

### Lobby Modes

- [x] Support `lobby.mode: external`.
  - Use a normal separately hosted lobby already registered with Velocity.
  - Never start, stop, copy, or otherwise manage that server.
  - Preserve this as the conventional and maximum-compatibility option.

- [x] Support `lobby.mode: managed`.
  - [x] Start a Paper lobby as an SLS-LITE child process in the same hosting
    allocation as Velocity.
  - [x] Treat the active lobby as a reserved blueprint that normal stop commands
    cannot terminate.
  - [x] Add `/sls stop <server> --force` for administrators to intentionally stop
        a protected managed server.
    - Require an administrative force permission and exact instance resolution.
    - Evacuate connected players to the primary fallback or SLS-Limbo when
      possible, and clearly report when evacuation cannot be completed.
    - Divert new arrivals while evacuation is in progress and restore normal
      routing if the stop is cancelled.
    - Suppress automatic crash recovery for an intentional forced stop so the
      supervisor does not immediately restart the server.
    - Log the sender, target, and result, and cover permission denial, `this`
      resolution, player evacuation, and recovery suppression with tests.
  - [x] Start it during proxy initialization and wait for readiness before routing
    players.
  - [x] Keep it running independently of normal matchmaking cleanup.
  - [x] Restart it after a crash with bounded retry and backoff.
  - [x] Disconnect with a clear temporary-unavailability message while it is
        starting, recovering, or offline.
  - [x] Stop it gracefully during proxy shutdown.
  - [ ] Allow operators to disable automatic startup and manage it with commands.

- [x] Add SLS-Limbo, a default embedded virtual lobby that starts before the
      configured external or managed primary lobby.
  - [x] Keep normal matchmaking queues on the player's current healthy backend;
    never route through SLS-Limbo merely because a requested destination is
    starting.
  - [x] Keep `external` and `managed` as primary lobby modes; do not expose the
    SLS-Limbo as another mutually exclusive primary mode.
  - [x] Route players to SLS-Limbo whenever the primary is starting,
    recovering, offline, or otherwise unreachable.
  - [x] Keep players connected while managed-lobby recovery runs and transfer
    only players already forced into SLS-Limbo when the primary becomes ready.
  - [x] Provide a minimal safe experience: a fixed spawn, movement, status message,
    and access to proxy-level `/sls` commands.
  - [x] Explain the first failed primary handoff in chat, including safe backend
    disconnect reasons such as protocol incompatibility, without repeating the
    message on every retry.
  - [x] Package the required runtime inside the SLS-LITE JAR so operators do not
    install LimboAPI, PacketEvents, or another companion plugin.
  - [x] Evaluate a maintained embeddable virtual-server engine and its transitive
    dependencies; do not implement the versioned Minecraft protocol from scratch.
  - [ ] Verify compatibility with the selected Velocity version and supported client
    versions.
  - [x] Review dependency licensing before integration.
  - [x] Clearly document that Bukkit/Paper plugins and full server mechanics are not
    available in SLS-Limbo.
  - [x] Keep this feature isolated behind an interface so library or protocol changes
    do not affect the process supervisor.
  - [x] Restart SLS-Limbo after unexpected process failures with configurable,
    bounded exponential backoff and a stable-period retry reset.
  - [x] Report SLS-Limbo state, memory, port, retry usage, last failure, and
    dual-lobby availability through `/sls info`, `/sls system`, and console
    diagnostics.
  - [x] Enter a clearly reported degraded state if both the primary and SLS-Limbo
    are unavailable: keep Velocity, console administration, diagnostics, and
    bounded recovery running; reject players without a usable backend and never
    silently route them to a game backend.

### Lobby Routing

- [x] Add a lobby provider interface for external and managed primary modes.
- [x] Add a fallback lobby coordinator that selects the healthy primary when
      available and SLS-Limbo otherwise.
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

## Code Maintainability and Manual Modification

Perform this cleanup incrementally after the Stage 2 compatibility boundary is
stable. Refactoring must preserve command output, configuration compatibility,
data layout, lifecycle behavior, and the passing test suite.

- [ ] Audit large or multi-purpose classes and document their current
      responsibilities before moving code.
- [ ] Decompose the command implementation into focused subcommand handlers
      without changing the vSLS-compatible command tree, permissions, messages,
      sender restrictions, or tab completion.
- [ ] Clarify package ownership for configuration, blueprint parsing,
      installation, instance lifecycle, matchmaking, lobby routing, Velocity
      integration, and operator-facing presentation.
- [ ] Replace unclear names, duplicated control flow, and unnecessary coupling
      with small, explicit abstractions only where they reduce real complexity.
- [ ] Remove confirmed dead compatibility code and obsolete resources only after
      tests or migration documentation prove they are no longer required.
- [ ] Add concise comments and Javadocs around lifecycle invariants, concurrency
      boundaries, resource accounting, path security, and compatibility
      adaptations that are not evident from the code.
- [ ] Organize bundled defaults, templates, examples, protocol data, and
      third-party resources into predictable locations without silently changing
      generated operator paths or supported configuration keys.
- [ ] Make operator-edited YAML files consistent in layout, ordering, comments,
      valid-value examples, and validation diagnostics.
- [ ] Add a short contributor architecture guide showing the normal modification
      points for commands, blueprints, software installers, lifecycle behavior,
      lobby providers, messages, and tests.
- [ ] Add automated formatting and lightweight static analysis only after their
      rules are agreed upon; apply them in a dedicated reviewable change rather
      than mixing broad formatting churn with behavior changes.
- [ ] Complete the cleanup in small reviewable passes, with focused regression
      tests and manual compatibility checks for every affected subsystem.

## Documentation and Public Release Preparation

- [ ] Audit every existing file in `DOCS/` and classify it as current,
      historical reference, material to rewrite, or obsolete material to remove.
- [ ] Separate internal development notes and test-environment procedures from
      public operator documentation.
- [ ] Replace legacy SLS-LITE terminology, commands, configuration examples, and
      architecture descriptions that no longer match the implementation.
- [x] Preserve useful historical context in a clearly labeled archive instead of
      presenting old behavior as current guidance.
- [ ] Create a concise public README covering the project goal, supported
      environments, current maturity, installation, quick start, and links to
      detailed documentation.
- [ ] Publish operator documentation for:
  - Host requirements and shared-host limitations.
  - Installation, first startup, updates, backups, and uninstallation.
  - The complete commented `config.yml` reference and validation rules.
  - Software profiles, blueprints, registries, instance IDs, and lifecycle.
  - External and managed primary lobbies plus SLS-Limbo.
  - First-administrator claim codes, built-in SLS-LITE permissions, and optional
    external permission providers.
  - Commands, argument forms, selectors, output, permissions, and examples.
  - Resource budgeting, cleanup, logs, recovery, and troubleshooting.
  - Velocity forwarding, optional ViaVersion translation, supported protocol
    states, and the new-Minecraft-release compatibility process.
  - Pterodactyl and generic shared-host deployment without implying that host
    restrictions can be bypassed.
- [ ] Publish administrator migration guidance from historical SLS-LITE and the
      compatible subset of modern SLS, with unsupported distributed features
      identified explicitly.
- [ ] Publish contributor and integration documentation for the versioned
      SLS-LITE API, lifecycle events, capability detection, compatibility policy,
      build process, tests, and release process.
- [ ] Maintain a generated or test-verified command, permission, configuration,
      and feature-compatibility reference so public docs cannot silently drift
      from implemented behavior.
- [ ] Add a supported Velocity, Java, Paper, Minecraft protocol, NanoLimbo-derived
      runtime, and optional ViaVersion compatibility matrix for each release.
- [x] Add initial third-party notices, source links, pinned revisions,
      modification summaries, and license obligations for bundled components.
- [x] Package the SLS-LITE, NanoLimbo, and SnakeYAML licenses plus a consolidated
      third-party notice in every shaded plugin JAR.
- [ ] Mirror the exact corresponding NanoLimbo source in an SLS-LITE-controlled
      release location before distributing a public build with the bundled
      runtime.
- [ ] Review all public documentation before each release and prevent unfinished
      features from being documented as available.
- [ ] Prepare a versioned public documentation site that can share common SLS
      concepts while clearly labeling `SLS and SLS-LITE`, `Full SLS only`,
      `SLS-LITE only`, and `Adapted for local mode` material.

## Testing

- [x] Replace the current `main()` test classes with JUnit tests.
- [x] Set up a local Pterodactyl Panel/Wings environment with a Java 25
      Velocity allocation and browser-accessible server console.
  - [x] Move the local Panel, Wings, MariaDB, and Redis control plane into
        Docker while preserving existing server data and credentials.
  - [x] Proxy signed Wings upload/download routes through the local Panel and
        verify a 128 MiB browser-equivalent file upload.
- [ ] Test memory parsing.
- [x] Test path validation, configuration validation, and ID generation.
- [x] Test lifecycle transitions and invalid concurrent actions.
- [x] Test port allocation and release.
- [x] Test process output parsing, startup timeout, graceful shutdown, and crash
      handling with a small fixture process.
- [x] Test queue success, timeout, cancellation, disconnect, failed-start
      behavior, duplicate requests, and orphan cleanup.
- [x] Test durable instance metadata and unclean-shutdown reconciliation.
- [x] Test external and managed lobby routing.
- [x] Add SLS-Limbo native and ViaVersion-translated protocol compatibility
      tests.
  - [x] Add a pinned login-to-PLAY smoke harness and representative native
        NanoLimbo matrix with backend-brand verification.
  - [x] Verify the fixed `1.21.5`/protocol `770` baseline through Velocity and
        ViaVersion with a newer automated client without heightmap warnings.
  - [x] Verify the same translated path with the current real client, including
        SLS-Limbo movement, commands, and handoff to the primary lobby.
  - [x] Synchronize dynamically registered SLS-Limbo protocol metadata through
        ViaVersion's public API so first boot does not wait for or require a
        persisted backend probe.
  - [x] Synchronize every dynamically named managed backend with ViaVersion
        before routing players, including replacement IDs created during
        managed-lobby recovery.
  - [x] Resolve ViaVersion's translated minimal-heightmap warnings by requiring
        fixed translation baselines to use NanoLimbo's modern protocol `770+`
        heightmap encoding.
- [x] Add an integration fixture that launches Velocity and one lightweight
      backend in a constrained single-host environment.
- [ ] Add CI for compilation, tests, packaging, and dependency checks.

## Release Stages

These are sequential acceptance gates. Completing a stage means its entire
network scenario works, not merely that its individual classes compile.

### Stage 1: The Surface Level

Prove the core concept by operating our own small network entirely through
SLS-LITE blueprints and locally supplied content. Compatibility with upstream
SLS is not required at this gate.

- [x] [S1] Add the modern SLS `state.volumes` blueprint shape for locally
      supplied worlds and other directory content, beginning with `cow` mode.
- [x] [S1] Implement the SLS-LITE local equivalent of `cow` by safely copying
      each selected volume over the installed or manually prepared software base
      when creating an isolated instance.
- [x] [S1] Resolve volume sources inside the SLS-LITE data directory, interpret
      targets relative to the instance root, and add collision, traversal,
      symlink, rollback, and copy-failure tests.
- [x] [S1] Upload several representative test worlds and define an SLS-LITE
      network containing:
  - [x] Copy and verify all 21 top-level world roots from the legacy backup in
        the durable test allocation without modifying their source archive.
  - [x] Promote the verified roots into modern-style `worlds/lobbies`,
        `worlds/minigames`, and `worlds/adventures` paths while retaining the
        immutable legacy mirror.
  - [x] Load blueprints recursively from organized category folders.
  - [x] Define 11 exact-version legacy blueprints across `lobby`, `minigame`,
        and `adventure` registries.
  - [x] Start the historical 1.18.2 lobby as the managed primary using cached
        exact Paper build 388 and Java 17.
  - [x] Define at least two user-defined registries.
  - [x] Define at least two blueprints backed by different promoted worlds.
  - [x] Join and verify at least two promoted game worlds in a real client.
- [x] [S1] Start the network from one Velocity allocation, join every world,
      switch between servers with `/sls join`, and return to the lobby.
  - [x] Verify the lobby and eight promoted game worlds in a real client.
  - [x] Retry Meteor Miners after addressing transient volume-copy I/O failures;
        retain enough bounded failure evidence to distinguish damaged source
        data from translated-filesystem errors.
    - [x] Retry transient per-file `FileSystemException` copy failures with
          bounded backoff, remove partial targets, and preserve rollback after
          the retry budget is exhausted.
      - [x] Extend the translated-filesystem retry window to eight seconds after
            Combat Cube reproduced a longer Docker Desktop bind-mount I/O stall.
    - [x] Preserve bounded failed-start reports after ephemeral cleanup.
  - [x] Verify Missile Wars Paper 1.16.5 starts and accepts a player after
        correcting dynamic ViaVersion registration.
    - [x] Confirm exact Paper 1.16.5 installation and subsequent cache reuse.
    - [x] Restart Velocity with the fixture's 600-second queue timeout active.
    - [x] Log the effective per-request queue timeout and per-process readiness
          timeout so runtime behavior can be compared directly with configuration.
    - [x] Identify the 40-60 second cutoff: Paper reached readiness, but the
          subsequent two-second ViaVersion protocol-detection ping timed out and
          registration forcibly stopped the otherwise-started process.
    - [x] Resolve known blueprint Minecraft versions through ViaVersion's
          protocol catalog before registration, retaining backend ping detection
          only for custom or unknown versions.
    - [x] Retain registration failures and non-ready failed process exits before
          ephemeral cleanup so the child server output survives future failures.
- [x] [S1] Confirm instance isolation, capacity handling, persistence or
      ephemeral cleanup, idle shutdown, and proxy shutdown with the test worlds.
- [x] [S1] Record the exact fixture configuration and manual acceptance results.

### Stage 2: Compatibility Run

Prove that SLS-LITE remains part of the SLS ecosystem by speaking the same
configuration language wherever a distributed feature has a safe local
equivalent.

- [ ] [S2] Pin the modern SLS release and commit used for this compatibility run.
- [ ] [S2] Compare modern SLS and SLS-LITE feature by feature across
      configuration, blueprints, registries, matchmaking, lifecycle, commands,
      permissions, observability, installation, storage, and integrations.
- [ ] [S2] Classify every compared feature as `supported`, `adapted for local
      mode`, `intentionally unsupported`, or `deferred`, with a reason for every
      difference.
- [ ] [S2] Perform a scope-balance review before Stage 3:
  - Identify important shared SLS behavior that SLS-LITE is missing.
  - Identify SLS-LITE behavior that is unnecessary, overbuilt, or outside its
    single-host purpose.
  - Confirm that retained features remain practical on constrained shared hosts.
  - Obtain project-owner approval for the resulting scope and compatibility
    matrix.
- [ ] [S2] Define and publish the supported modern SLS configuration and blueprint
      subset, including local adaptations and intentionally unsupported fields.
- [ ] [S2] Load representative pre-made modern SLS software definitions,
      configuration, and blueprints without requiring manual schema translation.
- [ ] [S2] Map supported distributed concepts to their documented local
      equivalents while rejecting unsupported behavior with actionable errors.
- [ ] [S2] Preserve compatible names, annotations, registry types, limits,
      lifecycle intentions, and content declarations.
- [ ] [S2] Define the modern SLS copy-on-write volume compatibility contract:
      preserve its isolation intent while documenting `reflink`, `overlay`, and
      portable-copy implementations as host-dependent local adaptations.
- [ ] [S2] Add upstream-derived compatibility fixtures and automated parser,
      validation, and migration tests where licensing permits.
- [ ] [S2] Run a multi-world network from those modern SLS fixtures and document
      every supported, adapted, and rejected field.

### Stage 3: The Full Stack

Run the complete SLS-LITE product as one cohesive network. Expected failures
must degrade cleanly without crashing Velocity, corrupting data, leaking child
processes, or trapping players in unexplained states.

- [ ] [S3] Verify every locally supported command, argument branch, selector,
      permission, sender restriction, output, and tab-completion path.
- [ ] [S3] Exercise managed lobby, external lobby, SLS-Limbo fallback, queues,
      transfers, multiple registries, full-server provisioning, and forced
      administrative operations in one test plan.
- [ ] [S3] Exercise Paper and vanilla installation, manual custom software,
      cache reuse, exact versions, Java selection, EULA gating, failed downloads,
      retries, and incomplete-cache recovery.
- [ ] [S3] Exercise automatic and explicitly required COW strategies across
      supported and unsupported filesystems; verify instance isolation, immutable
      source worlds, fallback behavior, physical disk savings, mount cleanup, and
      unclean-shutdown reconciliation.
- [ ] [S3] Exercise normal shutdown, startup cancellation, process crashes,
      readiness timeout, lobby recovery exhaustion, memory rejection, occupied
      ports, proxy restart, and persistent-instance recovery.
- [ ] [S3] Confirm every failure produces bounded, useful console, chat, action
      bar, command, and temporary-log diagnostics without spam.
- [ ] [S3] Confirm all children stop or are safely reconciled, all Velocity
      registrations and ports are released, and no instance data is silently
      corrupted.
- [ ] [S3] Re-run the complete automated suite and the documented Pterodactyl
      workflow with supported real clients.
- [ ] [S3] External lobby mode works without SLS-LITE managing that lobby.
- [ ] [S3] Resolve every release-blocking defect found by the full-stack run and
      repeat affected scenarios.

### Stage 4: Release Candidate

This gate produces the build sent to external testers ahead of public release.
Its final scope is intentionally waiting for project-owner notes.

- [ ] [S4] Await and incorporate the project-owner release-candidate notes.
- [ ] [S4] Produce one versioned plugin JAR and one canonical documented
      configuration.
- [ ] [S4] Document installation, host capabilities, supported runtimes,
      compatibility boundaries, operations, recovery, and known limitations.
- [ ] [S4] Complete the documentation audit, third-party source and license
      obligations, compatibility matrix, and release notes.
- [ ] [S4] Add CI for compilation, tests, packaging, and dependency checks.
- [ ] [S4] Publish the candidate artifact and checksums to the selected external
      testers with a structured feedback and reproduction template.
- [ ] [S4] Triage external findings and identify which defects block release.

### Stage 5: Release

- [ ] [S5] Resolve all release-blocking candidate findings and rerun the affected
      Stage 3 scenarios.
- [ ] [S5] Complete the final security, licensing, documentation, compatibility,
      artifact, and clean-install audit.
- [ ] [S5] Build the release artifact from the approved source revision and
      publish its checksum.
- [ ] [S5] Publish the versioned documentation, source, license materials,
      migration notes, and supported compatibility matrix.
- [ ] [S5] Tag and publish the first public SLS-LITE release.
