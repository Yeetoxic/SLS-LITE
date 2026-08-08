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
- Treat SLS-LITE as a composable platform for operator-authored network
  experiences. Prefer stable, documented, permission-aware integration
  surfaces over hard-coded assumptions about one lobby or minigame workflow.
- Keep the SLS-LITE core focused and make optional or unusual behavior possible
  through separate trusted Velocity extensions. Extensions may expose their own
  network services, integrations, UI, automation, or intentionally unconventional
  behavior under operator control; evolve the public API additively when a real
  extension need is not expressible, without exposing mutable internals that can
  silently violate core lifecycle, ownership, accounting, or data-safety
  invariants.
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

- [x] Exercise managed lobby, separately hosted external lobby, SLS-Limbo
      fallback, queues, transfers, multiple registries, full servers, forced
      administration, recovery, and shutdown in one repeatable plan.
- [x] Verify external lobby mode never starts, stops, copies, or owns that lobby.
- [x] Verify supported native and ViaVersion-translated client ranges for the
      selected Velocity/NanoLimbo combination; do not claim new versions before
      the complete transfer path passes. Native 1.13.2-1.21.11 and complete
      1.21.5/1.21.11 automated handoffs and the stable 26.2 real-client handoff
      pass. Development 26.3 snapshots are outside the release matrix.
- [x] Document ViaBackwards and ViaRewind as optional operator choices for older
      clients and preserve native SLS-Limbo operation without ViaVersion.
- [x] Finish first-available/random pluggable blueprint selection while retaining
      existing ready-instance preference and capacity limits.
- [x] Allow operators to disable managed-lobby automatic startup only with clear
      routing and recovery behavior.
- [x] Re-run the real-client workflow across every supported protocol boundary,
      including the stable 26.2 managed lobby -> SLS-Limbo -> recovered managed
      lobby handoff.

### 3.9 Stage 3 Acceptance Gate

- [x] Back up any retained fixture evidence, fully reset the local Pterodactyl
      test allocation, and perform a clean first-time SLS-LITE installation
      using only the release candidate artifact and published instructions.
      Record every required manual step, default-config correction, unexpected
      file, failed assumption, and avoidable disk cost; then repeat from clean
      state after fixing all release-blocking onboarding defects.
- [x] Profile release-candidate performance and overall efficiency under idle,
      startup, queue/transfer, single-instance, multi-instance, recovery, and
      shutdown workloads. Review CPU, heap/RSS, child-process admission, disk
      use and churn, preparation/readiness latency, network overhead, log growth,
      and cleanup, and resolve or explicitly document material regressions.
- [x] Audit every generated configuration and software-profile default for
      environment neutrality. Identify values inherited from the local test rig
      and replace them with safe common-ground defaults or clearly marked
      operator choices. Review paths, ports, memory/process budgets, Java
      selection, forwarding and online-mode assumptions, lobby behavior,
      storage strategy, logging, timeouts, provider channels, installation
      policy, and lifecycle limits across self-hosted, Pterodactyl, container,
      Windows, and native Linux environments. Keep fixture-specific values in
      ignored test fixtures or scripts rather than shipped product defaults,
      then repeat the fresh-install workflow using only the revised defaults
      and published setup instructions.
- [x] Investigate the managed-lobby shutdown path that can report both
      `Deferring unfinished shutdown cleanup` and `Deferring late exit cleanup`
      during an otherwise successful Panel stop. Determine whether cleanup is
      genuinely late or the warning is a callback-order race, eliminate false
      console noise, and verify that real deadline overruns still leave durable
      reconciliation state and an actionable warning.
- [x] Remove dated proof-of-concept and test-evidence narratives from the
      operator and contributor documentation so those pages function solely as
      clear, current information and instructions. Preserve still-useful test
      procedures, supported compatibility claims, and performance guidance;
      move release evidence that must be retained into a dedicated test or
      release record rather than mixing it into the product documentation.
- [x] Add CI for compilation, tests, packaging, dependency checks, and the
      lightweight static rules approved during the organization work.
- [x] Run the complete automated suite, exact compatibility-corpus gate, package
      verification, storage harnesses, and documented Pterodactyl workflow.
- [x] Verify every supported command and every configuration, lifecycle,
      installation, storage, lobby, queue, transfer, recovery, and shutdown
      branch retained for the first release.
- [x] Compare SLS-LITE against the complete current SLS project, not only the
      historical Velocity-only implementation. Audit blueprint and software
      schemas, commands, configuration, APIs, lifecycle and matchmaking
      behavior, storage, installation, networking, nodes, persistence,
      observability, security, operations, and extensions. Classify every
      difference as compatible, locally adapted, intentionally outside the
      single-host scope, deferred, or a release-blocking defect; fix blockers
      and carry the resulting compatibility map into the consolidated docs.
- [x] Resolve every release-blocking full-stack defect and repeat each affected
      scenario.

Acceptance: the complete retained product and its expected failure matrix pass
without unexplained player states, leaked resources, weakened security, source
mutation, or silent instance corruption.

### 3.10 Java Extension API Release Gate

- [x] Provide the API foundation: version/capability discovery, readiness,
      immutable blueprint and instance views, asynchronous local
      start/stop/delete and matchmaking requests, queue inspection/cancellation,
      ordered lifecycle subscriptions, and an implementation-free classifier
      JAR.
- [x] Add bounded player matchmaking events for accepted queue assignment,
      transfer start/success/rejection/failure, cancellation, disconnect,
      timeout, instance failure, backend absence, and shutdown. Preserve global
      sequence ordering and exactly one terminal owner without exposing Velocity
      results or internal failures.
- [x] Add sanitized, exactly-once instance failure events with stable phase,
      category, blueprint/type, instance, and correlation data; distinguish
      post-registration runtime crashes from startup/readiness failures.
- [x] Add one bounded catalog reload event at the atomic commit/reject edge,
      exposing only scope, correlation, sanitized result, and committed change
      counts without definition identifiers or parser details.
- [x] Add deduplicated effective-lobby events covering primary and holding
      status, recovery/degradation, and the active primary/holding/none route
      without backend or process details.
- [x] Add exactly-once shared software-installation events for start,
      ready, sanitized failure, and shutdown cancellation without cache paths,
      URLs, checksums, provider logs, or duplicate waiter notifications.
- [x] Expand the bounded event model to cover reconciliation and API shutdown.
      Give each event stable immutable data,
      sequence/timestamp semantics, and a capability when support is optional.
- [x] Add redacted, bounded diagnostic views for system and lobby status,
      maintenance, installation state, host capabilities, instance statistics,
      recent log snapshots, and correlated failure summaries. Do not expose
      mutable buffers, credentials, filesystem paths, process handles, or
      implementation exceptions.
- [x] Add an extension context/registration handle that owns subscriptions and
      future callbacks, closes idempotently, and cannot leave work registered
      after extension or SLS-LITE shutdown.
- [x] Define and implement only the narrow pre-release extension hooks justified
      by a real example: namespaced annotation consumption plus bounded
      instance-ready and post-transfer actions. Keep matchmaking replacement,
      lobby providers, installers, storage/COW strategies, and process control
      as internal SPIs until separately designed and approved.
- [x] Build a reviewable example Velocity extension against only the public API
      artifact. Exercise discovery, readiness, inspection, lifecycle and player
      events, diagnostics, and extension cleanup without importing an internal
      package.
- [x] Exercise public start, READY, real-player queue/transfer, stop, delete,
      expected rejection, persistent restart, and proxy-shutdown paths through
      the example extension on the local Pterodactyl/Velocity fixture.
- [x] Add a checked API signature/binary-compatibility baseline in addition to
      reflection and documentation contract tests; verify immutable values,
      sanitized failures, callback bounds, ordering, overload, and shutdown
      races.
- [x] Publish complete warning-free Javadocs, Maven and Gradle compile-only
      examples, API and source/Javadoc artifacts, and one canonical extension
      guide; enforce their public-only boundary in CI.
- [x] Test the final developer-artifact distribution path through a GitHub
      Release. The manual API distribution smoke assembled a private draft,
      downloaded it on a separate clean runner, verified its checksums and API
      boundary, and built both Maven and Gradle example consumers successfully.
- [x] Perform a focused API security, concurrency, compatibility, and usability
      review. Mutable annotation leakage, callback/operation-message exposure,
      shutdown monitor waiting, and a diagnostics/manager lock inversion were
      fixed. The unreleased candidate remains API `1.0`; its accepted JVM
      signature, reproducible artifact checksums, and final live fixture health
      are recorded and frozen.

Acceptance: a third-party-style Velocity extension can depend only on the
published API artifacts, perform the documented safe local integrations, clean
up all owned work, survive normal failure/shutdown scenarios, and pass the live
operation matrix. The release candidate makes no claim that internal provider
SPIs, distributed SLS control, or an authenticated HTTP API are available.

### 3.11 Documentation Consolidation and Wiki Draft

- [x] Review the complete documentation set as one operator and contributor
      journey after the Stage 3.10 API contract is frozen; remove duplication
      and drift, repair navigation, establish one canonical home for each
      concept, and make installation, configuration, daily operation,
      troubleshooting, compatibility, backup/recovery, extension development,
      and contribution guidance read coherently from start to finish.
- [x] Create a basic release-candidate GitHub Wiki source set with a home page,
      sidebar/navigation, installation and first-run guide, configuration,
      commands and permissions, storage/COW, lobby and matchmaking, operations,
      troubleshooting, compatibility, Java extension development, and
      contributor entry points. Keep wiki source reviewable in the repository
      and define the publication/update workflow before copying it to GitHub.

Acceptance: the repository documentation and draft wiki present one consistent
release-candidate story after implementation is frozen, link to canonical
detail instead of diverging copies, and can be published without inventing
unsupported behavior.

## Stage 4: Release Candidate

Goal: freeze scope, produce an externally testable build, and resolve candidate
feedback without destabilizing the completed full stack.

### 4.1 Scope Review

Scope posture: freeze and qualify the implemented single-host product. Add RC
work only when this review identifies a missing release fundamental or a small
safety/usability correction; move larger feature systems to a named
post-release milestone.

- [x] Incorporate the project owner's release-candidate notes.
- [x] Review each candidate below and record one explicit result: include in the
      candidate, defer to a named post-release milestone, or remove from scope.
      Do not leave an unclassified optional feature:
  - [x] Include the existing provider/runtime boundary: verified automatic
        Paper and vanilla acquisition, operator-supplied custom software and
        Java paths, explicit EULA/terms handling, checksums, and bounded
        download/prune actions. Do not duplicate Pterodactyl runtime installers
        or execute arbitrary full-SLS installer containers/scripts.
  - [x] Retain built-in administrators and granular Velocity permission nodes
        for the RC. Defer SLS-LITE-recognized permission bundles/role aliases to
        **Post-release: Operator Experience**; do not add another user/group
        database.
  - [x] Remove blueprint conversion and a separate validation command from RC
        scope. Never rewrite operator blueprints automatically. Include
        fault-isolated reloads: validate every blueprint, publish unrelated
        valid definitions in one coherent catalog revision, report the number
        of invalid blueprints to the command sender, and record every rejected
        blueprint with its exact validation error in the detailed SLS-LITE log.
        An invalid definition is absent from the new catalog, even when an older
        version was previously valid; already-running instances retain their
        captured immutable blueprint snapshot and continue unaffected.
  - [x] Include one final stable SLS, Velocity, Paper, and Minecraft release
        review before the RC build, adopting relevant compatibility and
        security corrections. Do not make experimental upstream branches an RC
        gate. Defer per-blueprint `allowed-client-versions` enforcement to
        **Post-release: Compatibility and Routing**.
  - [x] Include a secure Velocity backend-to-proxy plugin-message channel for
        operator-authored NPC, menu, and backend integrations in the RC. Do not
        reproduce the historical Bungee `slimelabs:network` wire format or trust
        a payload-supplied player UUID. Define a versioned, bounded SLS-LITE
        protocol that verifies and authorizes the event source/carrier, supports
        typed matchmaking requests plus a bounded SLS-command relay executed as
        the carrier player, and preserves normal permissions, admission, and
        lifecycle rules. Publish the protocol and minimal sender snippets for
        the RC, but do not require or publish a companion backend plugin.
  - [x] Retain backend-driven resource packs through validated,
        version-appropriate `server.properties` fields, client-reachable
        HTTP(S), and exact hashes for the RC. Defer proxy-managed logical pack
        resolution and transfer-time apply/replace/clear to **Post-release:
        Optional Integrations**. Keep pack conversion and public hosting outside
        SLS-LITE core; never upload packs to an external service automatically.
  - [x] Include the frozen Java API 1.0 and its verified artifact-distribution
        contract in the RC without expanding the public provider SPI. Preserve
        the checked compatibility signature, fix genuine defects conservatively,
        and make future capabilities additive unless a new API major version is
        deliberately approved.
  - [x] Keep an HTTP administration/event API and network metrics exporter out
        of SLS-LITE core. The Java API is the supported extension surface;
        operators who need a web panel, bot, remote controller, or metrics
        endpoint can install or build a separate trusted Velocity extension
        with its own authentication, exposure, privacy, and lifecycle policy.
        Core SLS-LITE opens no administration listener and sends no telemetry;
        this is an example of the general core-versus-extension boundary, not a
        prohibition on operator-chosen extension behavior.
  - [x] Defer warm instance pools to **Post-release: Performance and Capacity**.
        Design them as an optional, default-off configuration capability rather
        than assuming every host is resource-constrained: operators choose which
        blueprints/pools stay warm and accept the explicitly reported idle
        memory/process cost.
  - [x] Classify operator-choice gaps found during the RC scope audit:
    - [x] Give Paper profiles an explicit build-selection policy. Preserve
          newest-allowed selection as the default, and allow an exact build to
          be pinned per Minecraft version for reproducible fresh installs.
          Resolve and verify the selected artifact with Paper's published
          SHA-256 metadata in either mode; reject unavailable or mismatched
          pins instead of silently falling back to latest.
    - [x] Make built-in transfer action-bar feedback host-configurable and
          enabled by default. Support bounded MiniMessage templates for joining,
          force-joining, and dequeue feedback plus bounded animation frames,
          colors, and frame interval. Keep this host-wide for the RC rather than
          expanding the blueprint schema. When disabled, SLS-LITE remains silent
          and does not reserve or overwrite the action bar, allowing an extension
          to provide its own transfer presentation.
    - [x] Expose SLS-Limbo's complete player-facing presentation while preserving
          its operational contract. Let operators configure bounded MiniMessage
          content and enablement for the ping description, brand, join message,
          boss bar, title/subtitle, header/footer, timings, colors/styles, and a
          supported dimension. Encode generated YAML safely. Keep bind address,
          allocated port, forwarding mode/secret, advertised protocol, capacity,
          packet/resource limits, and lifecycle controls owned by SLS-LITE.
    - [x] Add a host-level ViaVersion synchronization policy with `auto` as the
          backward-compatible default, `on` as an explicit requirement that
          reports a clear error when ViaVersion is unavailable, and `off` as a
          guarantee that SLS-LITE neither inspects nor changes ViaVersion's
          backend protocol mapping.
    - [x] Keep the current COW order and conservative copy parallelism as the
          defaults, while allowing a host to configure an authoritative ordered
          `auto` strategy allowlist and bounded `auto`/numeric copy parallelism.
          Operators may omit any strategy, including portable copy; fail with
          capability diagnostics rather than attempting an excluded fallback.
          Reject empty, duplicate, unknown, or structurally invalid policies.
    - [x] Keep a host-wide queue timeout default and allow a blueprint override
          or explicit no-expiry policy. Make console-tail size, installer-history
          count, and retained failure-report count bounded host settings, with
          zero disabling retention where safe. Keep persistent logging separate
          and retain non-configurable hard byte/payload ceilings that protect
          integrity and bounded resource use.
  - [x] Defer offline world optimization to **Post-release: World Maintenance**.
        Keep it planned as an opt-in core capability with extension strategies,
        but do not expand the frozen RC API or put world-data transformation on
        the candidate's critical path.
  - [x] Classify forced-host and multiple-lobby routing:
    - [x] Preserve Velocity's selected initial server, including native
          `forced-hosts`, and use the SLS-LITE lobby only when Velocity has not
          selected a route. Do not rewrite or duplicate `velocity.toml` routing;
          retain SLS-Limbo only as the safety path when SLS-LITE owns the managed
          lobby route and that lobby is unavailable.
    - [x] Add `lobby.mode: velocity` as the recommended clean-install standard:
          Velocity owns its initial routes, ordinary lobby set, `try` order, and
          forced hosts. Retain explicit `external` and `managed` modes without
          changing existing configured behavior. Keep SLS-Limbo enabled by
          default as SLS-LITE's holding/safety backend, but allow operators to
          disable it and disconnect cleanly when no valid route remains. Defer
          multiple SLS-managed lobby pools to **Post-release: Compatibility and
          Routing**.
  - [x] Do not support binary plugin hot-reload for the RC. Support `/sls reload`
        for configuration and catalogs, reconcile only SLS-LITE-owned dynamic
        registrations during controlled lifecycle operations, and unregister
        owned registrations on normal shutdown. Detect and diagnose stale or
        duplicate names without mutating operator/other-plugin ownership. Require
        a normal Velocity restart when replacing the SLS-LITE JAR.
  - [x] Keep public documentation latest-release-first rather than maintaining a
        historical versioned site. Repository `DOCS/` is the canonical current
        source and the GitHub Wiki mirrors the latest supported release. Git tags
        preserve old text incidentally, but do not promote a version selector or
        maintain old manuals. Publish release notes and current migration/upgrade
        instructions that direct outdated installations toward the latest release.
        Defer a separate hosted documentation site until it provides a concrete
        benefit over the repository and Wiki.
- [x] Confirm that deferred items are absent from public availability claims.
      Public guides describe current behavior and permanent product boundaries;
      forward-looking classifications remain solely in `todo.md`. Documentation
      tests reject roadmap terminology and unimplemented RC configuration keys.

### 4.2 Candidate Artifact and Documentation

- [x] Review stable SLS, Velocity, Paper, and Minecraft releases against the
      pinned compatibility/runtime matrix immediately before the RC build;
      incorporate applicable security or shared-contract corrections and record
      intentional version boundaries. Experimental upstream branches are
      informational only and cannot block the candidate.
- [x] Implement fault-isolated blueprint reloads: collect every parse and
      validation failure, prevent one invalid file from rejecting unrelated
      valid siblings, and publish the accepted definitions as one coherent
      catalog revision. Report accepted/rejected counts to the command sender
      and write every confined blueprint path plus exact validation error to the
      detailed SLS-LITE log. Keep console output concise and bounded. Remove an
      invalid definition from the newly published catalog without stopping or
      mutating instances already running from its previous immutable snapshot;
      reject new starts for that blueprint until a valid definition is loaded.
- [x] Add Paper build-selection configuration: keep newest-allowed as the
      backward-compatible default and support exact per-Minecraft-version build
      pins. Resolve and verify Paper's published SHA-256 for the selected build,
      report unavailable or mismatched pins clearly, and never silently replace
      a requested pin with a newer build. Cover parsing, selection, installation,
      cache reuse, and failure behavior with tests and operator documentation.
- [x] Add host-wide transfer action-bar configuration. Keep the current feedback
      enabled by default, accept bounded MiniMessage templates for joining,
      force-joining, and dequeue messages, and allow bounded animation frames,
      colors, and frame interval. Validate malformed or excessive presentation
      input with precise diagnostics. When disabled, send no built-in transfer
      action-bar output and leave the surface free for Java API extensions.
- [x] Add host-wide SLS-Limbo presentation configuration. Allow operators to
      replace or disable its ping description, brand, join message, boss bar,
      title/subtitle, header/footer, and other player-facing presentation; allow
      supported dimensions and bounded timing/color/style values. Treat text as
      bounded MiniMessage and serialize arbitrary valid operator text safely into
      generated YAML. Retain SLS-LITE ownership of bind address, allocated port,
      forwarding mode and secret, advertised protocol, capacity, packet/resource
      limits, and lifecycle settings so customization cannot break the limbo's
      required operational or security contract.
- [x] Add a host-level ViaVersion backend synchronization policy: `auto` keeps
      the existing detection-based behavior, `on` requires the integration and
      emits a precise configuration/reload error when ViaVersion is unavailable,
      and `off` prevents SLS-LITE from inspecting or modifying ViaVersion's
      backend protocol mapping. Document `auto` as SLS-LITE's recommended
      standard without treating that default as mandatory operator policy.
- [x] Add bounded storage policy controls. Preserve the documented automatic COW
      priority and conservative CPU-based copy parallelism as defaults; allow an
      authoritative ordered auto-strategy allowlist and `auto` or numeric portable
      copy parallelism. Operators may exclude every fallback, including portable
      copy. Reject empty, duplicate, unknown, or invalid policies, diagnose host
      capability failures per requested strategy, and never attempt an excluded
      strategy silently. Cover selection, reload, fallback, and concurrency bounds
      with tests and operator documentation.
- [x] Add bounded queue and diagnostic-retention controls. Preserve the host queue
      timeout default while allowing each blueprint to override it or explicitly
      disable expiry. Make the instance console-tail size, installer-history count,
      and retained failure-report count host-configurable, accepting zero where
      disabling retention is safe. Keep persistent per-instance/general logging
      independent of these short in-memory histories, and do not expose hard
      byte/payload safety ceilings as tuning knobs. Test defaults, overrides, zero
      retention, reload behavior, bounds, and recovery/diagnostic behavior.
- [x] Correct initial-server routing so SLS-LITE cooperates with Velocity. When
      `PlayerChooseInitialServerEvent` already contains a selected server,
      preserve it—including native forced-host routing—and do not arm an
      SLS-Limbo handoff for that unrelated route. Supply the configured SLS-LITE
      lobby only when Velocity has not selected an initial server. Keep managed
      lobby/limbo unavailability behavior bounded to routes SLS-LITE owns, never
      modify `velocity.toml`, and test forced hosts, ordinary fallback order,
      managed and external lobbies, unavailable lobbies, reconnects, and kicks.
- [x] Add `lobby.mode: velocity` and make it the recommended clean-install mode.
      In this mode, leave Velocity's initial-server choice, ordinary lobby set,
      `try` order, and forced hosts authoritative; use enabled SLS-Limbo only as
      the bounded holding/safety route when no valid Velocity route remains.
      Preserve explicit existing `external` and `managed` behavior, migrate or
      diagnose older/missing configuration without silently changing an existing
      network, and allow Limbo to be disabled with a clear disconnect when no
      route remains. Test clean install, reload, forced hosts, several Velocity
      lobbies, missing routes, Limbo on/off, and all three modes.
- [x] Formalize the supported reload boundary and registration reconciliation.
      Ensure `/sls reload` atomically reconciles only dynamic registrations owned
      by SLS-LITE, normal shutdown unregisters those owned entries after bounded
      lifecycle cleanup, and conflicting/stale names receive precise diagnostics
      without modifying registrations belonging to Velocity configuration or
      another plugin. Document that replacing the plugin JAR requires a Velocity
      restart; add repeat-reload, conflict, shutdown, and unsupported hot-reload
      detection tests without claiming binary plugin-manager reload support.
- [x] Finalize latest-release-first documentation publication. Keep repository
      `DOCS/` canonical, synchronize the GitHub Wiki to the latest supported
      release, and publish current release notes plus tested migration/upgrade
      instructions. Do not create or advertise a maintained historical manual or
      version selector; leave old text available through Git history/tags only and
      direct unsupported old installations toward upgrading. Add a release check
      that prevents the Wiki/current docs from claiming a different supported
      release than the candidate artifact.
- [x] Implement and document the secure SLS-LITE backend messaging channel for
      operator-authored NPC, menu, and backend integrations. Accept messages
      only from a source-verified, explicitly authorized server connection and
      bind the request to its actual carrier player. Use a versioned,
      size-bounded schema; validate action and target identifiers; apply
      per-source/player rate limits; and reject client-originated, spoofed, or
      malformed messages. Provide typed matchmaking through the normal
      admission path and an opt-in bounded SLS-command relay that executes only
      as the carrier player through the ordinary command/permission contract.
      Allow operators to restrict permitted actions/command roots per source;
      never provide arbitrary console execution, identity override, or a bypass
      around permissions and lifecycle rules. Use a dedicated SLS-LITE channel,
      mark matching messages handled before source parsing, and do not listen to
      legacy or general command-forwarder channels by default. Deduplicate
      request IDs in a bounded expiring cache so retransmission or overlapping
      integrations cannot execute one request twice. Publish the protocol and a
      maintained minimal backend sender example; allow other plugins to
      implement that protocol without depending on the example. Treat any
      legacy/general-forwarder adapter as a separate explicit opt-in and do not
      claim automatic compatibility with `slimelabs:network`, `sls:vsls`, or
      `bungeecord:main` payloads. Document that general command-forwarding
      plugins remain usable by dispatching `/sls` normally through Velocity;
      do not duplicate their broader proxy-command role in the RC.
      Document that managed Paper lobbies are backend child processes, not code
      running inside the Velocity JVM: player-issued proxy commands work
      normally, while an NPC/menu plugin executing server-side still needs this
      channel or a general command forwarder, just like an external lobby.
- [x] Evolve the proven Stage 3.10 API distribution smoke into one reusable
      Build Release workflow with explicit `distribution-smoke`,
      `release-candidate`, and `release` modes. Reuse the clean-runner download,
      checksum, artifact-boundary, and Maven/Gradle consumer verification in
      every mode; keep smoke releases private drafts, require an approved
      environment before publishing a candidate or final release, and prevent
      a mode or rerun from silently promoting an artifact.
- [x] Produce one versioned plugin JAR and one canonical commented configuration.
- [x] Publish installation, update, backup, uninstall, host-capability, runtime,
      storage, compatibility, command, lifecycle, recovery, and troubleshooting
      documentation.
- [x] Publish a release compatibility matrix for Velocity, Java, Paper,
      Minecraft protocols, bundled NanoLimbo-derived runtime, and optional
      ViaVersion.
- [x] Label shared material as `SLS and SLS-LITE`, `Full SLS only`,
      `SLS-LITE only`, or `Adapted for local mode`; keep shared text in one place
      where practical.
- [x] Complete the documentation drift, security, privacy, dependency, source,
      license, third-party notice, and migration audits.
- [x] Mirror the exact corresponding bundled NanoLimbo-derived source in an
      SLS-LITE-controlled release location.
- [x] Verify the shaded JAR contains all required licenses/notices and no
      unfinished feature is documented as available.
- [x] Perform clean-install and upgrade tests on supported Windows/Pterodactyl
      and native-Linux profiles.
- [x] Publish the candidate artifact and checksums to the selected external
      testers.

### 4.3 Candidate Feedback and `v0.1.0-rc.2`

- [ ] Triage external findings as release-blocking, scheduled follow-up, or
      rejected with rationale.
- [ ] Preserve published `v0.1.0-rc.1` as the immutable first-candidate
      baseline. Implement the accepted Stage 4.3 tester feedback below for
      `v0.1.0-rc.2`, update versioned artifacts and release notes only after the
      batch passes its required regression and clean-install checks, and publish
      it through the approved release-candidate workflow with matching source
      and checksums.
- [ ] Continue the same immutable candidate cycle for `rc.3`, `rc.4`, and later
      candidates whenever additional accepted feedback or blockers require
      changes. Give each candidate a bounded documented scope, preserve every
      prior tag/artifact/checksum, repeat affected regression and clean-install
      evidence, and return it to testers. Advance to Stage 5 only after the
      current candidate has no known release blockers and the maintainer
      explicitly approves promotion; `rc.2` is not presumed final.
- [ ] Add the tester-requested host-wide `software.auto_accept_eula` convenience
      setting to `config.yml`, defaulting to `false`. Treat `true` as the
      operator's explicit acceptance for automatic Paper/vanilla installation
      across profiles while retaining the existing per-profile
      `software.accept_eula` opt-in; either explicit choice may satisfy the
      installation gate. Never infer acceptance, change generated defaults to
      true, alter manual software, or download software before an effective
      opt-in. Document the precedence and operator responsibility, preserve
      upgrades that omit the new key, and test false/default, global true,
      profile true, malformed values, reload/restart, cache reuse, warmup, and
      generated `eula.txt` behavior.
- [ ] Add a non-destructive host-configuration evolution contract before
      publishing `rc.2`. Give newly generated configurations an explicit
      `config_version`; treat an unversioned `rc.1` file as the documented legacy
      version. Never overwrite, reserialize, reorder, or remove an operator's
      `config.yml`. Continue applying safe code defaults for omitted optional
      keys (including `software.auto_accept_eula: false`), and fail with an exact
      migration instruction rather than guessing whenever a future change has
      no safe default. Ship each changed canonical configuration as a bounded,
      versioned, plugin-owned reference file without copying secrets, and report
      the old/current versions, newly available keys, effective defaults, and
      reference path once in the compact startup checklist. Allow operators to
      merge and acknowledge the new version deliberately. For renamed fields,
      provide a documented bounded compatibility alias when safe and reject
      configurations containing conflicting old and new forms. Test untouched
      `rc.1` upgrades, customized comments/order, missing and current versions,
      malformed/future versions, reference-file collision and symlink handling,
      atomic reference installation, deprecated aliases, conflicts, and fresh
      `rc.2` generation.
- [ ] Replace the scattered first-run forwarding instructions with one
      copy-and-paste onboarding path for a real Velocity/Paper network and one
      explicitly insecure isolated-development path. Show the matching
      `velocity.toml` and SLS-LITE `config.yml` fragments, exact secret-file
      location and permissions, matching online-mode values, required full
      restart, and the Paper-versus-vanilla boundary. Explain that SLS-LITE runs
      inside Velocity, dynamically registers managed backends, patches managed
      Paper instances, and does not require users to add each managed instance
      to `[servers]`; separately show when velocity-owned or external lobby
      entries and `try`/forced-host routes are required. Add a short decision
      table for `velocity`, `external`, and `managed` lobby modes, startup-log
      and `/sls system` checks, a mandatory real-client join/transfer test, and
      symptom-based fixes for mismatched modes, secrets, online mode, unreachable
      example servers, and players stranded in SLS-Limbo. Make README and the
      generated config point directly to this single canonical setup section,
      then validate it with a clean tester-style installation that begins with
      an unmodified Velocity configuration.
- [ ] Add a copyable blueprint recipe book that teaches mappings visually before
      presenting the full schema. For every recipe, show the source directory
      tree, a complete valid YAML fragment or minimal blueprint, and the
      resulting instance tree, with callouts explaining that `source` is below
      the SLS-LITE data directory while `target` is inside each prepared server.
      Include disposable and persistent worlds, one plugin JAR, a shared plugin
      bundle, multiple bundles merged into `plugins/`, a seeded
      `whitelist.json`, configuration patches, same-target COW precedence, a
      private `ro` snapshot, the explicitly single-instance shared-directory
      `rw` case, and importing a complete existing Paper server as a manually
      prepared `server.path` base. For the import recipe, show required JAR/profile
      agreement, `save: true`, SLS-LITE-owned networking overrides, source versus
      live-instance ownership, files operators may wish to clean first, and the
      destructive consequence of reset without mutating the original template.
      Explain why a root-target volume is not the import mechanism. Add a concise
      `state.volumes` versus `state.copy` versus `server.path` decision table and
      make directory-only volumes, file-capable copies, root-target limits,
      save/reset consequences, source immutability, collision precedence, and
      trailing-slash behavior unmistakable. Keep examples free of test-rig paths
      and version assumptions where possible, link them from README, the
      blueprint template, and Getting Started, and automatically parse every
      published YAML example or assemble it into a valid fixture so copied
      documentation cannot drift from the accepted schema.
- [ ] Perform a beginner-accessibility pass over installation and blueprint
      documentation so operators do not need storage or container expertise to
      begin. Lead with the mental model: SLS-LITE is an assembly line for
      servers, and a blueprint is the build sheet describing how each instance
      is assembled. Introduce `source` as the component's supply location,
      `target` as its location in the finished server, `copy` as placing a fresh
      copy, `cow` as a private writable copy, `rw` as deliberately shared live
      storage, and `save` as retaining the assembled instance. Organize the path
      as plain-language concepts, goal-based recipes, visual before/after trees,
      verification, troubleshooting, and only then the full schema and advanced
      COW/provider internals. Define unavoidable jargon on first use, separate
      safe defaults from advanced choices, provide clear next actions after
      errors, and usability-test the path with someone unfamiliar with SLS-LITE
      before calling the candidate documentation complete.
- [ ] Add one compact, bounded startup setup checklist that distinguishes
      release blockers, actions needed before a configured feature can work,
      and valid but development-oriented choices. Cover plugin initialization,
      forwarding/online-mode agreement, forwarding-secret readiness without
      exposing it, lobby/SLS-Limbo routing, loaded and rejected blueprints,
      zero-blueprint next steps, software EULA gates that are actually relevant
      to loaded definitions, required Java runtimes, managed process/memory/port
      admission, and the selected storage fallback. End with an unambiguous
      ready/action-needed summary and one canonical setup-document link; keep
      full probe detail in the detail log and `/sls system` so startup does not
      become another wall of text. Test clean production, isolated development,
      incomplete first-run, malformed definition, offline optional provider,
      and restricted Pterodactyl configurations.
- [ ] Make the change-application model explicit wherever operators edit or
      reload definitions. Document and report that blueprint/software reloads
      affect future assembly, persistent restart reuses the existing instance,
      reset rebuilds it from the current base/volumes/copies, `state.copy`
      changes therefore require reset, and `config.yml` requires a Velocity
      restart. After a successful definition reload, provide a concise next
      action when changed definitions have persistent or running instances,
      without touching those instances automatically. Add regression tests for
      plugin-JAR replacement, source-world changes, running-instance isolation,
      persistent restart, reset, rejected sibling definitions, and restart-only
      host configuration.
- [ ] Reopen candidate scope for safe per-file persistent state and publish the
      result as a new release candidate rather than changing `rc.1`. Audit full
      SLS file-shaped volume behavior, then design and implement root-file state
      for files such as `whitelist.json`, `ops.json`, ban lists, and server
      icons. Keep `state.copy` as the existing private/COW-like file seed and
      avoid duplicating its semantics; optimize eligible copies with reflinks
      only when behavior remains identical. Do not implement writable file
      volumes as naive symbolic links or privileged-only bind mounts:
      applications may atomically replace files, hosts may reject mounts, and
      either behavior can silently break persistence. Define an explicit bounded
      import/write-back contract with single-writer ownership, atomic
      publication, canonical-file backups, size and path confinement, symlink
      rejection, reset/delete behavior, conflict detection, crash
      reconciliation, and actionable diagnostics. Test real Paper whitelist,
      operator, and ban-list command saves plus interrupted writes, restarts,
      resets, concurrent access rejection, Windows, native Linux, and restricted
      Pterodactyl hosts before freezing and documenting the stable blueprint
      field. Repeat the affected schema, migration, storage, lifecycle,
      reconciliation, backup-boundary, documentation, API-compatibility, clean
      installation, upgrade, distribution-smoke, and real-client checks for the
      replacement candidate.
- [ ] Fix every candidate blocker and repeat its automated, storage, lifecycle,
      protocol, and real-client scenarios.

Acceptance: external testers receive reproducible, documented
`v0.1.0-rc.2` artifacts whose known limitations and compatibility boundaries
match observed behavior, with `v0.1.0-rc.1` retained as the prior baseline.

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

## Post-release: Operator Experience

- [ ] Design stable permission bundles/role aliases over the existing granular
      Velocity nodes (for example inspection, matchmaking, lifecycle, and
      software administration). Keep membership in the operator's permission
      provider, retain the built-in SLS-LITE administrator, and define child
      grant/deny precedence before implementation.
- [ ] Evaluate a guided existing-server import assistant that inspects an
      operator-selected directory and generates a draft manual software profile,
      blueprint, and compatibility report. Detect likely server software/JAR and
      version only when evidence is unambiguous; report hardcoded ports, bind and
      forwarding settings, absolute paths, plugin databases, caches/logs, world
      layout, EULA responsibility, persistence, and reset consequences. Confine
      all reads, bound traversal and output, reject symbolic-link escapes and
      special files, never execute imported content, and never move, delete,
      rewrite, clean, or adopt the original server automatically. Require the
      operator to review and activate the generated drafts explicitly.

## Post-release: Backup and Portability

- [ ] Design and implement explicit backup/export and restore/import operations
      for SLS-LITE-owned persistent instances. Define stopped-versus-live
      guarantees, Minecraft flush requirements, OverlayFS/FUSE suspension,
      snapshot-hook coordination, atomic publication, checksums, format/version
      metadata, free-space and size bounds, cancellation, partial-output cleanup,
      conflict handling, and crash recovery. Treat shared `rw` directories as
      separately owned external state: never traverse or silently include them,
      list every exclusion, and require an explicit separately confined backup
      choice if support is added. Restore must stage and verify into a new owned
      destination before any replacement, preserve the original on failure, and
      require explicit confirmation before replacing an existing instance.

## Post-release: Storage Portability

- [ ] Remove GNU `cp` as the only Linux reflink interface by evaluating a small,
      verified native helper or binding for the `FICLONE` operation. Continue to
      require an exact-path clone, mutation-isolation, allocated-size, and
      cleanup probe; bundle architecture-specific code only with checksums,
      notices, corresponding source, and a maintained security-update path.
      This may recover reflinks on minimal images with a capable filesystem but
      must never report reflink support where the backing filesystem rejects it.
- [ ] Evaluate an internal Btrfs seed-subvolume cache so ordinary eligible
      source directories can be imported once and subsequent instances created
      as snapshots. Define strong source identity and invalidation, bounded
      staging/publication, nested-subvolume rejection, immutable-seed
      protection, reference-safe cleanup, crash reconciliation, and disk-space
      accounting. Use it only when the exact workspace is Btrfs and contained
      subvolume create/snapshot/delete probes succeed.
- [ ] Prototype rootless kernel OverlayFS in an isolated user and mount
      namespace only on hosts that explicitly permit it. Prove that the managed
      Minecraft process shares the mount namespace, the source remains
      isolated, persistent instances can be resumed or recovered, and shutdown
      cannot strand mounts or namespace owners. Do not request host capability,
      seccomp, AppArmor, or user-namespace changes; reject the approach if it
      cannot fit the existing bounded supervision and reconciliation model.
- [ ] Evaluate a verified bundled `fuse-overlayfs` fallback for hosts that expose
      a usable `/dev/fuse` but do not install the executable. First collect
      capability diagnostics from real hosts to distinguish missing binaries
      from unavailable devices, permissions, user namespaces, or mount helpers.
      Prefer a compatible system binary, then consider pinned upstream static
      builds for supported architectures with checksums, licenses,
      corresponding source, no-follow extraction, and security-update handling.
      Retain the full mount/isolation/unmount probe and never create `/dev/fuse`,
      add capabilities, alter seccomp, or weaken host security. Test native Linux
      and restricted Pterodactyl hosts on x86-64 and ARM64, compare performance
      with portable copy, and proceed only if the experiment unlocks meaningful
      provider coverage without making release maintenance or supply-chain risk
      disproportionate.
- [ ] Publish a reference provider integration for the existing bounded
      `sls-snapshot-helper-v1` protocol, demonstrating how a trusted host-side
      component can expose OverlayFS, Btrfs, ZFS, LVM-thin, or a provider
      snapshot API without granting the Velocity JVM storage privileges. Keep
      installation explicit and provider-controlled, authenticate and confine
      the boundary, preserve idempotent lifecycle operations, and do not bundle
      or auto-start a privileged daemon with SLS-LITE.

## Post-release: Compatibility and Routing

- [ ] Design optional per-blueprint `allowed-client-versions` admission using
      explicit protocol ranges, ViaVersion-aware capability checks, actionable
      rejection messages, and tests that never imply support from translation
      plugin detection alone.
- [ ] Design multiple SLS-managed lobby pools with stable identities,
      health/capacity-aware selection, bounded startup and recovery, safe cycling,
      deterministic SLS-Limbo handoff, and explicit interaction with Velocity's
      native forced-host and fallback routing. Do not replace ordinary Velocity
      lobby pools that need no SLS-LITE lifecycle ownership.

## Post-release: Optional Integrations

- [ ] Design optional proxy-managed resource-pack switching with validated
      logical IDs, client-reachable HTTP(S), exact hashes, version-aware
      prompt/required behavior, and deterministic apply/replace/clear semantics
      across transfers. Integrate with operator-selected hosting/conversion
      services without making SLS-LITE a pack host or automatically uploading
      operator data.
- [ ] Evaluate an optional official Paper/Spigot SLS-LITE bridge JAR built on
      the versioned backend messaging protocol. Keep it independent from the
      core plugin, support general command forwarders through normal Velocity
      dispatch, and either license/rework the historical SL-JoinForward project
      or implement the companion cleanly under the current project license.

## Post-release: Performance and Capacity

- [ ] Add default-off configurable warm instance pools with per-blueprint or
      matchmaking-pool minimum/maximum ready counts, strict memory/process/port
      reservations, bounded asynchronous replenishment, startup backoff,
      maintenance/shutdown integration, crash reconciliation, idle-cost
      diagnostics, and hard protection against unbounded start loops. Preserve
      ordinary on-demand behavior when disabled.
- [ ] Profile and improve the universal portable-copy path without weakening
      isolation: preserve sparse allocation where supported, tune bounded
      parallelism by measured workload, avoid safely reusable scans, perform
      preflight space admission, report logical versus allocated bytes, and
      evaluate post-copy extent deduplication only after an exact-path
      isolation probe. Never substitute hard links, writable symbolic links,
      race-prone file watching, or process-interposition tricks for COW.

## Post-release: World Maintenance

- [ ] Implement opt-in offline world optimization. First establish and document
      the exact full-SLS behavior being retained, the built-in transformation,
      supported world formats/versions, and measured benefit. Default the feature
      off; support host policy and per-blueprint enable/strategy overrides. Never
      rewrite a source world in place: provide a dry run, build into confined
      separate output, verify it, and atomically select it for subsequent instance
      preparation. Bound CPU, memory, I/O, output size, concurrency, and duration;
      support cancellation, crash reconciliation, stale-output cleanup, and an
      explicit safe reset path. Refuse unsafe active-world operations and report
      partial or unsupported data precisely. Add a narrow namespaced Java API
      strategy registration for trusted extensions, with capability metadata,
      lifecycle cancellation, bounded progress/diagnostics, deterministic conflict
      handling, and no exposure of mutable core lifecycle internals. Test corrupt
      input, interrupted work, extension failure, cache identity/invalidation,
      atomic publication, COW interaction, reload, shutdown, and recovery on the
      local Linux/WSL fixture. Document which results affect disk, I/O, startup,
      or runtime memory rather than promising generic memory savings.

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
