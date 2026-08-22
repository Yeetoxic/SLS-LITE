# Compatibility

[Documentation home](README.md)

SLS-LITE is an independent, single-host implementation of useful SLS concepts.
It does not run under full SLS and does not require Protocube, a daemon, S4J,
Docker, or another SLS installation.

The compatibility contract follows the upstream SLS `main` branch. The
field-level comparison and scope decisions are in the
[SLS Main Compatibility Matrix](SLS_Main_Compatibility.md). Reviewed,
repository-owned fixtures keep ordinary builds deterministic between upstream
audits.

## Current Feature Matrix

Shared-product material uses these permanent scope labels:

- **SLS and SLS-LITE:** the operator intent and usable contract exist in both.
- **Full SLS only:** distributed/controller/container behavior that SLS-LITE
  intentionally does not claim.
- **SLS-LITE only:** functionality created for the local single-host product.
- **Adapted for local mode:** shared intent implemented with a documented local
  process, filesystem, or Velocity boundary.

| Area | Status | SLS-LITE behavior |
| --- | --- | --- |
| Dynamic registries | SLS and SLS-LITE | `blueprint.type` defines the registry. |
| Blueprint identity and limits | SLS and SLS-LITE | Modern names for ID, name, type, software, version, memory, players, and instances. |
| `state.volumes` | Adapted for local mode | Transactional `cow` merge, private-snapshot `ro`, and explicit persistent single-instance `rw` through a verified shared directory link. |
| Blueprint annotations | Adapted for local mode | Unknown annotation trees and nulls are preserved; local lifecycle keys plus vSLS lifecycle, capacity, `gameType`, and bounded `on-join` keys are interpreted. |
| Structured config patches | Adapted for local mode | `server.properties`, contained nested YAML maps, and atomic text line-prefix patches are supported; JSON, XML, INI, and arbitrary properties targets are rejected. |
| Software definitions | Adapted for local mode | Local profiles and constrained modern SLS definitions with shell-free Java invocation. |
| Exact Paper/vanilla install | SLS-LITE only | Verified local cache and provider download after explicit EULA acceptance. |
| Instance IDs | SLS and SLS-LITE | Human-readable `<blueprint>.<short-id>`. |
| Matchmaking and capacity | Adapted for local mode | Ready-instance preference, queued slots, bounded new-instance creation, and vSLS `gameType` pools. |
| Local lifecycle | Adapted for local mode | Start, readiness, graceful stop, cancellation, idle cleanup, restart, and reset. |
| Persistent instances | Adapted for local mode | Local ownership metadata, definition fingerprint, and startup reconciliation. |
| Managed/external lobby | Adapted for local mode | Primary lobby can be Velocity-owned, locally managed, or pre-registered. |
| SLS-Limbo | SLS-LITE only | Bundled local fallback when no normal backend is safe. |
| vSLS command tree | Adapted for local mode | Implemented local commands retain upstream names; unsupported roots respond explicitly. |
| Velocity permissions | Adapted for local mode | Umbrella and granular nodes, plus built-in administrator bootstrap. |
| Node/daemon administration | Full SLS only | No distributed nodes exist in local mode. |
| Container isolation/limits | Full SLS only | JVM arguments and local admission are not container enforcement. |
| Java extension API | SLS-LITE only | Versioned capability discovery, immutable inspection, asynchronous local requests, queues, and ordered lifecycle subscriptions. |
| Authenticated HTTP/event API | Full SLS only | No public network listener or distributed event service exists. Trusted extensions may expose their own authenticated integration surface. |
| Resource-pack hosting | Full SLS only | Public URL properties work; built-in serving and transfer orchestration are not provided. |
| True filesystem COW | Adapted for local mode | Reflink, eligible Btrfs subvolume snapshots, kernel OverlayFS, fuse-overlayfs, and an explicit bounded operator snapshot-helper protocol are implemented. Portable copy remains the universal fallback. |

## Complete Current SLS Project Map

The current comparison baseline is the upstream SLS `main` branch. Periodic
drift audits cover Protocube, daemon, vSLS, the shared blueprint/software
definitions, and the bundled SlimePacks extension. A matching field or command
name does not imply that SLS-LITE provides the distributed or container
boundary around it.

| Full-SLS surface | Classification | SLS-LITE decision |
| --- | --- | --- |
| Blueprint metadata, dynamic types, software/version, `save`, annotations, volumes, copy declarations, environment, and retained config patches | Compatible or locally adapted | Accepted directly where safe; local path confinement, bounded inputs, JVM limits, and documented parser subsets apply. |
| Software identity, image mappings, invocation, readiness, stop command, defaults, and warmup intent | Compatible or locally adapted | Imported Java software definitions select local runtimes and shell-free launches. Paper/vanilla use verified local providers; arbitrary container installation scripts and remote definition updates are not executed. |
| vSLS commands, permissions, selectors, force behavior, matchmaking annotations, and on-join actions | Compatible or locally adapted | Retained useful branches keep their command shape. Distributed/container-only branches remain recognizable and return an explicit local-mode explanation. |
| Server lifecycle, crash handling, readiness, reset, reinstall intent, console, logs, and statistics | Locally adapted | Supervised child JVMs replace Docker containers. Ownership, cancellation, persistence, reconciliation, bounded recovery, and cleanup are enforced locally. |
| Matchmaking, capacity, queues, server registration, empty-server cleanup, and player transfer | Compatible or locally adapted | Implemented inside Velocity for one host, including Velocity-native, managed, or external lobby policy and the SLS-Limbo recovery extension. |
| COW volumes and reusable software state | Locally adapted | Exact-path selection chooses reflink, eligible Btrfs, kernel OverlayFS, rootless fuse-overlayfs, or portable copy. Full SLS uses daemon-managed OverlayFS/container mounts. |
| `ro`, `rw`, and `state.copy` data intentions | Locally adapted | `ro` is a source-protecting private snapshot; `rw` is an explicit verified shared directory link; copy sources stay below the SLS-LITE data root. |
| Arbitrary host `state.mounts` | Intentionally outside scope | Rejected because a portable plugin cannot safely authorize host paths or reproduce daemon/container mount isolation. |
| Protocube HTTP API, API keys/scopes, SSE/WebSocket events, request IDs, and S4J clients | Outside SLS-LITE core | SLS-LITE has no public network control plane. Its separate versioned Java API exposes local immutable views and safe service requests; trusted extensions own any network listener they add. |
| Nodes, registration, heartbeats, draining, load balancing, allocations, and horizontal scaling | Intentionally outside scope | A SLS-LITE installation owns one Velocity allocation. Local maintenance mode blocks new creation without pretending to be node draining. |
| Docker images, cgroups, CPU/swap/I/O/disk/affinity/OOM enforcement, container networking, and daemon file/archive APIs | Intentionally outside scope | Container-only fields are validated or retained as visible metadata but never reported as enforced. SLS-LITE stays inside the hosting provider's existing security boundary. |
| Protocube/daemon SQLite controller state | Locally adapted | Versioned instance metadata, administrator properties, durable storage manifests, and startup reconciliation replace distributed controller databases. |
| Full-SLS installation containers and arbitrary scripts | Locally adapted | Exact Paper/vanilla downloads, manual software roots, EULA gating, atomic caches, warmup, cancellation, retry, and cleanup replace privileged installer containers. |
| Docker bridge addresses and remote node networking | Intentionally outside scope | Managed backends bind allocated loopback ports; Velocity forwarding and online-mode validation protect the local proxy/backend path. |
| System information, status, stats, console/log access, crash reports, and operational diagnostics | Locally adapted | Bounded in-game output, concise console milestones, rotating detailed logs, correlation IDs, timing phases, host probes, and resource admission replace controller/daemon observability. |
| Protocube bearer keys, daemon tokens, TLS/CORS, and plugin API trust | Intentionally outside scope | Velocity permissions plus a short-lived administrator claim secure local commands. Secrets and paths are confined/redacted; no external listener exists to authenticate. |
| Protocube Go `.so` extensions | Intentionally outside scope | Native controller plugins cannot run inside a JVM Velocity plugin. Blueprint annotations remain available for external integrations. |
| SlimePacks discovery, conversion, cache, and HTTP serving | Separate integration, not duplicated | SLS-LITE preserves resource-pack annotations and supports normal Minecraft pack URLs. Conversion/hosting remains a separate service. |
| Non-Minecraft game processes advertised by full SLS | Intentionally outside scope | The first SLS-LITE product is a Velocity/Minecraft network manager and launches reviewed Java server profiles only. |
| SLS-LITE lobby, SLS-Limbo, provider installers, administrator claims, join-test, maintenance mode, and multi-backend COW selection | SLS-LITE extensions | These replace infrastructure unavailable in a constrained single-allocation deployment; they do not claim full-SLS compatibility. |

No unresolved release-blocking difference was found within the documented
single-host product boundary. The incompatible areas above are explicit scope
boundaries, not silent parser acceptance or incomplete runtime behavior.

## Compatibility Rules

1. Modern SLS terminology wins when equivalent behavior exists.
2. The preserved historical single-host SLS fixture defines the proven local
   workflows to preserve.
3. Local adaptations must keep the same operator intent without claiming
   distributed isolation or enforcement.
4. Unsupported structural fields are rejected with actionable paths.
5. Unknown annotations remain available for external metadata.
6. Distributed-only commands retain a recognizable response instead of a fake
   local implementation.
7. SLS-LITE-specific behavior belongs under namespaced annotations or clearly
   labeled local configuration.
8. Full SLS must never become a runtime dependency of SLS-LITE.

## Compatibility Baselines

- Command, schema, and presentation target: upstream SLS `main`.
- Historical single-host behavior: the preserved legacy fixtures documented in
  [Migration](Migration.md).

The supported release line retains these boundaries:

- full SLS compatibility follows reviewed changes on `main`; ordinary builds
  use repository-owned fixtures and never download a moving branch;
- Minecraft and Paper 26.2 remain the stable server baseline; Minecraft 26.3 is
  still a snapshot line and is handled only by the documented forward-client
  ViaVersion policy;
- Velocity 4.1.0 remains a snapshot line. SLS-LITE retains the exact API build
  in `pom.xml` and the exercised 4.0.0 runtime rather than changing the proxy
  boundary immediately before the candidate;
- ViaVersion 5.11.0 remains the optional exercised integration.

See [SLS Command Compatibility](SLS_Command_Compatibility.md). Historical
single-host migration behavior is summarized in [Migration](Migration.md).

## Reference Development Environment

| Component | Reference value |
| --- | --- |
| SLS-LITE bytecode | Java 21 |
| Build/runtime JDK used by the local fixture | Temurin 25.0.3 |
| Velocity runtime used by the local fixture | 4.0.0 |
| Velocity API build used for compilation | Exact timestamped 4.1.0 snapshot pinned in `pom.xml` |
| ViaVersion fixture | 5.11.0, optional |
| SLS-Limbo | NanoLimbo 1.13.0 at `d192d57d` |
| Historical managed servers | Exact Paper versions from 1.11.2 through 1.18.2 in the preserved historical-world fixture |
| Forward-client policy | Minecraft 26.2 minimum with compatible ViaVersion; no hard maximum imposed by SLS-LITE |
| Exact protocol regression coverage | See `Protocol_Compatibility.md` |
| Host environment | Local Docker Desktop/Pterodactyl on Windows-backed storage |

Paper or vanilla provider availability for an exact version does not by itself
mean every world, plugin, Java combination, forwarding mode, or client protocol
is supported. With ViaVersion installed, SLS-LITE treats the documented current
client release as a forward-compatible minimum and does not impose a maximum;
the installed ViaVersion build must report support for each newer client and
backend baseline. The protocol matrix distinguishes that allowed forward path
from exact combinations regression-tested by SLS-LITE.

## Named Host Capability Profiles

Automatic storage selection is tested against these capability profiles. A
profile describes detected capabilities, not a provider brand guarantee; the
exact-path startup probes remain authoritative.

| Profile | Required exposure | Expected `auto` result |
| --- | --- | --- |
| Restricted ext4 shared host | Writable storage and child processes only | `portable-copy` |
| XFS reflink host | Successful clone/isolation probe | `reflink` |
| Eligible Btrfs host | Source subvolume and successful snapshot/isolation probe | `btrfs` |
| Kernel-overlay host | Overlay driver plus contained mount permission | `overlay` |
| Rootless-FUSE host | `fuse-overlayfs`, `/dev/fuse`, and successful unprivileged mount | `fuse-overlay` |
| Operator snapshot provider | Explicit confined `sls-snapshot-helper-v1` executable | `snapshot-hook` only when explicitly requested |

Pterodactyl's normal restricted profile is never weakened to obtain a faster
result. Missing `CAP_SYS_ADMIN`, inaccessible `/dev/fuse`, unsuitable
filesystems, or rejected helper handshakes degrade to portable copy under
`auto` and fail startup when explicitly required.

## Compatibility Contract

The compatibility work must:

- record the upstream `main` comparison run in development or release evidence;
- compare configuration, blueprints, commands, permissions, lifecycle,
  installation, storage, observability, and integrations;
- classify every feature as supported, adapted, SLS-LITE-only, or intentionally
  unsupported;
- load representative modern definitions without manual conversion;
- document every accepted, translated, and rejected field;
- identify both missing shared functionality and unnecessary SLS-LITE scope.

SLS-LITE supports the documented subset. It must not be described as compatible
with every modern SLS configuration or distributed full-SLS feature.
