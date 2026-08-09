package net.slimelabs.slslite.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.slimelabs.slslite.api.BlueprintReadinessFinding;
import net.slimelabs.slslite.api.BlueprintReadinessStatus;
import net.slimelabs.slslite.api.BlueprintView;
import net.slimelabs.slslite.api.ExtensionContext;
import net.slimelabs.slslite.api.ExtensionDiagnosticFinding;
import net.slimelabs.slslite.api.ExtensionDiagnosticSeverity;
import net.slimelabs.slslite.api.InstanceReadyAction;
import net.slimelabs.slslite.api.InstanceStatus;
import net.slimelabs.slslite.api.InstanceView;
import net.slimelabs.slslite.api.NamespacedAnnotations;
import net.slimelabs.slslite.api.PostTransferAction;
import net.slimelabs.slslite.api.QueueTicket;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.event.ApiShutdownEvent;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class DefaultExtensionContextTest {

  @Test
  void contextOwnsSubscriptionsAndClosesIdempotently() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("Example.Plugin");
    List<Long> received = new CopyOnWriteArrayList<>();
    CountDownLatch first = new CountDownLatch(1);
    context.subscribe(
        event -> {
          received.add(event.sequence());
          first.countDown();
        });
    api.publish(transition("arena.1"));
    assertTrue(first.await(2, TimeUnit.SECONDS));

    context.close();
    context.close();
    CountDownLatch barrier = new CountDownLatch(1);
    api.subscribe(event -> barrier.countDown());
    api.publish(transition("arena.2"));

    assertTrue(barrier.await(2, TimeUnit.SECONDS));
    assertEquals(List.of(1L), received);
    assertTrue(context.closed());
    assertEquals("example.plugin", context.namespace());
    api.close();
  }

  @Test
  void contextSuppressesIncompleteFutureCallbacksAfterClose() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("future-owner");
    CompletableFuture<String> future = new CompletableFuture<>();
    AtomicInteger callbacks = new AtomicInteger();
    var registration = context.onComplete(future, (value, failure) -> callbacks.incrementAndGet());

    context.close();
    registration.close();
    future.complete("late");

    assertEquals(0, callbacks.get());
    assertThrows(
        SLSLiteApiException.class,
        () -> context.onComplete(new CompletableFuture<>(), (value, failure) -> {}));
    api.close();
  }

  @Test
  void apiShutdownDeliversTerminalEventThenClosesContextAndCallbacks() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("shutdown-owner");
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> events = new CopyOnWriteArrayList<>();
    CompletableFuture<String> future = new CompletableFuture<>();
    AtomicInteger callbacks = new AtomicInteger();
    context.subscribe(events::add);
    context.onComplete(future, (value, failure) -> callbacks.incrementAndGet());

    api.close();
    future.complete("late");

    assertEquals(1, events.size());
    assertTrue(events.getFirst() instanceof ApiShutdownEvent);
    assertTrue(context.closed());
    assertEquals(0, callbacks.get());
    SLSLiteApiException closed =
        assertThrows(SLSLiteApiException.class, () -> api.extension("another"));
    assertEquals(SLSLiteApiException.Code.CLOSED, closed.code());
  }

  @Test
  void namespaceIsUniqueWhileOwnedAndReusableAfterClose() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext first = api.extension("sample-plugin");

    SLSLiteApiException conflict =
        assertThrows(SLSLiteApiException.class, () -> api.extension("sample-plugin"));
    assertEquals(SLSLiteApiException.Code.CONFLICT, conflict.code());
    assertThrows(IllegalArgumentException.class, () -> api.extension("Bad Namespace"));
    first.close();

    ExtensionContext replacement = api.extension("sample-plugin");
    assertFalse(replacement.closed());
    api.close();
    assertTrue(replacement.closed());
  }

  @Test
  void boundsOwnedRegistrations() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("bounded");
    for (int index = 0; index < 256; index++) {
      context.onComplete(new CompletableFuture<>(), (value, failure) -> {});
    }

    SLSLiteApiException rejected =
        assertThrows(
            SLSLiteApiException.class,
            () -> context.onComplete(new CompletableFuture<>(), (value, failure) -> {}));
    assertEquals(SLSLiteApiException.Code.REJECTED, rejected.code());
    context.close();
    api.close();
  }

  @Test
  void exposesOnlyOwnedImmutableAnnotationNamespace() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("example-plugin");
    List<String> mutable = new ArrayList<>(List.of("one"));
    BlueprintView blueprint =
        blueprint(
            Map.of(
                "example-plugin", Map.of("modes", mutable),
                "another-plugin", Map.of("secret", "hidden")));

    NamespacedAnnotations annotations = context.annotations(blueprint);
    mutable.add("late");

    assertEquals(Set.of("modes"), annotations.values().keySet());
    assertEquals(List.of("one"), annotations.values().get("modes"));
    assertThrows(UnsupportedOperationException.class, () -> annotations.values().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new NamespacedAnnotations("example-plugin", Map.of("mutable", new AtomicInteger(1))));
    api.close();
  }

  @Test
  void contextOwnsOneNamespacedBlueprintReadinessChecker() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("example-plugin");
    api.extensionReadiness()
        .refreshViews(List.of(blueprint(Map.of("example-plugin", Map.of("required", "database")))));

    var registration =
        context.onBlueprintReadiness(
            (blueprint, annotations) ->
                List.of(
                    new BlueprintReadinessFinding(
                        "database",
                        BlueprintReadinessStatus.TEMPORARILY_UNAVAILABLE,
                        "database is offline")));

    assertEquals(1, api.extensionReadiness().findings("arena").size());
    SLSLiteApiException conflict =
        assertThrows(
            SLSLiteApiException.class,
            () -> context.onBlueprintReadiness((blueprint, annotations) -> List.of()));
    assertEquals(SLSLiteApiException.Code.CONFLICT, conflict.code());

    registration.close();
    assertTrue(api.extensionReadiness().findings("arena").isEmpty());
    context.onBlueprintReadiness((blueprint, annotations) -> List.of());
    context.close();
    assertTrue(api.extensionReadiness().findings("arena").isEmpty());
    api.close();
  }

  @Test
  void contextOwnsOneNamespacedDiagnosticContributor() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("example-plugin");
    context.onDiagnostics(
        () ->
            List.of(
                new ExtensionDiagnosticFinding(
                    "healthy", ExtensionDiagnosticSeverity.INFO, "ready")));

    SLSLiteApiException conflict =
        assertThrows(SLSLiteApiException.class, () -> context.onDiagnostics(java.util.List::of));
    assertEquals(SLSLiteApiException.Code.CONFLICT, conflict.code());

    context.close();
    ExtensionContext replacement = api.extension("example-plugin");
    replacement.onDiagnostics(java.util.List::of);
    api.close();
  }

  @Test
  void capturesBoundedActionsAtPublicationAndDisablesFailures() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    DefaultExtensionContext context = (DefaultExtensionContext) api.extension("example-plugin");
    BlueprintView blueprint = blueprint(Map.of("example-plugin", Map.of("mode", "ranked")));
    InstanceView instance =
        new InstanceView(
            "arena.1",
            "arena",
            "minigame",
            InstanceStatus.READY,
            25571,
            512,
            0,
            false,
            Instant.now(),
            "instance-test");
    List<InstanceReadyAction> ready = new CopyOnWriteArrayList<>();
    List<String> order = new CopyOnWriteArrayList<>();
    context.onInstanceReady(
        action -> {
          order.add("first");
          ready.add(action);
        });
    context.onInstanceReady(action -> order.add("second"));
    Runnable capturedReady = context.captureInstanceReady(instance, blueprint, Instant.now());
    context.onInstanceReady(ignored -> ready.add(null));
    capturedReady.run();

    assertEquals(1, ready.size());
    assertEquals(List.of("first", "second"), order);
    assertEquals("ranked", ready.getFirst().annotations().values().get("mode"));

    List<PostTransferAction> transferred = new CopyOnWriteArrayList<>();
    AtomicInteger failures = new AtomicInteger();
    context.onPostTransfer(transferred::add);
    context.onPostTransfer(
        ignored -> {
          failures.incrementAndGet();
          throw new IllegalStateException("bad action");
        });
    QueueTicket ticket =
        new QueueTicket(UUID.randomUUID(), "Player", "minigame", "arena", "arena.1", Instant.now());
    context.capturePostTransfer(ticket, false, blueprint, Instant.now()).run();
    context.capturePostTransfer(ticket, false, blueprint, Instant.now()).run();

    assertEquals(2, transferred.size());
    assertEquals(1, failures.get());
    api.close();
  }

  @Test
  void alreadyConnectedSuccessEventDoesNotDispatchPostTransferAction() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    ExtensionContext context = api.extension("example-plugin");
    AtomicInteger actions = new AtomicInteger();
    CountDownLatch eventDelivered = new CountDownLatch(1);
    context.onPostTransfer(ignored -> actions.incrementAndGet());
    context.subscribe(
        event -> {
          if (event instanceof net.slimelabs.slslite.api.event.PlayerMatchmakingEvent) {
            eventDelivered.countDown();
          }
        });
    QueueTicket ticket =
        new QueueTicket(UUID.randomUUID(), "Player", "minigame", "arena", "arena.1", Instant.now());

    api.publish(
        new LocalJoinService.MatchmakingTransition(
            new LocalJoinService.QueueTicket(
                ticket.playerId(),
                ticket.playerName(),
                ticket.registry(),
                ticket.blueprintId(),
                ticket.instanceId(),
                ticket.queuedAt()),
            false,
            LocalJoinService.MatchmakingTransitionStatus.TRANSFER_SUCCEEDED,
            Instant.now()));

    assertTrue(eventDelivered.await(2, TimeUnit.SECONDS));
    assertEquals(0, actions.get());
    api.close();
  }

  private static InstanceLifecycle.Transition transition(String id) {
    return new InstanceLifecycle.Transition(
        id, InstanceState.CREATED, InstanceState.PREPARING, Instant.now());
  }

  private static BlueprintView blueprint(Map<String, Object> annotations) {
    return new BlueprintView(
        "arena",
        "Arena",
        "minigame",
        "paper",
        "26.3",
        512,
        20,
        4,
        false,
        List.of(),
        false,
        Set.of(),
        annotations);
  }

  private static ProxyServer proxy() {
    return (ProxyServer)
        Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (instance, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}
