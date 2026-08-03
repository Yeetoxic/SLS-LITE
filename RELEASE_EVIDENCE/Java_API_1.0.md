# Java API 1.0 Evidence

This record covers the versioned in-process Java extension API introduced
before the SLS-LITE release candidate. Operator and extension-author guidance
lives in [Java Extension API](../DOCS/Java_API.md); this file records validation
rather than forming part of the public contract.

## Public Boundary

API version `1.0` provides capability and readiness discovery, immutable
blueprint and instance views, asynchronous start/stop/delete requests,
player matchmaking queue operations, and globally ordered instance lifecycle,
player matchmaking, and sanitized instance-failure events.
Only `net.slimelabs.slslite.api` and its `event` child package are supported.
The implementation adapter, coordinators, filesystem paths, process handles,
and mutable repositories remain internal.

The dedicated `sls-lite-0.1.0-SNAPSHOT-api.jar` was inspected after a clean
build. It contained the public API/event classes and
`META-INF/licenses/LICENSE`, contained no `api.internal`, instance, blueprint,
or Velocity implementation classes, and had SHA-256
`554A1C42FB0FF2D56959E258950BC4386BE21762141AF17939DA5DB3A1C97B54`.
`jdeps` found only Java base-library dependencies and Velocity's intentionally
provided plugin/proxy API.

The full shaded plugin had SHA-256
`E94FF466C7A401F64FA4F481AE3C54BC764BD3ADAB6B85BFE22C8126513B5F19`.
Packaging review also exposed and corrected a pre-existing license-resource
target mismatch; both the API classifier and full plugin now contain the
project license at `META-INF/licenses/LICENSE`.

## Automated Validation

On 2026-08-03, focused API contract, documentation, immutable-view, bounded
event-dispatch, lifecycle-observer, and license-resource tests passed 9 tests
with zero failures or errors. `mvn clean verify` passed before the final
dependency-metadata refinement, and `mvn verify` passed the final tree: 626
tests with zero failures or errors and eight environment-dependent skips.
The matchmaking-event increment raised the complete passing tree to 633 tests
with the same eight environment-dependent skips.
The instance-failure increment raised the complete passing tree to 635 tests.
It verifies exactly-once startup failure delivery, post-registration process
crashes classified as `RUNTIME`/`PROCESS`, sanitized public mapping, and global
sequence integration. `mvn verify` again passed dependency analysis, Spotless,
and SpotBugs with zero findings.
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
profile. On startup the consumer reported API version `1.0`, all 8 capabilities
then advertised by that increment,
1 blueprint, and 1 persistent instance. Persistent lobby `lobby.b5kk8m`
published ordered `PREPARING -> STARTING -> READY` events. The public `READY`
event appeared only after Velocity registration, matching the API contract.

The consumer was then rebuilt strictly against the updated API classifier and
subscribed to `PlayerMatchmakingEvent`. A real online-mode player invoked
`/sls join lobby lobby`; the API delivered `QUEUED`, `TRANSFER_STARTED`, and
`TRANSFER_SUCCEEDED` in order for existing instance `lobby.b5kk8m`, and Velocity
reported the expected `ALREADY_CONNECTED` result. The event exposed only the
immutable ticket, `instanceCreated=false`, and stable status enums.

The disposable consumer JAR was then removed and the allocation restarted
through the Panel. Only `sls-lite.jar` remained in the live plugin directory;
SLS-Limbo and persistent Paper 26.2 lobby `lobby.b5kk8m` returned ready. No
fixture data was deleted and no SLS-LITE startup error was observed.

The instance-failure build was subsequently deployed with the same Panel
stop/start workflow. The container copy matched the shaded-plugin checksum
above; Velocity 4.0.0 loaded SLS-LITE, SLS-Limbo became ready, and persistent
lobby `lobby.b5kk8m` returned ready on its managed loopback port. Only
`sls-lite.jar` was present in the live plugin directory and the allocation was
left running.
