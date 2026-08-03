# Java API 1.0 Evidence

This record covers the versioned in-process Java extension API introduced
before the SLS-LITE release candidate. Operator and extension-author guidance
lives in [Java Extension API](../DOCS/Java_API.md); this file records validation
rather than forming part of the public contract.

## Public Boundary

API version `1.0` provides capability and readiness discovery, immutable
blueprint and instance views, asynchronous start/stop/delete requests,
player matchmaking queue operations, and ordered instance lifecycle events.
Only `net.slimelabs.slslite.api` and its `event` child package are supported.
The implementation adapter, coordinators, filesystem paths, process handles,
and mutable repositories remain internal.

The dedicated `sls-lite-0.1.0-SNAPSHOT-api.jar` was inspected after a clean
build. It contained the public API/event classes and
`META-INF/licenses/LICENSE`, contained no `api.internal`, instance, blueprint,
or Velocity implementation classes, and had SHA-256
`5882020A30C2FD4AD07497D3D5503BE2034BF81055238FF7C71EC651F8F89973`.
`jdeps` found only Java base-library dependencies and Velocity's intentionally
provided plugin/proxy API.

The full shaded plugin had SHA-256
`F87B53AE6C4146B6572407FBE1A098E34E78721C4C389F598DFBF08212EE3E34`.
Packaging review also exposed and corrected a pre-existing license-resource
target mismatch; both the API classifier and full plugin now contain the
project license at `META-INF/licenses/LICENSE`.

## Automated Validation

On 2026-08-03, focused API contract, documentation, immutable-view, bounded
event-dispatch, lifecycle-observer, and license-resource tests passed 9 tests
with zero failures or errors. `mvn clean verify` passed before the final
dependency-metadata refinement, and `mvn verify` passed the final tree: 626
tests with zero failures or errors and eight environment-dependent skips.
Dependency analysis, Spotless, and SpotBugs all passed, and `git diff --check`
reported no whitespace errors. SnakeYAML remains shaded into the runtime plugin
but is optional in the published POM, so API-classifier consumers do not inherit
that implementation dependency.

The public contract test rejects implementation-package types in API methods
and constructors and requires every API method and advertised capability to
remain documented. Event tests cover ordering, subscriber failure isolation,
the 128-subscriber bound, idempotent closure, and stable closed/not-ready
behavior.

## Velocity/Pterodactyl Smoke Test

A disposable Velocity plugin was compiled using only the API classifier plus
Velocity's provided compile-time dependencies. It declared `sls-lite` as a
required plugin, discovered the provider through `SLSLiteApiProvider`, awaited
readiness, inspected capabilities/catalogs, and subscribed to lifecycle events.

The verified full plugin and disposable consumer were deployed through the
normal Pterodactyl Panel workflow without changing the allocation's security
profile. On startup the consumer reported API version `1.0`, all 7 capabilities,
1 blueprint, and 1 persistent instance. Persistent lobby `lobby.b5kk8m`
published ordered `PREPARING -> STARTING -> READY` events. The public `READY`
event appeared only after Velocity registration, matching the API contract.

The disposable consumer JAR was then removed and the allocation restarted
through the Panel. Only `sls-lite.jar` remained in the live plugin directory;
SLS-Limbo and persistent Paper 26.2 lobby `lobby.b5kk8m` returned ready. No
fixture data was deleted and no SLS-LITE startup error was observed.
