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

## Blueprint Schema

| SLS v0.2.0 field | Status | SLS-LITE behavior |
| --- | --- | --- |
| `blueprint.id` | Supported | Global stable blueprint ID. |
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
| `parser: file` | Deferred | Requires explicit safe replacement semantics. |
| `state.volumes` mapping form with `mode: cow` | Adapted | Portable transactional private copy. |
| Volume shorthand `name:source:target[:mode]` | Supported | Omitted mode defaults to `cow`. |
| Multiple `cow` volumes targeting one directory | Adapted | Deterministic declaration-order merge; first source wins collisions, matching SLS v0.2.0 OverlayFS lower-layer precedence. |
| `mode: ro` | Adapted | Private writable instance snapshot protects the source; not a strict read-only bind mount. |
| `mode: rw` | Intentionally unsupported at runtime | Definition parses, but launch fails before preparation because shared mutable host state is unsafe in portable local mode. |
| `state.mounts` | Intentionally unsupported | Arbitrary host mounts require daemon/container policy. |
| `state.copy` | Deferred | Useful for plugins/configuration if sources and targets remain contained. |
| `state.env` | Deferred | Useful after protected variables and launch boundaries are defined. |
| `save` | Supported | Persistent instance directory and identity. |
| `annotations` | Supported as metadata | Open-ended values load without structural rejection. |

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
| `annotations.vsls.matchmaking.gameType` | Deferred | Requires game-type selection separate from blueprint registry/type. |
| `annotations.vsls.on-join[].run` | Deferred | Requires safe post-connect console actions and placeholder validation. |

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
| `images` and `mappings` | Metadata only | Validated but Docker references cannot select local binaries; adapted profiles use host `java`. |
| `invocation` | Adapted | A shell-free `java ... -jar <relative-file>` command is tokenized; shell syntax is rejected and heap limits are localized. |
| `stop-command` | Supported | Local graceful shutdown command. |
| `online-signal` | Adapted | Treated as a literal readiness substring. |
| `install-script` | Intentionally not executed directly | Structure is validated; known Paper/vanilla IDs map to verified providers and other IDs remain manual. |
| software `limits` | Metadata only | Validated but container-only defaults are not enforced or used for admission yet. |
| software `configs.server.properties` | Adapted | Defaults merge before blueprint patches and proxy-owned network values. |
| Other software config parsers/targets | Deferred | Rejected until a contained structured patcher exists. |
| remote `update` | Intentionally unsupported | Metadata is validated; definitions remain operator-controlled and pinned. |

SLS-LITE's provider-backed Paper/vanilla fields and manual profile schema are
local extensions. An imported definition does not imply Minecraft EULA
acceptance, and modern Docker mappings cannot name operator-supplied local Java
executables.

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

## Fields Not In The Pin

`allowed-client-versions` does not appear in the SLS `v0.2.0` source, models,
examples, or vSLS implementation. It is treated as announced/deferred work and
will be reconsidered only after an upstream schema and behavior stabilize.

## Stage 2 Acceptance

Before this matrix is final:

1. Add pinned, attributed fixtures for representative accepted and rejected
   definitions.
2. Test every supported/adapted row.
3. Load the project owner's modern network blueprints without editing them.
4. Implement or deliberately defer the useful local gaps.
5. Run the resulting multi-world network.
6. Review scope balance before Stage 3.

## Corpus Evidence

On 2026-07-29, the opt-in parser harness loaded the copied modern SlimeLabs
corpus unchanged:

- 54 blueprints;
- registries: `adventure`, `archive`, `experimental`, `game`, and `minigame`;
- explicit Java image selectors, one contained software path override,
  properties and YAML config patches, distributed limit metadata, and shorthand
  COW volumes.

Volume source directories were intentionally not required for this parser run.
The pinned upstream example harness loaded 5 of 6 examples independently. This
includes duplicate/multi-source COW merge and `ro`/`rw` definitions. The sole
expected rejection is `example_config_patches.yaml`, which uses the separately
deferred `parser: file` contract.
