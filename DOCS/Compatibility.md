# Compatibility

SLS-LITE is an independent, single-host implementation of useful SLS concepts.
It does not run under full SLS and does not require Protocube, a daemon, S4J,
Docker, or another SLS installation.

This document describes the pre-Stage 2 baseline. Stage 2 will pin a current
modern SLS revision and replace broad claims with a tested field-by-field
matrix from unmodified upstream definitions.

## Current Feature Matrix

| Area | Status | SLS-LITE behavior |
| --- | --- | --- |
| Dynamic registries | Supported | `blueprint.type` defines the registry. |
| Blueprint identity and limits | Supported | Modern names for ID, name, type, software, version, memory, players, and instances. |
| `state.volumes` `cow` intent | Adapted for local mode | Transactional private directory copy; no daemon or host mount. |
| Blueprint annotations | Adapted | Unknown annotations are accepted; documented SLS-LITE lifecycle annotations are interpreted. |
| Structured config patches | Partial | `server.properties` with `parser: properties` is supported; other formats are rejected. |
| Software definitions | Adapted | Local Java-jar profiles with Paper, vanilla, or manual sources. |
| Exact Paper/vanilla install | SLS-LITE only | Verified local cache and provider download after explicit EULA acceptance. |
| Instance IDs | Supported | Human-readable `<blueprint>.<short-id>`. |
| Matchmaking and capacity | Supported | Ready-instance preference, queued slots, and bounded new-instance creation. |
| Local lifecycle | Supported | Start, readiness, graceful stop, cancellation, idle cleanup, restart, and reset. |
| Persistent instances | Adapted | Local ownership metadata, definition fingerprint, and startup reconciliation. |
| Managed/external lobby | SLS-LITE only/adapted | Primary lobby can be local or pre-registered. |
| SLS-Limbo | SLS-LITE only | Bundled local fallback when no normal backend is safe. |
| vSLS command tree | Partial/adapted | Implemented local commands retain upstream names; unsupported roots respond explicitly. |
| Velocity permissions | Supported | Umbrella and granular nodes, plus built-in administrator bootstrap. |
| Node/daemon administration | Intentionally unsupported | No distributed nodes exist in local mode. |
| Container isolation/limits | Intentionally unsupported | JVM arguments and local admission are not container enforcement. |
| Remote event stream/API | Deferred | No public integration API or distributed event service yet. |
| Resource-pack hosting | Deferred | Public URL properties work; built-in serving and transfer orchestration do not. |
| True filesystem COW | Deferred | Portable copy is the current tested fallback. |

## Compatibility Rules

1. Modern SLS terminology wins when equivalent behavior exists.
2. Historical SLS v2.1.2 defines the proven single-host workflows to preserve.
3. Local adaptations must keep the same operator intent without claiming
   distributed isolation or enforcement.
4. Unsupported structural fields are rejected with actionable paths.
5. Unknown annotations remain available for external metadata.
6. Distributed-only commands retain a recognizable response instead of a fake
   local implementation.
7. SLS-LITE-specific behavior belongs under namespaced annotations or clearly
   labeled local configuration.
8. Full SLS must never become a runtime dependency of SLS-LITE.

## Version Baselines

- Command/presentation target: SLS `v0.2.0`, commit
  `8e8b1e3cf7d2157887764c16f11b8901f8241121`.
- Historical single-host behavior: SLS `2.1.2`, commit
  `4f9b7ca7f6d857d43253076f1627ad4087f663ab`.
- Current SLS-LITE build: `0.1.0-SNAPSHOT`.

See [SLS Command Compatibility](SLS_Command_Compatibility.md). Historical
single-host migration behavior is summarized in [Migration](Migration.md).

## Validated Development Environment

| Component | Current validation |
| --- | --- |
| SLS-LITE bytecode | Java 21 |
| Build/runtime JDK used by the local fixture | Temurin 25.0.3 |
| Velocity runtime used by the local fixture | 4.0.0 |
| Velocity API build used for compilation | Exact timestamped 4.1.0 snapshot pinned in `pom.xml` |
| ViaVersion fixture | 5.11.0, optional |
| SLS-Limbo | NanoLimbo 1.13.0 at `d192d57d` |
| Historical managed servers | Exact Paper versions from 1.11.2 through 1.18.2 in the Stage 1 fixture |
| Newer protocol smoke coverage | See `Protocol_Compatibility.md` |
| Host environment | Local Docker Desktop/Pterodactyl on Windows-backed storage |

Paper or vanilla provider availability for an exact version does not by itself
mean every world, plugin, Java combination, forwarding mode, or client protocol
is supported. Native-Linux performance and broad current-Paper compatibility
remain later test gates.

## Stage 2 Contract

Stage 2 must:

- pin the modern SLS revision used for the run;
- compare configuration, blueprints, commands, permissions, lifecycle,
  installation, storage, observability, and integrations;
- classify every feature as supported, adapted, intentionally unsupported, or
  deferred;
- load representative modern definitions without manual conversion;
- document every accepted, translated, and rejected field;
- identify both missing shared functionality and unnecessary SLS-LITE scope.

Until that gate passes, do not describe SLS-LITE as generally compatible with
all modern SLS configuration.
