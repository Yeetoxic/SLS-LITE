# SLS-LITE Roadmap

This is the canonical project plan. Work proceeds in stage order, and a stage is
complete only when its acceptance gate passes. Completed implementation history
is summarized instead of repeated. Optional ideas remain visible under the
Stage 4 scope review until they are either approved or explicitly deferred.

## Product Contract

SLS-LITE runs a small dynamic Minecraft network from one Velocity allocation.
It launches and supervises local Java children, binds backends to loopback,
shares the allocation's CPU and memory, and works from writable panel storage.
Conventional separately hosted backends and lobbies remain supported.

Core operation requires only the SLS-LITE plugin JAR. Optional integrations may
improve compatibility, but administration, lifecycle management, and SLS-Limbo
must not require another plugin, controller, daemon, VPS, container platform, or
full SLS installation.

Project-wide invariants:

- Never bypass panel limits, container restrictions, provider security, or
  provider terms.
- Never invoke operator-controlled Java or helper arguments through a shell.
- Never execute arbitrary shell installation scripts by default on shared hosts.
- Confine managed paths and reject traversal; external paths require an explicit
  documented operator opt-in.
- Keep source worlds immutable and never hard-link or share mutable server data.
- Preserve transactionality, bounded resource use, useful diagnostics, and safe
  reconciliation after cancellation, failure, crash, or proxy restart.
- Do not claim resource enforcement, compatibility, or isolation that the
  detected host cannot provide.
- Keep modern SLS compatibility local: distributed-only operations return an
  actionable local-mode response rather than partial emulation.
- Keep ViaVersion, ViaBackwards, ViaRewind, PacketEvents, and permission plugins
  optional unless a retained feature has a proven hard requirement.

## Stage 1: Surface Level — Complete

Goal: prove that one Velocity allocation can run a usable multi-world
SLS-LITE network from local blueprints and worlds.

- [x] Establish the Java 21 Velocity plugin, shaded runtime, configuration,
      blueprint/software catalogs, commands, and passing Maven baseline.
- [x] Implement bounded local Java supervision, unique instance IDs/directories,
      loopback ports, readiness, shutdown, crash handling, memory admission, and
      proxy-shutdown cleanup.
- [x] Implement isolated portable instance copies, persistent and ephemeral
      lifecycles, idle shutdown, reset, and restart.
- [x] Register managed backends with Velocity; implement queueing, joining,
      cancellation, transfers, registry-aware discovery, and cleanup.
- [x] Implement the initial command, permission, built-in administrator, log,
      status, and diagnostic surfaces.
- [x] Implement external and managed lobby modes plus the bundled SLS-Limbo
      fallback.
- [x] Build the local Docker Pterodactyl Panel/Wings fixture and verify multiple
      worlds, sibling instances, transfers, capacity, persistence, isolation,
      and cleanup with a real client.

Acceptance: the recorded Stage 1 Pterodactyl workflow passed without corrupting
source data, leaking children, or requiring external SLS infrastructure.

## Stage 2: Compatibility Run — Complete (2026-07-29)

Goal: load and run the supported local subset of modern SLS configuration and
blueprints without manual schema translation.

- [x] Pin SLS `v0.2.0` and publish field-level configuration, blueprint,
      command, lifecycle, and feature compatibility matrices.
- [x] Implement the constrained modern software adapter, Java/version mappings,
      software inheritance, provider-backed Paper/vanilla installation, checksum
      verification, EULA gating, locking, retry, and manual software support.
- [x] Implement modern blueprint metadata, limits, persistence, `state.volumes`,
      `state.copy`, `state.env`, safe configuration patches, annotations,
      matchmaking game types, and bounded on-join actions.
- [x] Preserve compatible fields and unknown annotations, reject unsupported
      distributed structures with actionable local alternatives, and retain
      immutable atomic catalog reloads.
- [x] Load the exact copied 54-blueprint compatibility corpus and add attributed
      parser, validation, migration, configuration-editor, and lifecycle tests.
- [x] Run the representative multi-version network through Pterodactyl with real
      client joins, transfers, sibling provisioning, persistent/ephemeral
      behavior, and deliberate unsupported examples.
- [x] Resolve the Stage 2 review findings covering lifecycle ownership, resource
      accounting, parser determinism, annotation validation, runtime selection,
      path safety, cleanup, and documentation.

Acceptance: the documented 54-ID corpus gate, automated suite, packaging gate,
and focused Pterodactyl compatibility run passed.

## Stage 3: Full Stack

Goal: run the entire retained product as one cohesive, recoverable network.
Expected failures must degrade cleanly without crashing Velocity, corrupting
data, leaking resources, or trapping players in unexplained states.

### 3.1 Host Capabilities and Storage Selection

- [x] Report filesystem identity/capacity/attributes, atomic moves, configured
      Java runtimes, process creation, writable paths, loopback binding, process
      identity, managed-memory budget, storage probes, and selected strategy at
      startup and through `/sls system`.
- [x] Support `auto`, `copy`, `reflink`, `btrfs`, `overlay`, `fuse-overlay`, and
      explicit-only `snapshot-hook`; fail unavailable explicit strategies rather
      than silently copying.
- [x] Use the documented automatic priority: reflink, eligible Btrfs snapshot,
      kernel OverlayFS, rootless fuse-overlayfs, then portable copy. Select only
      after an exact-path isolation and cleanup probe.
- [x] Cache successful probes by storage path and filesystem identity, and
      invalidate them when either identity changes.
- [x] Add provider-specific available-memory detection only where it is reliable
      and remains inside panel/container limits.
- [x] Test named shared-host/panel capability profiles and publish the supported,
      degraded, and unsupported results.

### 3.2 COW Backends and Universal Fallback

- [x] Implement transactional reflink cloning with per-source fallback, explicit
      failure, cancellation, source isolation, and real XFS shared-extents proof.
- [x] Implement kernel OverlayFS with durable manifests, verified mount
      ownership, multi-lower precedence, persistent remount, reset/delete,
      rollback, and crash reconciliation.
- [x] Implement eligible Btrfs subvolume snapshots with isolation, durable
      manifests, persistent replacement, deepest-first deletion, fallback for
      ineligible sources, reconciliation, and shared-extents proof.
- [x] Implement fuse-overlayfs with verified daemon ownership/arguments,
      persistent rediscovery/remount, reset/delete, rollback, reconciliation, and
      safe fallback when an unprivileged mount is denied.
- [x] Implement explicit `sls-snapshot-helper-v1` with a confined executable,
      fixed shell-free arguments, bounded output/timeouts, durable manifests,
      lifecycle operations, rollback, and fake-provider failure tests.
- [x] Improve portable copy with bounded parallelism, cooperative cancellation,
      deterministic rollback, sparse-file preservation on supported hosts, and
      persistent-directory reuse.
- [x] Reuse only verified immutable software/cache artifacts; never share a file
      a managed server may modify.
- [x] Run the complete Pterodactyl/Velocity fixture on disposable XFS
      `reflink=1`: verify `auto`, provisioning, isolation, persistent restart,
      reset, deletion, reconciliation, cleanup, and physical extent sharing.
- [x] Run the complete fixture on disposable Btrfs: verify eligibility,
      snapshots, isolation, fallback, lifecycle, reconciliation, cleanup, and
      physical savings.
- [x] Run the complete fixture in a disposable kernel-OverlayFS profile granting
      only the test privileges; verify lifecycle and leaked-mount absence without
      weakening the normal Pterodactyl profile.
- [x] Run the complete fixture in a disposable `/dev/fuse` profile; verify
      genuinely rootless selection and lifecycle without weakening the normal
      profile.
- [x] Run the complete fixture with the fake snapshot helper, including prepare,
      suspend/resume, reset, delete, timeout, malformed response, rollback, and
      crash reconciliation. Treat real ZFS, LVM-thin, and provider integrations
      as optional Stage 4 scope.
- [x] Compare every strategy using preparation/readiness time, bytes
      read/written, logical and physical disk use, cancellation, cleanup, and
      persistent restart.

### 3.3 Performance and Resource Efficiency

- [x] Record bounded monotonic timings for dispatch, software resolution, file
      preparation, configuration, launch, readiness, registration, shutdown,
      cleanup, and total elapsed time, including failed/cancelled starts.
- [x] Record initial Windows/Pterodactyl and WSL2-native ext4 baselines.
- [x] Use small initial JVM heaps, enforce configured `-Xmx` admission, reuse
      only provider-verified software artifacts, reject promotion of mutable
      child-written caches, and resume valid persistent instances.
- [x] Add queue, transfer, first-player, and proxy-restart timings.
- [x] Gather repeated small, medium, and large world samples on Windows-backed
      and native Linux storage, including idle/loaded memory and disk behavior.
- [x] Measure child memory, network, and disk only where reliable; otherwise keep
      explicit unavailable labels.
- [x] Document measured minimum/recommended allocations for Velocity, SLS-Limbo,
      legacy/current Paper, and representative network layouts.
- [x] Document translated/remote filesystem penalties and recommend native Linux
      storage where operators control it.
- [x] Audit squeezed allocations: proxy/child memory, process limits, idle
      shutdown, software-cache retention, temporary logs, stale instances, and
      disk reclamation.
- [x] Optimize only measured bottlenecks without weakening immutability,
      admission, transactionality, or recovery.
- [x] Set repeatable regression thresholds only after representative Windows and
      native-Linux distributions exist.

### 3.4 Internal Organization and Maintainability

- [x] Inventory package responsibilities and dependencies, prioritizing the
      crowded `instance` package, and publish a target package map.
- [x] Separate transactional storage/COW preparation into `instance.storage`,
      retaining only its cancellable lifecycle API and shared preparation
      exception across the orchestration boundary.
- [x] Separate immutable instance values, versioned metadata persistence, and
      startup recovery into `instance.model`, `instance.metadata`, and
      `instance.reconcile`.
- [x] Separate instance-confined forwarding, properties, YAML, and text editing
      into `instance.configuration`.
- [x] Separate lifecycle transitions, idle admission, and idle reaping into
      `instance.lifecycle` without exposing `ManagedInstance` construction.
- [x] Extract lifecycle timing and diagnostics behind focused ownership
      boundaries: `InstanceOutput` encapsulates its package-private bounded
      buffer and temporary file, while timing and process metrics expose only
      intentional service/read-only APIs. Retain process supervision in its
      existing focused package.
- [x] Decompose the command implementation into focused handlers without
      changing syntax, output, permissions, sender rules, or completion.
- [x] Audit other large/multi-purpose classes and extract abstractions only where
      they reduce demonstrated complexity or duplication.
- [x] Clarify ownership for configuration, blueprints, installation, instances,
      matchmaking, lobbies, Velocity integration, and presentation.
- [x] Remove dead compatibility code/resources only after tests or migration
      documentation prove they are obsolete.
- [x] Document lifecycle invariants, concurrency boundaries, resource
      accounting, path security, and non-obvious compatibility adaptations.
- [x] Organize bundled defaults, templates, examples, protocol data, tests, and
      third-party resources without changing generated operator paths or keys.
- [x] Normalize operator-edited YAML layout, ordering, comments, examples, and
      validation diagnostics.
- [x] Publish a contributor architecture guide covering normal modification
      points for commands, blueprints, installers, lifecycle, storage, lobbies,
      messages, and tests.
- [x] Apply moves in small reviewable passes, keep broad formatting separate,
      and run the full Maven suite plus focused Pterodactyl/Velocity smoke tests
      after every ownership-boundary change.
- [x] Add agreed automated formatting and lightweight static analysis in a
      dedicated reviewable change.

### 3.5 Commands, Permissions, and Operator Workflows

- [x] Complete locally useful vSLS commands while keeping unsafe portable
      pause/resume emulation out of scope:
  - [x] `create`, including only supported safe local overrides:
    - [x] Provision and start the pinned bare `<type> <id>` form with dedicated
          permission, usage, diagnostics, and hidden permission-aware completion.
    - [x] Persist and apply the approved safe override subset across restart and
          reset before accepting any `--name=value` flags.
  - [x] `delete`, with persistent ownership, sequential protected-lobby-safe
        `all` behavior, transactional cleanup, and crash reconciliation.
  - [x] `kill`, with explicit force semantics, evacuation, protected-lobby
        handling, sequential `all`, and owned terminal cleanup.
  - [x] `blueprint`, with dedicated permission, visible details, and hidden
        permission-aware ID completion.
  - [x] `debug`, preserving its pinned player-only sender, permission, toggle,
        feedback, and bounded sanitized diagnostic behavior.
- [x] Finish retained modifier behavior, including force semantics, and enumerate
      every intentionally unavailable daemon-backed modifier.
- [x] Finish output, permission, selector, sender, usage-error, and
      tab-completion parity for every supported branch.
- [x] Extend the versioned command contract across every argument branch,
      permission node, sender restriction, selector, modifier, and completion.
- [x] Verify public, granular-provider, built-in-administrator, console, player,
      other-player, force, invalid-usage, and hidden-suggestion cases.
- [x] Add registry-aware create/start/list/administrative forms only where the
      pinned vSLS contract exposes them.
- [x] Add persistent delete and explicit force-kill with evacuation, ownership,
      transactionality, cleanup, and audit diagnostics:
  - [x] Persistent delete.
  - [x] Explicit force-kill.
- [x] Add bounded `/sls console` response capture and opt-in nonblocking live
      console follow.
- [x] Add create-time overrides for memory, save mode, seed, view distance, and
      selected safe configuration fields.
- [x] Ensure distributed-only commands consistently explain why they are
      unavailable and identify the safe local alternative.
- [x] Design and implement an operator join-test command that performs bounded protocol-level
      probes without pretending a synthetic connection is a real player.
- [x] Maintain one generated or test-verified source for command, permission,
      configuration, and compatibility documentation.

### 3.6 Configuration, Software, and Data

- [x] Decide whether a blueprint-level base template adds behavior beyond modern
      `state.volumes`; implement it or document the supported replacement.
- [x] Add safe version-aware view-distance and simulation-distance policies.
- [x] Complete the retained startup-patch contract: properties/YAML behavior,
      runtime placeholders, merge precedence, per-start overrides, and
      instance-confined targets. Add JSON/TOML only when an approved blueprint
      requires them.
- [x] Define and test every accepted memory input form and rejection boundary;
      remove obsolete parsing expectations if memory remains a validated numeric
      MiB field.
- [x] Finish retained per-blueprint lifecycle controls: do-not-stop-when-empty,
      empty-stop delay, maximum concurrent instances, and startup/stop timeout
      overrides.
- [x] Finish modern volume intentions: best-safe COW, immutable read-only data,
      and explicitly shared persistent read-write data with clear safety rules.
- [x] Reload host-wide configuration without corrupting active state, or retain
      and document restart-required keys explicitly.
- [x] Provide detailed atomic software/blueprint reload diagnostics.
- [x] Preserve migration fixtures for historical registry YAML and decide
      whether historical `shutdown`/`config` commands remain documented aliases;
      cover `minigames.yml`, `adventureMaps.yml`, and `archive.yml`.
- [x] Add an operator software-cache cleanup command with dry run, minimum age,
      explicit confirmation, and protection for loaded, active, persistent, and
      installing versions.
- [x] Add optional software warmup only if it can stop before publishing a
      verified reusable template.
- [x] Exercise Paper/vanilla installation, exact versions/channels, Java
      selection, EULA gating, manual software, cache reuse, failed downloads,
      retry, concurrency, cancellation, and incomplete-cache recovery.

### 3.7 Lifecycle, Concurrency, and Failure Safety

- [x] Define and test the lifecycle concurrency matrix for simultaneous start,
      stop, restart, reset, delete, kill, join, dequeue, cleanup, reload,
      cancellation, lobby recovery, and proxy shutdown.
- [x] Make every retained operation idempotent or return a stable conflict
      result; verify state, port, registration, memory, process, directory, mount,
      and queue ownership after each race.
- [x] Add bounded crash-restart policy where useful beyond the managed lobby,
      with exponential backoff and stable-runtime retry reset.
- [x] Add maintenance/drain mode that blocks new creation while allowing active
      instances to finish.
- [x] Audit all configured and blueprint-controlled paths, IDs, manifests,
      software paths, configuration targets, and archive operations for
      confinement, traversal, symlink, overlap, and ambiguous-ownership safety.
- [x] Audit user-facing diagnostics for secret/path redaction while retaining
      actionable console evidence.
- [x] Replace routine console walls with concise startup, lifecycle, recovery,
      and shutdown summaries. Keep warnings, failures, and operator actions
      visible in the normal Velocity console, while routing detailed capability
      probes, timings, reconciliation decisions, storage lifecycle traces, and
      child diagnostics to a dedicated bounded rotating SLS-LITE log with
      configurable verbosity, retention, redaction, and a correlation ID that
      links each console summary to its detailed record. Add a separate,
      default-off configuration option for detailed Velocity-console mirroring,
      independent from detailed file logging; disabling it must never suppress
      concise milestones, warnings, failures, or operator-requested output.
- [x] Verify bounded logs, failure reports, temporary-file cleanup, and retention
      under repeated crashes and failed starts.
- [x] Standardize structured failure phases for configuration, preparation,
      installation, startup, readiness, connection, shutdown, and cleanup.
- [x] Exercise normal shutdown, startup cancellation, process crashes, readiness
      timeout, lobby recovery exhaustion, memory rejection, occupied ports,
      proxy restart, and persistent recovery.
- [x] Confirm every failure produces bounded useful console, chat, action-bar,
      command, and temporary-log diagnostics without spam.
- [x] Confirm every child, port, Velocity registration, queue, mount, helper
      process, staging directory, and memory reservation is released or safely
      reconciled, with no silent data corruption.

### 3.8 Lobby, Matchmaking, Protocol, and Network Scenarios

- [ ] Exercise managed lobby, separately hosted external lobby, SLS-Limbo
      fallback, queues, transfers, multiple registries, full servers, forced
      administration, recovery, and shutdown in one repeatable plan.
- [ ] Verify external lobby mode never starts, stops, copies, or owns that lobby.
- [ ] Verify supported native and ViaVersion-translated client ranges for the
      selected Velocity/NanoLimbo combination; do not claim new versions before
      the complete transfer path passes.
- [ ] Document ViaBackwards and ViaRewind as optional operator choices for older
      clients and preserve native SLS-Limbo operation without ViaVersion.
- [ ] Finish first-available/random pluggable blueprint selection while retaining
      existing ready-instance preference and capacity limits.
- [ ] Allow operators to disable managed-lobby automatic startup only with clear
      routing and recovery behavior.
- [ ] Re-run the real-client workflow across every supported protocol boundary.

### 3.9 Stage 3 Acceptance Gate

- [ ] Add CI for compilation, tests, packaging, dependency checks, and the
      lightweight static rules approved during the organization work.
- [ ] Run the complete automated suite, exact compatibility-corpus gate, package
      verification, storage harnesses, and documented Pterodactyl workflow.
- [ ] Verify every supported command and every configuration, lifecycle,
      installation, storage, lobby, queue, transfer, recovery, and shutdown
      branch retained for the first release.
- [ ] Resolve every release-blocking full-stack defect and repeat each affected
      scenario.

Acceptance: the complete retained product and its expected failure matrix pass
without unexplained player states, leaked resources, weakened security, source
mutation, or silent instance corruption.

## Stage 4: Release Candidate

Goal: freeze scope, produce an externally testable build, and resolve candidate
feedback without destabilizing the completed full stack.

### 4.1 Scope Review

- [ ] Incorporate the project owner's release-candidate notes.
- [ ] Review each candidate below and record one explicit result: include in the
      candidate, defer to a named post-release milestone, or remove from scope.
      Do not leave an unclassified optional feature:
  - [ ] Classify provider/runtime acquisition with exact versions, checksums,
        terms, manual paths, and explicit download/prune actions.
  - [ ] Classify granular built-in roles beyond administrator.
  - [ ] Classify a blueprint validation/conversion command.
  - [ ] Classify stable-upstream-release review, experimental-feature tracking,
        and future `allowed-client-versions` support.
  - [ ] Classify historical `slimelabs:network` plugin-message compatibility.
  - [ ] Classify first-class client resource-pack switching using validated
        modern fields, client-reachable HTTP(S), SHA-1, version-aware
        prompt/required behavior, transfer-time apply/replace/clear, and
        `server.properties` fallback; never upload packs to an external
        conversion service automatically, and evaluate local conversion only as
        an optional adapter/tool.
  - [ ] Classify internal lifecycle events and a small versioned public Java API
        for capability discovery, blueprint/instance inspection,
        queue/start/stop/delete requests, and subscriptions without exposing
        implementation classes.
  - [ ] Classify an authenticated local administration/event API and opt-in
        privacy-safe metrics.
  - [ ] Classify warm instance pools with strict process/memory accounting and
        bounded, opt-in replenishment.
  - [ ] Classify offline world optimization with dry run, separate output,
        backup, and manual deletion approval.
  - [ ] Classify forced-host and multiple-lobby routing.
  - [ ] Classify hot-reload registration reconciliation if plugin hot reload
        becomes supported.
  - [ ] Classify a versioned shared documentation site.
- [ ] Confirm that deferred items are absent from public availability claims.

### 4.2 Candidate Artifact and Documentation

- [ ] Produce one versioned plugin JAR and one canonical commented configuration.
- [ ] Publish installation, update, backup, uninstall, host-capability, runtime,
      storage, compatibility, command, lifecycle, recovery, and troubleshooting
      documentation.
- [ ] Publish a release compatibility matrix for Velocity, Java, Paper,
      Minecraft protocols, bundled NanoLimbo-derived runtime, and optional
      ViaVersion.
- [ ] Label shared material as `SLS and SLS-LITE`, `Full SLS only`,
      `SLS-LITE only`, or `Adapted for local mode`; keep shared text in one place
      where practical.
- [ ] Complete the documentation drift, security, privacy, dependency, source,
      license, third-party notice, and migration audits.
- [ ] Mirror the exact corresponding bundled NanoLimbo-derived source in an
      SLS-LITE-controlled release location.
- [ ] Verify the shaded JAR contains all required licenses/notices and no
      unfinished feature is documented as available.
- [ ] Perform clean-install and upgrade tests on supported Windows/Pterodactyl
      and native-Linux profiles.
- [ ] Publish the candidate artifact and checksums to the selected external
      testers.

### 4.3 Candidate Feedback

- [ ] Triage external findings as release-blocking, scheduled follow-up, or
      rejected with rationale.
- [ ] Fix every candidate blocker and repeat its automated, storage, lifecycle,
      protocol, and real-client scenarios.

Acceptance: external testers receive a reproducible, documented candidate whose
known limitations and compatibility boundaries match observed behavior.

## Stage 5: Release

Goal: approve and publish the first public SLS-LITE release.

- [ ] Resolve all remaining release blockers and rerun affected Stage 3 and
      Stage 4 scenarios.
- [ ] Complete the final clean-install, upgrade, security, licensing,
      documentation, dependency, compatibility, and artifact audit.
- [ ] Build the release artifact from the approved source revision and publish
      its SHA-256 checksum.
- [ ] Publish versioned documentation, source, corresponding bundled-component
      source, notices, migration notes, compatibility matrices, and known
      limitations.
- [ ] Tag and publish the first public release.

Acceptance: the published artifact, checksum, source, documentation, and tag all
refer to the same approved revision and passed release evidence.

## Coverage Audit

This rewrite consolidates the former phase checklist, feature backlog,
maintainability list, testing list, and release gates. The following themes must
remain represented until completed or explicitly deferred:

- Hosting feasibility, provider limits, path/process/port/memory capabilities.
- Configuration, blueprint/software compatibility, migration, patches, reloads,
  volumes, overrides, and resource packs.
- Process supervision, lifecycle state, concurrency, cancellation, recovery,
  reconciliation, and cleanup.
- Portable copying, reflink, Btrfs, kernel OverlayFS, fuse-overlayfs, snapshot
  helpers, benchmarks, and immutable source data.
- Velocity registration, queueing, matchmaking, transfers, commands,
  permissions, selectors, completions, logs, and diagnostics.
- External/managed lobbies, SLS-Limbo, ViaVersion, protocol compatibility, and
  degraded routing.
- Paper/vanilla/manual software, Java selection, EULA, caches, installers,
  downloads, checksums, retry, and warmup.
- Performance, resource efficiency, metrics, retention, and constrained-host
  guidance.
- Internal package/resource organization, contributor guidance, formatting,
  static analysis, CI, and test coverage.
- Public API/events/integrations, optional features, documentation, licensing,
  corresponding source, candidate testing, and release publication.

If future work removes a theme, record the scope decision and replacement or
rationale in Stage 4 rather than silently deleting it.
