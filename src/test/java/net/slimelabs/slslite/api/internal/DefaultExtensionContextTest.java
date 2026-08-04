package net.slimelabs.slslite.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.slimelabs.slslite.api.ExtensionContext;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.event.ApiShutdownEvent;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceState;
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

  private static InstanceLifecycle.Transition transition(String id) {
    return new InstanceLifecycle.Transition(
        id, InstanceState.CREATED, InstanceState.PREPARING, Instant.now());
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
