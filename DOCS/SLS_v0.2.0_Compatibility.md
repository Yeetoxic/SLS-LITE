# SLS v0.2.0 Compatibility Matrix

This is the Stage 2 source contract for SLS-LITE.

- Upstream repository: `https://github.com/jessefaler/SLS`
- Release/tag: `v0.2.0`
- Commit: `8e8b1e3cf7d2157887764c16f11b8901f8241121`
- Pin verified: 2026-07-28
- Upstream license: GNU AGPL v3.0

The matrix is based on the pinned source models, parsers, bundled examples,
software definitions, and vSLS implementation. It will be updated as Stage 2
fixtures exercise each row.

## Status Terms

- **Supported:** same field and materially equivalent behavior.
- **Adapted:** accepted directly, with documented single-host behavior.
- **Intentionally unsupported:** distributed or unsafe behavior that does not
  belong in SLS-LITE.
- **Deferred:** useful local behavior that still needs implementation or tests.
- **SLS-LITE extension:** local-only behavior outside the shared SLS contract.

## Compatibility And Scope Review

Review completed on 2026-07-29 against:

- the pinned SLS `v0.2.0` source and bundled examples;
- the official blueprint, software, and vSLS command documentation;
- the copied 54-blueprint SlimeLabs corpus;
- the current SLS-LITE parser, command, lifecycle, storage, and process code.

SLS `v0.2.0` is still the latest published upstream release. The pin therefore
remains both reproducible and current for this Stage 2 run.

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

| Area | Stage 2 decision |
| --- | --- |
| Blueprint metadata, registries, software/version, config patches, state declarations, persistence, and vSLS lifecycle/capacity annotations | Shared contract; preserve names and operator intent. |
| Software memory defaults and version-to-Java mappings | Implemented local adaptations, covered by parser and atomic-reload tests. |
| `vsls.on-join` and `vsls.matchmaking.gameType` | Implemented vSLS adaptations, covered by transition and pool tests. |
| Container CPU, swap, I/O, disk, affinity, and OOM enforcement | Preserve as visible metadata; do not claim enforcement by a portable child JVM. |
| Docker images | Treat `java_<major>` as a local Java selector; reject other image semantics at launch. |
| Host mounts and shared `rw` volumes | Intentionally unsupported because they bypass local containment and instance isolation. |
| Nodes, load balancing, Protocube API, daemon event stream, and container administration | Intentionally unsupported; these define full SLS rather than SLS-LITE. |
| Public Java/API integration contract | Deferred until the local lifecycle is stable; do not expose internal classes as an accidental API. |
| Resource-pack conversion | Intentionally separate from SLS-LITE. Preserve annotations and support normal Minecraft URLs; conversion belongs in SlimePacks or another pack service. |
| Resource-pack discovery/serving for local test worlds | Useful local integration, but not part of the shared SLS blueprint contract and not a Stage 2 schema blocker. |
| SLS-Limbo, built-in administrator claims, local lobby recovery, provider-backed Paper/vanilla installation, and bounded local logs | Retained SLS-LITE extensions needed for a self-contained constrained-host product. |
| True OverlayFS/reflink COW | Performance work after schema compatibility; portable transactional copy remains the required fallback. |
| `create`, `delete`, `kill`, `blueprint`, `debug`, and complete command-output parity | Stage 3 command gate. Preserve roots now; implement only meaningful and safe local semantics. |
| `pause` and `resume` | Do not prioritize. Portable process suspension is unsafe and low-value for the constrained-host goal. |
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
| `mode: rw` | Intentionally unsupported at runtime | Definition parses, but launch fails before preparation because shared mutable host state is unsafe in portable local mode. |
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
| Daemon event stream | Deferred local equivalent | A public in-proxy event/API contract does not exist yet. |
| True overlay COW | Deferred optimization | Portable copy preserves isolation intent today. |
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

- Stage 2 requires blueprint/software language and runtime intent, not every
  administrative command.
- Stage 3 verifies every supported command branch, selector, permission,
  completion, and message.
- Distributed-only `node` behavior remains unavailable in local mode.
- SLS-LITE additions such as `admin`, `registries`, `blueprints`, granular
  lifecycle permissions, `--force` lobby protection, and install diagnostics
  are additive and do not replace upstream forms.

Commands currently implemented with local semantics are `info`, `list`,
`start`, `join`, `find`, `system`, `console`, `logs`, `reload`, `stop`,
`dequeue`, `status`, `stats`, `version`, `restart`, `reset`, and `install`.
The detailed argument-level status remains in
`SLS_Command_Compatibility.md`.

The unimplemented `create` root includes an upstream `--env=KEY=value`
override in the pinned source. If local `create` is implemented, it must reuse
the same environment validation as blueprint `state.env`; node and container
resource overrides must return explicit local-mode errors rather than being
accepted without enforcement.

## Acceptance Boundary

The parser, inheritance, mappings, annotation behavior, actionable rejection,
and exact-ID corpus gates are complete. Stage 2 can complete after:

1. the deployed modern multi-world fixture runs and its accepted, adapted, and
   rejected behavior is recorded;
2. the project owner approves this scope boundary.

Command completion, destructive bulk commands, public APIs, true filesystem
COW, native-Linux performance work, and release hardening remain later gates.

## Fields Not In The Pin

`allowed-client-versions` does not appear in the SLS `v0.2.0` source, models,
examples, or vSLS implementation. It is treated as announced/deferred work and
will be reconsidered only after an upstream schema and behavior stabilize.

## Stage 2 Acceptance

Before this matrix is final:

1. Pinned, attributed accepted and rejected fixtures: complete.
2. Automated coverage for the Stage 2 supported/adapted rows: complete.
3. Exact-ID loading of the project owner's copied modern blueprints: complete.
4. Required local compatibility gaps: implemented.
5. Resulting multi-world network: pending deployed/manual verification.
6. Project-owner scope approval before Stage 3: pending.

## Corpus Evidence

On 2026-07-29, the opt-in parser harness loaded the exact 54 expected
definitions from the normalized compatibility copy:

- 54 blueprints;
- registries: `adventure`, `archive`, `experimental`, and `minigame`;
- explicit Java image selectors, one contained software path override,
  properties and YAML config patches, distributed limit metadata, and shorthand
  COW volumes.

`BLUEPRINT_TEMPLATE.yaml.example` is excluded, while
`adventures/temple_of_doom.yaml` is included. The harness compares loaded IDs
with `EXPECTED_BLUEPRINT_IDS.txt`, so equal counts cannot hide omissions or
substitutions. The owner's source directory remains untouched.

Volume source directories were intentionally not required for this parser run.
The pinned upstream example harness loaded all 6 examples independently. This
includes duplicate/multi-source COW merge, `ro`/`rw` definitions, properties,
YAML, and text-file patches. Runtime volume sources remain a separate launch
gate.

An attributed fixture derived from `protocube/blueprint/example.yml` separately
verifies `state.copy` mapping/shorthand and `state.env`, because neither field
is used by the current 54-blueprint SlimeLabs corpus.
