# Java API Scope and Compatibility Policy

[Documentation home](README.md)

SLS-LITE's public Java API is the local extension boundary for trusted Velocity
plugins. It is not a smaller Protocube HTTP API and does not represent remote
nodes, Docker containers, or another SLS installation.

API `1.2` is the current supported compatibility contract and retains the API
`1.0` and `1.1` baselines. Its developer-facing usage is documented in
[Java Extension API](Java_API.md). Breaking changes require a new API major
version; compatible additions may be introduced in a minor version.

## API and SPI Boundary

An API lets an extension inspect SLS-LITE, request safe operations, and observe
results. An SPI lets an extension replace or inject core behavior. API 1.2
exposes safe operations and only narrowly bounded action hooks.

| Surface | API 1.2 | Outside the API 1.2 contract |
| --- | --- | --- |
| Catalog and runtime inspection | Immutable blueprint, instance, system, lobby, installation, capability, and diagnostic views | Mutable repositories or coordinator access |
| Local operations | Start, stop, restart/reset, delete, installation requests, definition reload, maintenance, exact transfer, matchmaking, queue control, and documented safe actions | Direct process, filesystem, port, mount, force-termination, or protected-lobby control |
| Events | Instance, player routing, lobby/recovery, catalog, installation, reconciliation, failure, and shutdown events | Distributed node/daemon event emulation |
| Extension hooks | Namespaced annotations, bounded readiness/status findings, and instance-ready/post-transfer actions with owned cleanup | Replacement matchmaking, lobby, installer, storage/COW, or process-provider SPIs |
| External control | None in the core Java API | Separately classified opt-in authenticated HTTP/event adapter |

Provider SPIs are intentionally excluded because they cross lifecycle,
filesystem, process, resource-accounting, and security invariants. Publishing
one would make an unsafe implementation detail a compatibility
promise.

## Supported 1.2 Contract

API 1.2 includes the API 1.0 and 1.1 baselines plus additive safe administrative
requests, exact-instance routing, and namespaced operational diagnostics. The
definition-reload result also includes a bounded impact summary without
granting extensions authority to mutate affected instances. The complete
current contract includes:

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

The checked JVM signature, public-package boundary, documentation contracts,
and clean-consumer distribution workflow enforce this baseline.

## Compatibility Policy

API 1.x preserves existing method signatures and record shapes. Additive
optional behavior uses capabilities; a breaking change requires a new major
version and migration guidance.

The SLS-LITE plugin version and Java API version remain independent. Extensions
must check `version()` and `capabilities()` instead of inferring API support from
the plugin version.

## Explicit Non-Goals

The first Java API will not expose distributed nodes, controller databases,
Docker management, arbitrary host mounts, native Go plugins, S4J compatibility,
or unauthenticated network control. An HTTP administration/event adapter may be
considered separately, but it requires authentication, authorization, request
and rate bounds, listener configuration, and TLS/reverse-proxy guidance.
