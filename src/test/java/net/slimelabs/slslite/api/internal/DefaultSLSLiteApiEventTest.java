package net.slimelabs.slslite.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import net.slimelabs.slslite.api.ApiStatus;
import net.slimelabs.slslite.api.InstanceStatus;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.event.ApiShutdownEvent;
import net.slimelabs.slslite.api.event.CatalogReloadEvent;
import net.slimelabs.slslite.api.event.CatalogReloadFailureCategory;
import net.slimelabs.slslite.api.event.CatalogReloadScope;
import net.slimelabs.slslite.api.event.CatalogReloadStatus;
import net.slimelabs.slslite.api.event.InstanceFailureCategory;
import net.slimelabs.slslite.api.event.InstanceFailureEvent;
import net.slimelabs.slslite.api.event.InstanceFailurePhase;
import net.slimelabs.slslite.api.event.InstanceLifecycleEvent;
import net.slimelabs.slslite.api.event.LobbyRoute;
import net.slimelabs.slslite.api.event.LobbyServiceStatus;
import net.slimelabs.slslite.api.event.LobbyStatusEvent;
import net.slimelabs.slslite.api.event.MatchmakingStatus;
import net.slimelabs.slslite.api.event.PlayerMatchmakingEvent;
import net.slimelabs.slslite.api.event.ReconciliationEvent;
import net.slimelabs.slslite.api.event.SoftwareInstallationEvent;
import net.slimelabs.slslite.api.event.SoftwareInstallationFailureCategory;
import net.slimelabs.slslite.api.event.SoftwareInstallationSource;
import net.slimelabs.slslite.api.event.SoftwareInstallationStatus;
import net.slimelabs.slslite.api.event.SoftwareReleaseChannel;
import net.slimelabs.slslite.config.DefinitionReloader;
import net.slimelabs.slslite.install.InstallationKey;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.instance.diagnostics.FailurePhase;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciliationReport;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import net.slimelabs.slslite.software.SoftwareSource;
import net.slimelabs.slslite.velocity.LocalJoinService;
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

  @Test
  void mapsMatchmakingTransitionsIntoTheGlobalOrderedEventStream() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> received = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    api.subscribe(
        event -> {
          received.add(event);
          delivered.countDown();
        });
    api.publish(
        new InstanceLifecycle.Transition(
            "arena.123", InstanceState.CREATED, InstanceState.PREPARING, Instant.now()));
    UUID playerId = UUID.randomUUID();
    api.publish(
        new LocalJoinService.MatchmakingTransition(
            new LocalJoinService.QueueTicket(
                playerId, "QueueTester", "minigame", "arena", "arena.123", Instant.now()),
            true,
            LocalJoinService.MatchmakingTransitionStatus.QUEUED,
            Instant.now()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(List.of(1L, 2L), received.stream().map(event -> event.sequence()).toList());
    PlayerMatchmakingEvent matchmaking = (PlayerMatchmakingEvent) received.get(1);
    assertEquals(MatchmakingStatus.QUEUED, matchmaking.status());
    assertEquals(playerId, matchmaking.ticket().playerId());
    assertTrue(matchmaking.instanceCreated());
    api.close();
  }

  @Test
  void mapsSanitizedInstanceFailuresIntoTheGlobalOrderedEventStream() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> received = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    api.subscribe(
        event -> {
          received.add(event);
          delivered.countDown();
        });
    api.publish(
        new InstanceLifecycle.Transition(
            "arena.123", InstanceState.READY, InstanceState.FAILED, Instant.now()));
    api.publish(
        new net.slimelabs.slslite.instance.InstanceManager.InstanceFailureTransition(
            "arena.123",
            "arena",
            "minigame",
            "instance-test",
            FailurePhase.RUNTIME,
            net.slimelabs.slslite.instance.InstanceManager.FailureCategory.PROCESS,
            Instant.now()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(List.of(1L, 2L), received.stream().map(event -> event.sequence()).toList());
    InstanceFailureEvent failure = (InstanceFailureEvent) received.get(1);
    assertEquals(InstanceFailurePhase.RUNTIME, failure.phase());
    assertEquals(InstanceFailureCategory.PROCESS, failure.category());
    assertEquals("arena", failure.blueprintId());
    assertEquals("instance-test", failure.correlationId());
    api.close();
  }

  @Test
  void mapsBoundedCatalogReloadResultIntoTheGlobalOrderedEventStream() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> received = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    api.subscribe(
        event -> {
          received.add(event);
          delivered.countDown();
        });
    api.publish(
        new InstanceLifecycle.Transition(
            "arena.123", InstanceState.CREATED, InstanceState.PREPARING, Instant.now()));
    api.publishCatalogReload(
        new DefinitionReloader.DefinitionReloadTransition(
            "reload-test",
            DefinitionReloader.ReloadScope.ALL,
            DefinitionReloader.ReloadStatus.COMMITTED,
            DefinitionReloader.ReloadFailureCategory.NONE,
            1,
            2,
            3,
            4,
            5,
            6,
            Instant.now()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(List.of(1L, 2L), received.stream().map(event -> event.sequence()).toList());
    CatalogReloadEvent reload = (CatalogReloadEvent) received.get(1);
    assertEquals(CatalogReloadScope.ALL, reload.scope());
    assertEquals(CatalogReloadStatus.COMMITTED, reload.status());
    assertEquals(CatalogReloadFailureCategory.NONE, reload.failureCategory());
    assertEquals(6, reload.blueprints().changed());
    assertEquals(15, reload.software().changed());
    api.close();
  }

  @Test
  void mapsEffectiveLobbyStatusIntoTheGlobalOrderedEventStream() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> received = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    api.subscribe(
        event -> {
          received.add(event);
          delivered.countDown();
        });
    api.publish(
        new InstanceLifecycle.Transition(
            "lobby.123", InstanceState.STARTING, InstanceState.READY, Instant.now()));
    api.publish(
        new LobbyProvider.LobbyStatusTransition(
            new LobbyProvider.LobbyStatusSnapshot(
                LobbyStatus.RECOVERING, LobbyStatus.READY, LobbyProvider.LobbyRoute.HOLDING),
            Instant.now()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(List.of(1L, 2L), received.stream().map(event -> event.sequence()).toList());
    LobbyStatusEvent lobby = (LobbyStatusEvent) received.get(1);
    assertEquals(LobbyServiceStatus.RECOVERING, lobby.primaryStatus());
    assertEquals(LobbyServiceStatus.READY, lobby.holdingStatus());
    assertEquals(LobbyRoute.HOLDING, lobby.route());
    assertTrue(lobby.available());
    api.close();
  }

  @Test
  void mapsSanitizedSoftwareInstallationIntoTheGlobalOrderedEventStream() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> received = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    api.subscribe(
        event -> {
          received.add(event);
          delivered.countDown();
        });
    api.publish(
        new InstanceLifecycle.Transition(
            "lobby.123", InstanceState.STARTING, InstanceState.READY, Instant.now()));
    api.publish(
        new SoftwareInstallationService.InstallationTransition(
            new InstallationKey("paper", "1.21.11"),
            SoftwareSource.PAPER,
            net.slimelabs.slslite.software.SoftwareReleaseChannel.STABLE,
            SoftwareInstallationService.InstallationTransitionStatus.FAILED,
            SoftwareInstallationService.InstallationFailureCategory.IO,
            Instant.now()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(List.of(1L, 2L), received.stream().map(event -> event.sequence()).toList());
    SoftwareInstallationEvent installation = (SoftwareInstallationEvent) received.get(1);
    assertEquals("paper", installation.softwareId());
    assertEquals("1.21.11", installation.version());
    assertEquals(SoftwareInstallationSource.PAPER, installation.source());
    assertEquals(SoftwareReleaseChannel.STABLE, installation.channel());
    assertEquals(SoftwareInstallationStatus.FAILED, installation.status());
    assertEquals(SoftwareInstallationFailureCategory.IO, installation.failureCategory());
    api.close();
  }

  @Test
  void concurrentProducersDeliverInGlobalSequenceOrder() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    int eventCount = 100;
    List<Long> sequences = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(eventCount);
    api.subscribe(
        event -> {
          sequences.add(event.sequence());
          delivered.countDown();
        });
    var producers = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    for (int index = 0; index < eventCount; index++) {
      int instance = index;
      producers.execute(
          () -> {
            try {
              start.await();
              api.publish(
                  new InstanceLifecycle.Transition(
                      "arena." + instance,
                      InstanceState.CREATED,
                      InstanceState.PREPARING,
                      Instant.now()));
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
          });
    }
    start.countDown();
    producers.shutdown();

    assertTrue(producers.awaitTermination(2, TimeUnit.SECONDS));
    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(LongStream.rangeClosed(1, eventCount).boxed().toList(), sequences);
    api.close();
  }

  @Test
  void replaysRetainedReconciliationBeforeFutureLiveEvents() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    api.recordReconciliation(new InstanceReconciliationReport(1, 2, 3, 4, 5, 6), "startup-test");
    assertThrows(
        IllegalStateException.class,
        () ->
            api.recordReconciliation(
                new InstanceReconciliationReport(0, 0, 0, 0, 0, 0), "duplicate"));
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> received = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    api.subscribe(
        event -> {
          received.add(event);
          delivered.countDown();
        });
    api.publish(
        new InstanceLifecycle.Transition(
            "arena.123", InstanceState.CREATED, InstanceState.PREPARING, Instant.now()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    ReconciliationEvent reconciliation = (ReconciliationEvent) received.getFirst();
    assertEquals(1L, reconciliation.sequence());
    assertEquals(20, reconciliation.inspected());
    assertEquals("startup-test", reconciliation.correlationId());
    assertEquals(2L, received.get(1).sequence());
    api.close();
  }

  @Test
  void subscriptionDoesNotReceiveEventsQueuedBeforeItSubscribed() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    api.subscribe(
        event -> {
          if (event.sequence() == 1L) {
            firstEntered.countDown();
            try {
              releaseFirst.await();
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
          }
        });
    api.publish(
        new InstanceLifecycle.Transition(
            "arena.1", InstanceState.CREATED, InstanceState.PREPARING, Instant.now()));
    assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

    List<Long> lateSequences = new CopyOnWriteArrayList<>();
    CountDownLatch lateDelivered = new CountDownLatch(1);
    api.subscribe(
        event -> {
          lateSequences.add(event.sequence());
          lateDelivered.countDown();
        });
    releaseFirst.countDown();
    api.publish(
        new InstanceLifecycle.Transition(
            "arena.2", InstanceState.CREATED, InstanceState.PREPARING, Instant.now()));

    assertTrue(lateDelivered.await(2, TimeUnit.SECONDS));
    assertEquals(List.of(2L), lateSequences);
    api.close();
  }

  @Test
  void deliversFinalShutdownEventBeforeDispatcherCloses() throws Exception {
    DefaultSLSLiteApi api = new DefaultSLSLiteApi(proxy(), NOPLogger.NOP_LOGGER);
    List<net.slimelabs.slslite.api.event.SLSLiteEvent> received = new CopyOnWriteArrayList<>();
    api.subscribe(received::add);

    api.close();

    assertEquals(1, received.size());
    assertTrue(received.getFirst() instanceof ApiShutdownEvent);
    assertEquals(ApiStatus.CLOSED, api.status());
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
