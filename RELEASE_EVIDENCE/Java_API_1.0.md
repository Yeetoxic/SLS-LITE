# Java API 1.0 Evidence

This record covers the versioned in-process Java extension API introduced
before the SLS-LITE release candidate. Operator and extension-author guidance
lives in [Java Extension API](../DOCS/Java_API.md); this file records validation
rather than forming part of the public contract.

## Public Boundary

API version `1.0` provides capability and readiness discovery, immutable
blueprint and instance views, asynchronous start/stop/delete requests,
player matchmaking queue operations, and globally ordered instance lifecycle,
player matchmaking, sanitized instance-failure, reconciliation, and API
shutdown events.
Only `net.slimelabs.slslite.api` and its `event` child package are supported.
The implementation adapter, coordinators, filesystem paths, process handles,
and mutable repositories remain internal.

The dedicated `sls-lite-0.1.0-SNAPSHOT-api.jar` was inspected after a clean
build. It contained the public API/event classes and
`META-INF/licenses/LICENSE`, contained no `api.internal`, instance, blueprint,
or Velocity implementation classes, and had SHA-256
`5F297C9B187BB2D949779F0E66EC974A073A2C09C52069F6EB34E4269217DA48`.
`jdeps` found only Java base-library dependencies and Velocity's intentionally
provided plugin/proxy API.

The full shaded plugin had SHA-256
`9817641356B0784787C54E75DCFCBAA02C6BD405D5C9A14CF60D15C94DE27F86`.
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
The atomic catalog-reload event and artifact-boundary CI increment raised the
complete passing tree to 637 tests. Committed and rejected reloads publish one
bounded result at the serialized transaction edge; observer failures cannot
alter the transaction. The API classifier contained 31 public API classes, no
`api.internal` classes, and no project classes outside the API package. CI now
enforces that boundary and the required API/license entries before artifact
upload.
The lobby-status increment raised the complete passing tree to 640 tests and
the classifier to 34 public API classes with no internal classes. Tests cover
primary recovery while the holding lobby remains available, total lobby loss,
primary restoration, snapshot deduplication, observer failure isolation, and
global API sequence mapping. The readiness callback was also moved onto the
serialized health scheduler to remove a provider/fallback shutdown lock-order
risk found during review.
The software-installation event increment raised the complete passing tree to
641 tests and the classifier to 39 public API classes with no internal classes.
Tests cover one shared `STARTED -> READY` stream for concurrent waiters,
sanitized `IO` failure followed by a successful retry, shutdown cancellation
without a second failed event or warning, and global API sequence mapping.
Dependency analysis, Spotless, and SpotBugs all passed, and `git diff --check`
reported no whitespace errors. SnakeYAML remains shaded into the runtime plugin
but is optional in the published POM, so API-classifier consumers do not inherit
that implementation dependency.
The reconciliation/API-shutdown increment raised the complete clean tree to
644 tests with zero failures or errors and the same eight environment-dependent
skips. The classifier contained 41 public API classes and no internal classes.
Tests cover retained per-subscriber reconciliation replay, exclusion of events
queued before subscription, original sequence preservation, duplicate
reconciliation rejection, and terminal shutdown delivery before dispatcher
closure. Recipient sets are captured at publication time, preventing late
subscribers from observing stale queued events.
The bounded diagnostics increment raised the complete clean tree to 646 tests
with zero failures or errors and eight environment-dependent skips. The
classifier contained 50 public API classes and no internal classes. Contract
tests cover defensive collection copies, log/entry limits, control-character
rejection, implementation-package exclusion, and documentation of the new
`DIAGNOSTICS` capability.
The extension-context increment raised the complete clean tree to 651 tests
with zero failures or errors and eight environment-dependent skips. The
classifier contained 51 public API classes and no internal classes. Tests cover
owned event removal, late-future suppression, terminal shutdown delivery,
idempotent context and registration closure, normalized namespace conflicts and
reuse, and the 256-registration bound.

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

The latest API-event build was subsequently deployed with the same Panel
stop/start workflow after both the instance-failure and atomic-reload
increments. The container copy matched the shaded-plugin checksum above;
Velocity 4.0.0 loaded SLS-LITE, SLS-Limbo became ready, and persistent lobby
`lobby.b5kk8m` returned ready on its managed loopback port. Only `sls-lite.jar`
was present in the live plugin directory and the allocation was left running.

For the lobby-event increment, the disposable API-only consumer was rebuilt
against the classifier and deployed alongside the verified plugin. On a normal
fixture restart it reported 11 capabilities, then observed
`primary=STARTING, holding=READY, route=HOLDING, available=true`, followed by
`primary=READY, holding=READY, route=PRIMARY, available=true`. The consumer was
removed and the allocation restarted again through the Panel; Velocity loaded
its normal two plugins, SLS-Limbo and persistent lobby `lobby.b5kk8m` returned
ready, the deployed checksum matched the build above, and only `sls-lite.jar`
remained in the plugin directory.

The software-installation event build was deployed through the same Panel
workflow. The existing verified Paper cache was reused without a false
installation-start event or download, while Velocity, SLS-Limbo, and persistent
lobby `lobby.b5kk8m` returned ready normally. The deployed plugin matched the
current shaded-plugin checksum above and the allocation was left running with
only `sls-lite.jar` installed.

For the reconciliation and API-shutdown increment, the API-only consumer was
rebuilt against the 41-class classifier. Although it subscribed after startup
reconciliation, it received the retained summary first with one inspected
persistent instance and zero failures, then API readiness and live lobby
events. A Panel restart produced the terminal `ApiShutdownEvent` at sequence
10 after the lobby stopped and before process exit. The next startup replayed a
new reconciliation summary and restored the persistent lobby. The disposable
consumer was removed, and a final Panel restart left Velocity running with its
normal two plugins, SLS-Limbo ready, persistent lobby `lobby.b5kk8m` ready,
only `sls-lite.jar` installed, and the deployed checksum matching the verified
build above.

The API-only consumer was then rebuilt against the diagnostics classifier and
captured a real snapshot from the normal fixture. It reported one managed
instance, zero queued players, maintenance disabled, 14 host probes, one
bounded instance-log snapshot, and zero recent failures while both lobby
processes were still starting; the subsequent lifecycle events and console
confirmed SLS-Limbo and persistent lobby `lobby.b5kk8m` reached ready. After
removing the disposable consumer, a final Panel restart loaded the normal two
plugins, restored both lobby services, left only `sls-lite.jar` installed, and
matched the shaded-plugin checksum above.

The disposable consumer was rebuilt once more to use only an owned
`ExtensionContext` for its event subscription and readiness callback. It
received retained reconciliation, completed readiness and diagnostics once,
and reached normal lobby health. The extension deliberately did not close its
context before a Panel restart; the archived Velocity log recorded lobby
`STOPPING -> STOPPED` followed by `ApiShutdownEvent` sequence 11. SLS-LITE then
closed the context automatically. The consumer was removed before the final
clean-fixture restart.
