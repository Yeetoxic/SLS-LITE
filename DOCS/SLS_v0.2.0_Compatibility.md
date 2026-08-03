# SLS v0.2.0 Compatibility Matrix

This is the pinned modern SLS source contract for SLS-LITE.

- Upstream repository: `https://github.com/jessefaler/SLS`
- Release/tag: `v0.2.0`
- Commit: `8e8b1e3cf7d2157887764c16f11b8901f8241121`
- Upstream license: GNU AGPL v3.0

The matrix describes SLS-LITE's contract with the pinned source models,
parsers, bundled examples, software definitions, and vSLS implementation.

## Status Terms

- **Supported:** same field and materially equivalent behavior.
- **Adapted:** accepted directly, with documented single-host behavior.
- **Intentionally unsupported:** distributed or unsafe behavior that does not
  belong in SLS-LITE.
- **Deferred:** useful local behavior that still needs implementation or tests.
- **SLS-LITE extension:** local-only behavior outside the shared SLS contract.

## Compatibility And Scope Review

The compatibility scope is derived from:

- the pinned SLS `v0.2.0` source and bundled examples;
- the official blueprint, software, and vSLS command documentation;
- the copied 54-blueprint SlimeLabs corpus;
- the current SLS-LITE parser, command, lifecycle, storage, and process code.

As of 2026-08-03, upstream `main` and tag `v0.2.0` resolve to this same commit.
The pin therefore represents both the reproducible release contract and the
current upstream source tree at the time of the Stage 3 acceptance audit.

### Resolved Findings

The compatibility pass closed five shared behavior gaps and one fixture defect:

1. Modern software `limits.memory_limit` now supplies the reservation when a
   blueprint omits `server.limits.memory_limit`.
2. Modern software mappings now select a `java_<major>` image key from the game
   version when a blueprint omits `server.image`.
3. Bounded `annotations.vsls.on-join` commands run once after each successful
   managed-backend transition with `{PLAYER_NAME}` substitution.
4. `annotations.vsls.matchmaking.gameType` now forms local pools without
   replacing `blueprint.type` as the operator registry.
5. Arbitrary annotation trees now retain YAML null values in immutable models.
6. The copied corpus now excludes the template, includes
   `adventures/temple_of_doom.yaml`, and is checked against an explicit
   54-blueprint ID manifest. The owner's original source directory was not
   changed.

### Scope Decision

| Area | Compatibility decision |
| --- | --- |
| Blueprint metadata, registries, software/version, config patches, state declarations, persistence, and vSLS lifecycle/capacity annotations | Shared contract; preserve names and operator intent. |
| Software memory defaults and version-to-Java mappings | Implemented local adaptations, covered by parser and atomic-reload tests. |
| `vsls.on-join` and `vsls.matchmaking.gameType` | Implemented vSLS adaptations, covered by transition and pool tests. |
| Container CPU, swap, I/O, disk, affinity, and OOM enforcement | Preserve as visible metadata; do not claim enforcement by a portable child JVM. |
| Docker images | Treat `java_<major>` as a local Java selector; reject other image semantics at launch. |
| Host mounts | Intentionally unsupported because arbitrary host paths bypass the plugin data boundary and cannot be made portable across managed hosts. |
| Shared `rw` volumes | Implemented local adaptation using a verified directory link to a source confined below the SLS-LITE data root. The shared-write and concurrency risk is explicit. |
| Nodes, load balancing, Protocube API, daemon event stream, and container administration | Intentionally unsupported; these define full SLS rather than SLS-LITE. |
| Public Java integration contract | Implemented as a separate versioned API-only artifact with immutable views, bounded lifecycle events, and asynchronous safe-local requests. |
| Resource-pack conversion | Intentionally separate from SLS-LITE. Preserve annotations and support normal Minecraft URLs; conversion belongs in SlimePacks or another pack service. |
| Resource-pack discovery/serving for local test worlds | Useful local integration, but not part of the shared SLS blueprint contract. |
| SLS-Limbo, built-in administrator claims, local lobby recovery, provider-backed Paper/vanilla installation, and bounded local logs | Retained SLS-LITE extensions needed for a self-contained constrained-host product. |
| True filesystem COW | Reflink, eligible Btrfs snapshots, kernel OverlayFS, rootless fuse-overlayfs, and an explicit snapshot-helper protocol are implemented with exact-path probes, durable lifecycle metadata, reconciliation, and safe fallback rules. |
| `create`, `delete`, `kill`, `blueprint`, and `debug` | Implemented with safe local semantics, granular permissions, bounded diagnostics, and explicit rejection of daemon-only modifiers. |
| `pause` and `resume` | Command shapes are retained and explain that portable process suspension is unavailable in local mode. |
| `node` | Keep the explicit local-mode response; never emulate distributed node control. |

No existing SLS-LITE subsystem is currently a removal candidate. The largest
local extensions all replace infrastructure that budget-host users otherwise
need to purchase or install. New distributed APIs, container emulation, and
resource-pack conversion would exceed the product boundary and should remain
out of scope.

## Blueprint Schema

| SLS v0.2.0 field | Status | SLS-LITE behavior |
| --- | --- | --- |
| `blueprint.id` | Adapted | Global stable ID restricted to the portable slug `[a-z0-9][a-z0-9_-]{0,63}` for filesystem, process, and Velocity registration safety. |
| `blueprint.name` | Supported | Display name. |
| `blueprint.type` | Supported | Dynamic registry used by commands. |
| `server.software` | Supported | References a local software profile. |
| `server.version` | Supported | Exact version; providers never substitute another game version. |
| `server.image` | Adapted | `java_<major>` selects a configured local Java executable; other Docker selectors fail at launch. |
| `server.path` | Adapted | Resolves below the local software cache as a manually prepared base and bypasses provider installation. |
| `server.limits.memory_limit` | Adapted | Local memory reservation and JVM limit, not container enforcement. |
| `server.limits.swap` | Metadata only | Validated but not enforced without a container/host boundary. |
| `server.limits.io_weight` | Metadata only | Validated but not enforced without a container/host boundary. |
| `server.limits.cpu_limit` | Metadata only | Validated but not enforced on portable child JVMs. |
| `server.limits.disk_space` | Metadata only | Validated; diagnostics/admission remain deferred. |
| `server.limits.threads` | Metadata only | Validated but CPU affinity is not applied. |
| `server.limits.oom_disabled` | Metadata only | Validated but host/container OOM policy is not controlled. |
| `server.configs.server.properties` with `parser: properties` | Supported | Applied atomically to the private instance. |
| Other `properties` targets | Deferred | Parser exists conceptually; safe generic target handling is not implemented. |
| `parser: yaml` | Adapted | Nested map patches for contained `.yml`/`.yaml` files merge recursively and write atomically. |
| `parser: file` | Adapted | Contained UTF-8 line-prefix replacement with an 8 MiB limit and atomic target swap. Missing targets become empty files; absent prefixes are not inserted. |
| `state.volumes` mapping form with `mode: cow` | Adapted | Portable transactional private copy. |
| Volume shorthand `name:source:target[:mode]` | Supported | Omitted mode defaults to `cow`. |
| Multiple `cow` volumes targeting one directory | Adapted | Deterministic declaration-order merge; first source wins collisions, matching SLS v0.2.0 OverlayFS lower-layer precedence. |
| `mode: ro` | Adapted | Private writable instance snapshot protects the source; not a strict read-only bind mount. |
| `mode: rw` | Adapted | Creates and verifies a directory link to a source confined below the SLS-LITE data root. The source outlives instances and is shared concurrently; operators should normally combine it with a single-instance policy. |
| `state.mounts` | Intentionally unsupported | Reload fails with a local-mode explanation and recommends a contained `cow` or `ro` volume. |
| `state.copy` mapping and shorthand | Adapted | Transactional contained file/directory copy after software and volumes. Sources must be relative to the SLS-LITE data root; full-SLS absolute/allowed-host sources are intentionally rejected. Persistent instances refresh sources on reset rather than every restart. |
| `state.env` | Adapted | Validated strings reach the local child process; JVM, loader, path, and SLS-owned variables are rejected. Names are visible to operators, values are not logged. |
| `save` | Supported | Persistent instance directory and identity. |
| `annotations` | Adapted | Unknown trees, including YAML null values, are retained as immutable metadata. |

SLS-LITE extensions under `server.limits` are `max_players` and
`max_instances`. Modern vSLS expresses those intentions under
`annotations.vsls`; the annotation form takes effect when the local extension
is omitted.

## vSLS Annotations

| SLS v0.2.0 annotation | Status | SLS-LITE behavior |
| --- | --- | --- |
| `annotations.vsls.dont-stop-when-empty` | Supported | Excludes the blueprint from idle cleanup. |
| `annotations.vsls.max-instances` | Supported | Supplies the instance cap when local `server.limits.max_instances` is omitted. |
| `annotations.vsls.matchmaking.maxPlayers` | Supported | Supplies per-instance matchmaking capacity when local `server.limits.max_players` is omitted. |
| `annotations.vsls.matchmaking.gameType` | Adapted | Groups blueprints into a local matchmaking pool while retaining blueprint type as the operator registry. |
| `annotations.vsls.on-join[].run` | Adapted | Runs at most 32 single-line backend-console commands after a managed-backend transition, with validated `{PLAYER_NAME}`/`{PLAYER_UUID}` substitution and disconnect cleanup. |

When neither local limits nor vSLS annotations provide capacity, SLS-LITE keeps
its constrained-host defaults of 20 players and one instance. Full vSLS uses
effectively unlimited matchmaking defaults. This is an intentional local safety
adaptation and must remain visible in documentation.

## Software Definitions

Modern SLS software YAML is directly recognized through a constrained local
adapter.

| SLS v0.2.0 field | Status | Local interpretation |
| --- | --- | --- |
| `software.id` | Supported | Local profile ID. |
| `software.name` | Supported | Preserved display metadata. |
| `images` | Adapted | `java_<major>` image keys can select operator-configured local Java executables; imported Docker image references do not supply local binaries. |
| `mappings` | Adapted | The first matching constraint, or `default`, selects a validated image key when the blueprint omits `server.image`. |
| `invocation` | Adapted | A shell-free `java ... -jar <relative-file>` command is tokenized; shell syntax is rejected and heap limits are localized. |
| `stop-command` | Supported | Local graceful shutdown command. |
| `online-signal` | Adapted | Treated as a literal readiness substring. |
| `install-script` | Intentionally not executed directly | Structure is validated; known Paper/vanilla IDs map to verified providers and other IDs remain manual. |
| software `limits.memory_limit` | Adapted | Supplies the blueprint JVM limit and local admission reservation when the blueprint omits it. |
| Other software `limits` | Metadata only | Validated but container-only defaults cannot be enforced by portable child JVMs. |
| software `configs.server.properties` | Adapted | Defaults merge before blueprint patches and proxy-owned network values. |
| Other software config parsers/targets | Deferred | Rejected until a contained structured patcher exists. |
| remote `update` | Intentionally unsupported | Metadata is validated; definitions remain operator-controlled and pinned. |

SLS-LITE's provider-backed Paper/vanilla fields and manual profile schema are
local extensions. An imported definition does not imply Minecraft EULA
acceptance. Modern image mappings select a Java major; the operator still
configures the executable for that major locally.

## Runtime And Integrations

| SLS feature | Status | Reason |
| --- | --- | --- |
| Composite server IDs | Supported | `<blueprint>.<short-id>`. |
| Dynamic registries | Supported | Local catalog by blueprint type. |
| Ready-instance matchmaking | Supported | Local capacity-aware selection. |
| Horizontal node allocation | Intentionally unsupported | SLS-LITE is one host. |
| Docker isolation and limits | Intentionally unsupported | Local Java children and admission accounting. |
| Protocube HTTP API | Intentionally unsupported | No central controller in local mode. |
| Daemon event stream | Adapted local equivalent | The Java API publishes ordered, bounded managed-instance lifecycle transitions. It is not a distributed daemon stream. |
| True overlay COW | Implemented on eligible Linux hosts | Exact-path probing, durable private layers, safe remount/unmount, reset/delete handling, and crash reconciliation are implemented. |
| vSLS command surface | Partial/adapted | See `SLS_Command_Compatibility.md`. |
| `resource_pack` annotation | Metadata only | Public serving and transfer orchestration are deferred. |
| SlimePacks conversion | Intentionally separate | SLS-LITE should integrate with a pack service, not duplicate conversion. |

## Host Configuration

SLS-LITE does not attempt to load Protocube or daemon host configuration. Those
files describe distributed endpoints, databases, Docker, node registration,
allowed host mounts, and controller storage. Treating them as portable plugin
configuration would create false compatibility.

The local `config.yml` is an SLS-LITE extension covering one-host concerns:
managed memory admission, process count, loopback ports, queue/idle timing,
Velocity forwarding, administrators, managed output, lobby selection, and
SLS-Limbo recovery. Blueprint and software definitions remain the shared
configuration language; host topology does not.

## Commands And Permissions

The pinned vSLS root names remain the command vocabulary. Compatibility is
split deliberately:

- The compatibility contract covers blueprint/software language and runtime
  intent, not every administrative command.
- Full-stack validation must verify every supported command branch, selector,
  permission, completion, and message.
- Distributed-only `node` behavior remains unavailable in local mode.
- SLS-LITE additions such as `admin`, `registries`, `blueprints`, granular
  lifecycle permissions, `--force` lobby protection, and install diagnostics
  are additive and do not replace upstream forms.

Commands currently implemented with local semantics are `info`, `list`,
`create`, `start`, `join`, `find`, `system`, `console`, `blueprint`, `debug`,
`delete`, `logs`, `reload`, `stop`, `kill`, `dequeue`, `status`, `stats`, and
`version`. SLS-LITE also supplies local `restart`, `reset`, `install`,
`registries`, `blueprints`, `admin`, `maintenance`, and `join-test` operations.
The detailed argument-level contract is in `SLS_Command_Compatibility.md`.

The upstream `create --env=KEY=value` modifier is deliberately recognized but
rejected: persisting arbitrary secret-bearing command input would create a
different security contract from reviewed blueprint `state.env`. Node,
container-image, and container-resource modifiers are likewise recognized and
return explicit local-mode errors rather than pretending to enforce them.

## Acceptance Boundary

The parser, inheritance, mappings, annotation behavior, actionable rejection,
exact-ID corpus, command contract, native storage backends, and deployed
multi-world gates are complete. Reviews identified and resolved correctness
gaps including:

1. overlapping `parser: file` prefixes are rejected before file preparation;
2. interpreted vSLS lifecycle, capacity, and matchmaking annotations enforce
   their documented types and positive integer ranges;
3. host preflight requires Java runtimes selected by active resolved
   blueprints while treating other configured runtimes as optional warnings.

Network APIs and distributed control-plane behavior remain outside the first
single-host release. The separate versioned Java extension API is supported;
it does not emulate Protocube, daemon, or S4J endpoints.

## Fields Not In The Pin

`allowed-client-versions` does not appear in the SLS `v0.2.0` source, models,
examples, or vSLS implementation. It is treated as announced/deferred work and
will be reconsidered only after an upstream schema and behavior stabilize.

## Verification Requirements

Changes to this compatibility contract must keep attributed accepted/rejected
fixtures, exact-ID corpus loading, upstream example coverage, and automated
tests for every supported or adapted row. Parser-only acceptance does not prove
runtime volume sources, Java selection, process launch, or player transfer;
those remain separate integration gates described in [Testing](Testing.md).
The complete current-project classification is summarized in
[Compatibility](Compatibility.md); dated acceptance results remain outside the
operator documentation under `RELEASE_EVIDENCE/`.
