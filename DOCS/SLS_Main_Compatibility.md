# SLS Main Compatibility Matrix

[Documentation home](README.md)

This is the maintained upstream SLS source contract for SLS-LITE.

- Upstream repository: `https://github.com/jessefaler/SLS`
- Tracked branch: `main`
- Upstream license: GNU AGPL v3.0

The matrix describes SLS-LITE's contract with the reviewed source models,
parsers, bundled examples, software definitions, and vSLS implementation on
`main`.

## Status Terms

- **Supported:** same field and materially equivalent behavior.
- **Adapted:** accepted directly, with documented single-host behavior.
- **Intentionally unsupported:** distributed or unsafe behavior that does not
  belong in SLS-LITE.
- **SLS-LITE extension:** local-only behavior outside the shared SLS contract.

## Scope Boundary

The upstream `main` branch defines the moving comparison target. Reviewed local
fixtures make ordinary builds reproducible; scheduled or manual audits identify
upstream drift before those fixtures and this matrix are updated. The table
below describes current behavior; implementation and test history belongs in
release records rather than this operator-facing matrix.

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

The local extensions replace infrastructure that constrained-host users would
otherwise need to purchase or install. Distributed APIs, container emulation,
and resource-pack conversion exceed the SLS-LITE product boundary and remain
out of scope.

## Blueprint Schema

| SLS main field | Status | SLS-LITE behavior |
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
| `server.limits.disk_space` | Metadata only | Validated but not enforced as a local admission or filesystem quota. |
| `server.limits.threads` | Metadata only | Validated but CPU affinity is not applied. |
| `server.limits.oom_disabled` | Metadata only | Validated but host/container OOM policy is not controlled. |
| `server.configs.server.properties` with `parser: properties` | Supported | Applied atomically to the private instance. |
| Other `properties` targets | Intentionally unsupported | Only the confined `server.properties` target is accepted. |
| `parser: yaml` | Adapted | Nested map patches for contained `.yml`/`.yaml` files merge recursively and write atomically. |
| `parser: file` | Adapted | Contained UTF-8 line-prefix replacement with an 8 MiB limit and atomic target swap. Missing targets become empty files; absent prefixes are not inserted. |
| `state.volumes` mapping form with `mode: cow` | Adapted | Portable transactional private copy. |
| Volume shorthand `name:source:target[:mode]` | Supported | Omitted mode defaults to `cow`. |
| Multiple `cow` volumes targeting one directory | Adapted | Deterministic declaration-order merge; first source wins collisions, matching SLS main OverlayFS lower-layer precedence. |
| `mode: ro` | Adapted | Private writable instance snapshot protects the source; not a strict read-only bind mount. |
| `mode: rw` | Adapted | Creates and verifies a directory link to a source confined below the SLS-LITE data root. The source outlives instances and is shared concurrently; operators should normally combine it with a single-instance policy. |
| `state.mounts` | Intentionally unsupported | Reload fails with a local-mode explanation and recommends a contained `cow` or `ro` volume. |
| `state.copy` mapping and shorthand | Adapted | Transactional contained file/directory copy after software and volumes. Sources must be relative to the SLS-LITE data root; full-SLS absolute/allowed-host sources are intentionally rejected. Persistent instances refresh sources on reset rather than every restart. |
| `state.persistent_files` | SLS-LITE extension | Single-writer regular-file import and bounded atomic write-back below `volumes/`, with conflict preservation and no symlink or mount dependency. Full SLS main has no file-shaped volume behavior; its volume manager checks directory sources. |
| `state.env` | Adapted | Validated strings reach the local child process; JVM, loader, path, and SLS-owned variables are rejected. Names are visible to operators, values are not logged. |
| `save` | Supported | Persistent instance directory and identity. |
| `annotations` | Adapted | Unknown trees, including YAML null values, are retained as immutable metadata. |

SLS-LITE extensions under `server.limits` are `max_players` and
`max_instances`. Modern vSLS expresses those intentions under
`annotations.vsls`; the annotation form takes effect when the local extension
is omitted.

## vSLS Annotations

| SLS main annotation | Status | SLS-LITE behavior |
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

| SLS main field | Status | Local interpretation |
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
| Other software config parsers/targets | Intentionally unsupported | Rejected; only the documented contained targets and parsers are accepted. |
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
| `resource_pack` annotation | Metadata only | SLS-LITE does not provide public serving or transfer orchestration. |
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

The reviewed vSLS root names remain the command vocabulary. Compatibility is
split deliberately:

- The compatibility contract covers blueprint/software language and runtime
  intent, not every administrative command.
- Full-stack validation must verify every supported command branch, selector,
  permission, completion, and message.
- Distributed-only `node` behavior remains unavailable in local mode.
- SLS-LITE additions such as `admin`, `registries`, `blueprints`, granular
  lifecycle permissions, `--force` lobby protection, and install diagnostics
  are additive and do not replace upstream forms.

Commands implemented with local semantics are `info`, `list`,
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

## Fields Not In The Upstream Contract

`allowed-client-versions` does not appear in the SLS `main` source, models,
examples, or vSLS implementation. SLS-LITE does not interpret this field or
claim automatic per-blueprint client-version admission.

## Verification Requirements

Changes to this compatibility contract must keep attributed accepted/rejected
fixtures, exact-ID corpus loading, upstream example coverage, and automated
tests for every supported or adapted row. Parser-only acceptance does not prove
runtime volume sources, Java selection, process launch, or player transfer;
those remain separate integration gates described in [Testing](Testing.md).
The complete product classification is summarized in
[Compatibility](Compatibility.md).
