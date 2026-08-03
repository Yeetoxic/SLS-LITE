# Java API Scope and Release Gate

SLS-LITE's public Java API is the local extension boundary for trusted Velocity
plugins. It is not a smaller Protocube HTTP API and does not represent remote
nodes, Docker containers, or another SLS installation.

The current implementation is the API 1.0 candidate, not yet the externally
frozen 1.x contract. Its completed foundation is documented in
[Java Extension API](Java_API.md). Stage 3.10 must pass before the release
candidate advertises general SLS-LITE expansion support.

## API and SPI Boundary

An API lets an extension inspect SLS-LITE, request safe operations, and observe
results. An SPI lets an extension replace or inject core behavior. The first
release should expose a useful API and only narrowly bounded action hooks.

| Surface | Before Stage 4 | Later or separately approved |
| --- | --- | --- |
| Catalog and runtime inspection | Immutable blueprint, instance, system, lobby, installation, capability, and diagnostic views | Mutable repositories or coordinator access |
| Local operations | Start, stop, delete, matchmaking, queue control, and documented safe actions | Direct process, filesystem, port, or mount control |
| Events | Instance, player routing, lobby/recovery, catalog, installation, reconciliation, failure, and shutdown events | Distributed node/daemon event emulation |
| Extension hooks | Namespaced annotations and bounded instance-ready/post-transfer actions with owned cleanup | Replacement matchmaking, lobby, installer, storage/COW, or process-provider SPIs |
| External control | None in the core Java API | Separately classified opt-in authenticated HTTP/event adapter |

Provider SPIs are intentionally deferred because they cross lifecycle,
filesystem, process, resource-accounting, and security invariants. Publishing
one prematurely would make an unsafe implementation detail a compatibility
promise.

## Release-Candidate Requirements

Before freezing API 1.x, SLS-LITE must provide:

- stable immutable models and machine-readable failure categories;
- bounded ordered callbacks with explicit threading, overload, and shutdown
  behavior;
- extension-owned registration and deterministic cleanup;
- redacted and size-bounded diagnostics;
- an example plugin compiled without the full SLS-LITE implementation JAR;
- automated signature, immutability, sanitization, concurrency, and
  documentation checks;
- a live start, transfer, stop, delete, persistent-recovery, rejection, and
  shutdown exercise through only the public API; and
- Javadocs, Maven/Gradle usage, versioning rules, published artifacts, and
  recorded checksums.

The detailed checklist and acceptance gate are maintained under Stage 3.10 in
the [roadmap](../todo.md).

## Compatibility Policy

Until Stage 3.10 closes, `1.0` is a candidate identifier and may be renumbered
or adjusted before any external release. Once frozen, API 1.x must preserve
existing method signatures and record shapes. Additive optional behavior uses
capabilities; a breaking change requires a new major version and migration
guidance.

The SLS-LITE plugin version and Java API version remain independent. Extensions
must check `version()` and `capabilities()` instead of inferring API support from
the plugin version.

## Explicit Non-Goals

The first Java API will not expose distributed nodes, controller databases,
Docker management, arbitrary host mounts, native Go plugins, S4J compatibility,
or unauthenticated network control. An HTTP administration/event adapter may be
considered separately, but it requires authentication, authorization, request
and rate bounds, listener configuration, and TLS/reverse-proxy guidance.
