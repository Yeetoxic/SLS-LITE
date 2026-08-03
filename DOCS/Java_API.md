# Java Extension API

SLS-LITE exposes a versioned in-process Java API for trusted Velocity plugins.
API version `1.0` supports capability discovery, immutable blueprint and
instance inspection, asynchronous start/stop/delete and player-matchmaking
requests, queue inspection/cancellation, and ordered instance lifecycle events.

This is not the Protocube HTTP API or an S4J endpoint. It cannot manage remote
nodes, containers, or another SLS installation.

## Dependency And Discovery

Build an extension against `sls-lite-<version>-api.jar` as a compile-only or
provided dependency. The API classifier contains only the supported public
package and its license; the full shaded plugin remains the runtime provider.
Declare `sls-lite` as a required Velocity plugin dependency so its plugin
instance is available before the extension initializes.

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
| `blueprints()` / `blueprint(id)` | Inspect immutable blueprint views. |
| `instances()` / `instance(id)` | Inspect immutable instance views. |
| `start(request)` | Start and register an instance. |
| `stop(id)` / `delete(id)` | Stop or delete through normal lifecycle rules. |
| `enqueue(request)` | Match and transfer an online player. |
| `queued(playerId)` / `dequeue(playerId)` | Inspect or cancel a queued request. |
| `subscribe(listener)` | Receive ordered lifecycle events. |

API 1.0 advertises `BLUEPRINT_INSPECTION`, `INSTANCE_INSPECTION`,
`INSTANCE_START`, `INSTANCE_STOP`, `INSTANCE_DELETE`, `PLAYER_QUEUE`, and
`LIFECYCLE_EVENTS`.

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

API versions use `major.minor` semantics. A major change may break source or
binary compatibility. A minor change may add new types, capabilities, event
implementations, exception codes, or interface default methods; existing
method signatures and record shapes remain stable within a major version.
Extensions should test the advertised version and capability set rather than
infer support from the SLS-LITE plugin version.

There is no authenticated HTTP administration/event API in this release. That
surface remains a separate candidate because it requires listener exposure,
authentication, rate limits, request-size limits, and deployment-specific TLS
or reverse-proxy guidance.
