# Java Extension API

SLS-LITE exposes a versioned in-process Java API for trusted Velocity plugins.
The internally frozen API `1.0` candidate supports capability discovery, immutable
blueprint and instance inspection, asynchronous start/stop/delete and
player-matchmaking requests, queue inspection/cancellation, and ordered
instance lifecycle events.

The accepted JVM contract is frozen by the checked signature baseline. Breaking
changes now require a new API major version even before the first public plugin
release. External availability remains gated on downloading and recompiling the
example against the final artifacts from the selected release channel; local
snapshot coordinates are development inputs, not a publication guarantee. See
the remaining [Java API release gate](Java_API_Roadmap.md).

This is not the Protocube HTTP API or an S4J endpoint. It cannot manage remote
nodes, containers, or another SLS installation.

## Dependency And Discovery

Build an extension against `sls-lite-<version>-api.jar` as a compile-only or
provided dependency. The API classifier contains only the supported public
package and its license; the full shaded plugin remains the runtime provider.
Declare `sls-lite` as a required Velocity plugin dependency so its plugin
instance is available before the extension initializes.

Server operators install only `sls-lite-<version>.jar`. The public API classes
are already bundled in that plugin. Do not place the `-api.jar` in Velocity's
`plugins` directory: it is a smaller compile-time artifact for extension
developers, comparable to an SDK. Keeping it separate prevents extension code
from accidentally importing SLS-LITE implementation packages and avoids using
the large shaded runtime JAR as a development dependency.

The accepted 1.0 JVM class, field, constructor, and method descriptors have a
checked SHA-256 baseline in
`src/test/resources/api/public-api-1.0.sha256`. The build derives the signature
directly from compiled class files (not reflection), writes the reviewable form
to `target/api-signature/public-api-1.0.txt`, and fails on any descriptor or
visibility change. Updating the baseline requires an explicit compatibility
review; a passing hash does not authorize an undocumented API change.

### Developer artifacts

One verified build produces four relevant JARs:

| Artifact | Consumer |
| --- | --- |
| `sls-lite-<version>.jar` | Server operators; install this in Velocity. |
| `sls-lite-<version>-api.jar` | Extension compilers; never install it as a plugin. |
| `sls-lite-<version>-api-sources.jar` | IDE source attachment for the public API only. |
| `sls-lite-<version>-api-javadoc.jar` | Offline HTML reference for the public API only. |

The sources and Javadocs exclude `net.slimelabs.slslite.api.internal`. Javadoc
generation validates references, HTML, syntax, and accessibility with doclint
and fails the build on warnings. Successful CI
runs upload this exact artifact set together; a release candidate must attach
the same reviewed set to its GitHub Release. The plugin JAR is the only artifact
an operator needs.

For Maven, install or resolve the reviewed artifact set and use a provided
classifier dependency:

```xml
<dependency>
  <groupId>net.slimelabs</groupId>
  <artifactId>sls-lite</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <classifier>api</classifier>
  <scope>provided</scope>
</dependency>
```

For Gradle Kotlin DSL, use the same classifier as a compile-only dependency:

```kotlin
repositories {
    mavenLocal() // Replace with the published release repository when available.
}

dependencies {
    compileOnly("net.slimelabs:sls-lite:0.1.0-SNAPSHOT:api")
}
```

The retained example includes complete
[Maven](../examples/velocity-extension/pom.xml) and
[Gradle](../examples/velocity-extension/build.gradle.kts) builds. Because the
pinned Velocity 4 snapshot advertises Java 25 Gradle metadata, its Gradle build
selects a Java 25 compile classpath while `javac --release 21` continues to emit
Java 21-compatible example bytecode.

```java
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import net.slimelabs.slslite.api.SLSLiteApi;
import net.slimelabs.slslite.api.SLSLiteApiProvider;

@Plugin(
    id = "example-sls-extension",
    name = "Example SLS Extension",
    version = "1.0.0",
    dependencies = @Dependency(id = "sls-lite"))
public final class ExampleExtension {
  private final ProxyServer proxy;

  public ExampleExtension(ProxyServer proxy) {
    this.proxy = proxy;
  }

  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    SLSLiteApi api =
        SLSLiteApiProvider.find(proxy)
            .orElseThrow(() -> new IllegalStateException("SLS-LITE API unavailable"));

    api.ready().thenRun(() -> api.blueprints().forEach(blueprint ->
        System.out.println(blueprint.type() + "/" + blueprint.id())));
  }
}
```

`api()` and `SLSLiteApiProvider.find(...)` return the same stable facade for the
plugin lifetime. Check `status()` or wait for `ready()` before inspection or
operations. Initialization failure completes `ready()` exceptionally with a
public `SLSLiteApiException`; shutdown changes the status to `CLOSED`.

## API Surface

| Method | Purpose |
| --- | --- |
| `version()` | Return the Java API version. |
| `status()` / `ready()` | Inspect or await provider readiness. |
| `capabilities()` | Discover supported optional features. |
| `extension(namespace)` | Create an owned extension callback/subscription context. |
| `diagnostics()` | Capture redacted bounded operational diagnostics. |
| `blueprints()` / `blueprint(id)` | Inspect immutable blueprint views. |
| `instances()` / `instance(id)` | Inspect immutable instance views. |
| `start(request)` | Start and register an instance. |
| `stop(id)` / `delete(id)` | Stop or delete through normal lifecycle rules. |
| `enqueue(request)` | Match and transfer an online player. |
| `queued(playerId)` / `dequeue(playerId)` | Inspect or cancel a queued request. |
| `subscribe(listener)` | Receive ordered lifecycle, matchmaking, and failure events. |

API 1.0 advertises `BLUEPRINT_INSPECTION`, `INSTANCE_INSPECTION`,
`INSTANCE_START`, `INSTANCE_STOP`, `INSTANCE_DELETE`, `PLAYER_QUEUE`,
`MATCHMAKING_EVENTS`, `INSTANCE_FAILURE_EVENTS`, `CATALOG_RELOAD_EVENTS`,
`LOBBY_STATUS_EVENTS`, `SOFTWARE_INSTALLATION_EVENTS`, `RECONCILIATION_EVENTS`,
`API_SHUTDOWN_EVENTS`, `DIAGNOSTICS`, `EXTENSION_CONTEXTS`,
`EXTENSION_ACTIONS`, and `LIFECYCLE_EVENTS`.

## Operations

Start completes only after the backend is ready and registered with Velocity:

```java
api.start(new StartRequest("block_hunt"))
    .thenAccept(instance -> System.out.println("Ready: " + instance.id()))
    .exceptionally(failure -> {
      // Unwrap CompletionException and inspect SLSLiteApiException.code().
      return null;
    });
```

`InstanceOverrides` exposes only the same reviewed local fields as `/sls
create`: memory, persistence, seed, view distance, simulation distance, and
command blocks. Node, image, software, environment, and container-resource
overrides are deliberately absent.

`stop(instanceId)` performs graceful lifecycle shutdown. `delete(instanceId)`
uses the same ownership-aware persistent/ephemeral deletion transaction as the
operator command. These calls return `CompletionStage` and must not be blocked
on Velocity's event thread.

Queue an online player through normal capacity-aware matchmaking:

```java
api.enqueue(new QueueRequest(playerId, "minigame", "block_hunt"))
    .thenAccept(result -> {
      if (!result.connected()) {
        // The request completed, but Velocity rejected the final transfer.
      }
    });
```

`queued(playerId)` inspects a ticket and `dequeue(playerId)` atomically removes
one. Queue requests still enforce blueprint pools, maximum instances, player
capacity, maintenance mode, memory admission, and normal connection behavior.

## Diagnostics

`diagnostics()` returns one immutable point-in-time `DiagnosticsSnapshot`.
It includes system and queue counts, maintenance state, effective primary and
holding-lobby health, up to 100 recent installations, 64 startup host probes,
256 instance statistics and redacted 20-line output tails, and the latest 64
sanitized correlated instance failures. System counts remain exact when the
detailed instance lists are truncated.

Messages are single-line, redacted, and limited to 512 characters. The view
does not expose credentials, filesystem paths, download URLs, process IDs or
handles, mutable log buffers, internal exceptions, coordinators, or repository
objects. Callers receive defensive list copies and must request a new snapshot
to observe later changes.

## Extension Contexts

A complete Velocity consumer is available in
[`examples/velocity-extension`](../examples/velocity-extension/README.md) and
is compiled against only the API classifier in CI.

Use one `ExtensionContext` for each extension plugin and close it during that
plugin's shutdown:

```java
ExtensionContext context = api.extension("example-plugin");
context.subscribe(event -> handle(event));
context.onComplete(api.start(request), (instance, failure) -> handle(instance, failure));
context.onInstanceReady(action -> initializeBackend(action.instance(), action.annotations()));
context.onPostTransfer(action -> recordArrival(action.ticket(), action.annotations()));

// During extension shutdown:
context.close();
```

Namespaces are case-normalized plugin-style identifiers and remain unique
while owned. At most 128 contexts and 256 registrations per context are
accepted. Event subscriptions and completion registrations return independently
idempotent `Subscription` handles, while closing the context releases all of
them at once. A completed future automatically releases its registration.

Completion callbacks execute on the thread completing their source stage;
event callbacks use the bounded SLS-LITE event dispatcher. Callbacks must remain
non-blocking. Closing a context gates callbacks that have not begun but cannot
interrupt user code already executing. SLS-LITE shutdown first delivers the
terminal `ApiShutdownEvent`, then closes every remaining context; incomplete
future callbacks are suppressed as soon as API shutdown begins.

The owned extension-context surface selects only its namespace's top-level
blueprint annotation object. For a context named `example-plugin`, the matching
blueprint shape is:

```yaml
annotations:
  example-plugin:
    mode: ranked
    rewards:
      - daily
```

`context.annotations(blueprint)` returns a deeply immutable
`NamespacedAnnotations` value. Maps and lists are limited to 256 entries, the
tree to 16 levels and 4,096 total values, and strings to 4,096 characters.
Only null, strings, booleans, immutable numeric values, maps, and lists cross
the API boundary.

`onInstanceReady(action)` runs after the instance is registered with Velocity
and immediately after its public READY event is queued. `onPostTransfer(action)`
runs only after a queued Velocity connection request actually moves the player,
immediately after `TRANSFER_SUCCEEDED` is queued. An `ALREADY_CONNECTED` no-op
still produces the established success event but does not invoke the action.
Payloads capture the immutable instance or ticket, the original occurrence
time, and the extension's annotation namespace at publication; later catalog
reloads cannot change them. Registrations are
included in the context's 256-registration limit, use the same bounded ordered
dispatcher as events, run in namespace then registration order, and are disabled
after their first callback failure.

These hooks cannot replace matchmaking, lobby providers, software installers,
storage/COW strategies, process supervision, backend registration, or resource
admission. Those remain internal implementation boundaries.

## Lifecycle Events

```java
Subscription subscription = api.subscribe(event -> {
  if (event instanceof InstanceLifecycleEvent lifecycle) {
    System.out.println(lifecycle.instanceId() + ": "
        + lifecycle.previousStatus() + " -> " + lifecycle.currentStatus());
  }
});

// During extension shutdown:
subscription.close();
```

Events are delivered in transition order by one bounded SLS-LITE dispatcher.
Recipients are captured when an event is published, so a new subscriber never
receives ordinary events that were already queued before it subscribed.
Callbacks must return quickly and offload blocking work. A subscriber that
throws is disabled after its first logged failure. If a slow extension fills
the 1,024-event queue, SLS-LITE drops later notifications and warns at most once
per minute; sequence gaps let consumers detect this. At most 128 simultaneous
subscriptions are allowed.
An event records an accepted transition, not a retained object reference; a
terminal instance may already be absent when a delayed subscriber inspects it.
The public `READY` transition is published only after Velocity registration;
the earlier child-process readiness signal remains internal. Likewise,
`start(...)` completes only after that registration succeeds.

Matchmaking subscriptions receive `PlayerMatchmakingEvent` with the immutable
queue ticket, whether the request created its assigned instance, and one of the
following accepted statuses:

- `QUEUED` includes the assigned instance;
- `TRANSFER_STARTED` means cancellation can no longer claim the request;
- `TRANSFER_SUCCEEDED`, `TRANSFER_REJECTED`, and `TRANSFER_FAILED` are transfer
  terminal states; and
- `CANCELLED`, `DISCONNECTED`, `TIMED_OUT`, `INSTANCE_FAILED`,
  `BACKEND_UNAVAILABLE`, and `SHUTDOWN` explain non-transfer terminal states.

The status is deliberately machine-readable and contains no internal exception
message or Velocity result object. A request emits exactly one terminal status.

An accepted instance failure emits one `InstanceFailureEvent`. Its
`InstanceFailurePhase` identifies where the failure occurred and its
`InstanceFailureCategory` provides a stable reason family. The event includes
only the instance, blueprint, type, and correlation identifiers needed to join
it to operator diagnostics. It never exposes an exception, exception message,
filesystem path, child-process output, or mutable coordinator. A process exit
after successful Velocity registration uses phase `RUNTIME`; startup and
readiness exits remain distinct.

An operator `/sls reload all|blueprints|software` attempt emits one
`CatalogReloadEvent` after the atomic transaction commits or rejects. Committed
events expose only added, updated, and removed counts for blueprints and
software. Rejected events report zero committed changes and one sanitized
`CatalogReloadFailureCategory`: `IO`, `VALIDATION`, or `INTERNAL`. The event's
correlation ID joins it to operator logs without exposing definition IDs, file
names, paths, or parser messages.

`LobbyStatusEvent` provides a deduplicated snapshot of the effective lobby
service. It reports the primary and holding-lobby `LobbyServiceStatus` values
and the currently selected `LobbyRoute`: `PRIMARY`, `HOLDING`, or `NONE`.
Recovery can therefore be observed even while players remain safely routed to
the holding lobby. `available()` is false only for route `NONE`. Server names,
addresses, ports, child processes, retry details, and failure messages remain
internal.

Actual shared automatic-install jobs emit `SoftwareInstallationEvent` states:
`STARTED`, then exactly one `READY`, `FAILED`, or `CANCELLED`. Concurrent callers
waiting for the same target do not create duplicate event streams. Failures use
only `IO`, `INSTALLER`, or `INTERNAL`; shutdown cancellation uses `CANCELLED`.
Cache hits and requests rejected before work is accepted emit no installation
event. Software/version, source, and release channel are exposed, while cache
paths, download URLs, checksums, provider logs, progress text, and exception
messages remain internal.

Startup produces one `ReconciliationEvent` containing bounded outcome counts
and the startup correlation ID. Because reconciliation finishes before the API
becomes ready, this is the only retained event: it is replayed once to each
subscriber that registers after reconciliation. Its original sequence and
timestamp are preserved, and it is delivered before that subscriber's later
live events. No instance identifiers, paths, mount details, or failure messages
are exposed.

Closing SLS-LITE publishes one terminal `ApiShutdownEvent` to the subscribers
registered at shutdown, then drains the dispatcher within the normal bounded
shutdown deadline. The terminal notification reserves queue capacity by evicting at
most one older queued notification if a saturated extension has filled the
queue. As with other callbacks, a subscriber that blocks past the shutdown
deadline can prevent delivery. Subscriptions and new API operations are
rejected after closure.

## Trust And Compatibility

The Java API is for installed, trusted plugins and does not repeat player
permission checks. An extension exposing commands, plugin messages, or network
endpoints must authenticate and authorize its callers. SLS-LITE still enforces
its internal lifecycle, resource, instance-limit, ownership, and path-safety
rules.

Only packages below `net.slimelabs.slslite.api` are public. Do not import
`api.internal` or any other SLS-LITE implementation package. Public views never
expose mutable repositories, coordinators, child processes, filesystem paths,
Velocity connection results, or internal exceptions.

After the release gate freezes 1.x, API versions use `major.minor` semantics. A
major change may break source or binary compatibility. A minor change may add
new types, capabilities, event implementations, exception codes, or interface
default methods; existing method signatures and record shapes remain stable
within a major version.
Extensions should test the advertised version and capability set rather than
infer support from the SLS-LITE plugin version.

There is no authenticated HTTP administration/event API in this release. That
surface remains a separate candidate because it requires listener exposure,
authentication, rate limits, request-size limits, and deployment-specific TLS
or reverse-proxy guidance.
