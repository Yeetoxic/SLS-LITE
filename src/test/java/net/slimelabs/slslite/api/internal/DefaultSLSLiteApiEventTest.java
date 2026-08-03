package net.slimelabs.slslite.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.slimelabs.slslite.api.ApiStatus;
import net.slimelabs.slslite.api.InstanceStatus;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.event.InstanceLifecycleEvent;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceState;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class DefaultSLSLiteApiEventTest {

  @Test
  void deliversOrderedEventsAndIsolatesSubscriberFailure() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    List<InstanceLifecycleEvent> received = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(3);
    api.subscribe(
        event -> {
          throw new IllegalStateException("bad extension");
        });
    var subscription =
        api.subscribe(
            event -> {
              received.add((InstanceLifecycleEvent) event);
              delivered.countDown();
            });
    InstanceLifecycle lifecycle = new InstanceLifecycle("arena.123", api::publish);

    lifecycle.transitionTo(InstanceState.PREPARING);
    lifecycle.transitionTo(InstanceState.STARTING);
    lifecycle.transitionTo(InstanceState.READY);

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(
        List.of(1L, 2L, 3L), received.stream().map(InstanceLifecycleEvent::sequence).toList());
    assertEquals(
        List.of(InstanceStatus.PREPARING, InstanceStatus.STARTING, InstanceStatus.READY),
        received.stream().map(InstanceLifecycleEvent::currentStatus).toList());
    subscription.close();
    subscription.close();
    api.close();
    assertThrows(SLSLiteApiException.class, () -> api.subscribe(ignored -> {}));
  }

  @Test
  void boundsSubscribersAndExposesStableShutdownState() {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    var subscriptions = new java.util.ArrayList<net.slimelabs.slslite.api.event.Subscription>();
    for (int index = 0; index < 128; index++) {
      subscriptions.add(api.subscribe(ignored -> {}));
    }

    SLSLiteApiException rejected =
        assertThrows(SLSLiteApiException.class, () -> api.subscribe(ignored -> {}));
    assertEquals(SLSLiteApiException.Code.REJECTED, rejected.code());
    api.close();
    api.close();
    assertEquals(ApiStatus.CLOSED, api.status());
    SLSLiteApiException closed = assertThrows(SLSLiteApiException.class, api::blueprints);
    assertEquals(SLSLiteApiException.Code.CLOSED, closed.code());
    assertTrue(api.ready().toCompletableFuture().isCompletedExceptionally());
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
