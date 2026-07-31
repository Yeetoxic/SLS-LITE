package net.slimelabs.slslite.instance.lifecycle;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.slimelabs.slslite.blueprint.BlueprintLifecyclePolicy;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.lobby.LobbyProvider;
import org.slf4j.Logger;

public final class IdleInstanceReaper implements AutoCloseable {

  private static final Duration SCAN_INTERVAL = Duration.ofSeconds(5);

  private final ProxyServer proxy;
  private final ServerController instances;
  private final IdleAdmissionControl admissions;
  private final LobbyProvider lobby;
  private final int defaultIdleShutdownSeconds;
  private final Logger logger;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Map<String, Instant> emptySince = new HashMap<>();

  public IdleInstanceReaper(
      ProxyServer proxy,
      ServerController instances,
      IdleAdmissionControl admissions,
      LobbyProvider lobby,
      int defaultIdleShutdownSeconds,
      Logger logger) {
    this(
        proxy,
        instances,
        admissions,
        lobby,
        defaultIdleShutdownSeconds,
        logger,
        Clock.systemUTC(),
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "sls-lite-idle-reaper");
              thread.setDaemon(true);
              return thread;
            }));
  }

  IdleInstanceReaper(
      ProxyServer proxy,
      ServerController instances,
      IdleAdmissionControl admissions,
      LobbyProvider lobby,
      int defaultIdleShutdownSeconds,
      Logger logger,
      Clock clock,
      ScheduledExecutorService scheduler) {
    if (defaultIdleShutdownSeconds < 0) {
      throw new IllegalArgumentException("defaultIdleShutdownSeconds must not be negative");
    }
    this.proxy = proxy;
    this.instances = instances;
    this.admissions = admissions;
    this.lobby = lobby;
    this.defaultIdleShutdownSeconds = defaultIdleShutdownSeconds;
    this.logger = logger;
    this.clock = clock;
    this.scheduler = scheduler;
  }

  public void start() {
    logger.info(
        "Idle cleanup scheduled every {} seconds (default timeout: {} seconds)",
        SCAN_INTERVAL.toSeconds(),
        defaultIdleShutdownSeconds);
    scheduler.scheduleWithFixedDelay(
        this::scanSafely, SCAN_INTERVAL.toSeconds(), SCAN_INTERVAL.toSeconds(), TimeUnit.SECONDS);
  }

  void scanNow() {
    Instant now = clock.instant();
    Set<String> activeIds = new HashSet<>();
    for (ManagedInstance instance : instances.getAll()) {
      activeIds.add(instance.id());
      evaluate(instance, now);
    }
    synchronized (emptySince) {
      emptySince.keySet().retainAll(activeIds);
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    synchronized (emptySince) {
      emptySince.clear();
    }
  }

  private void scanSafely() {
    try {
      scanNow();
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable exception) {
      logger.error("Idle instance scan failed", exception);
    }
  }

  private void evaluate(ManagedInstance instance, Instant now) {
    BlueprintLifecyclePolicy policy =
        BlueprintLifecyclePolicy.from(instance.blueprint(), defaultIdleShutdownSeconds);
    if (instance.state() != InstanceState.READY
        || lobby.isLobby(instance.id())
        || policy.keepAlive()) {
      forget(instance.id());
      return;
    }

    RegisteredServer server = proxy.getServer(instance.id()).orElse(null);
    if (server == null
        || !server.getPlayersConnected().isEmpty()
        || admissions.hasPendingJoin(instance.id())) {
      forget(instance.id());
      return;
    }

    Instant idleFrom;
    synchronized (emptySince) {
      idleFrom = emptySince.get(instance.id());
      if (idleFrom == null) {
        idleFrom = now;
        emptySince.put(instance.id(), idleFrom);
        logger.info(
            "Instance {} is empty; idle shutdown is due in {} seconds",
            instance.id(),
            policy.idleTimeout().toSeconds());
      }
    }
    if (Duration.between(idleFrom, now).compareTo(policy.idleTimeout()) < 0) {
      return;
    }
    if (!admissions.tryDrain(instance.id())) {
      forget(instance.id());
      return;
    }

    RegisteredServer rechecked = proxy.getServer(instance.id()).orElse(null);
    if (rechecked == null || !rechecked.getPlayersConnected().isEmpty()) {
      admissions.cancelDrain(instance.id());
      forget(instance.id());
      return;
    }

    try {
      logger.info(
          "Stopping idle instance {} after {} seconds",
          instance.id(),
          policy.idleTimeout().toSeconds());
      forget(instance.id());
      instances
          .stop(instance.id())
          .whenComplete(
              (exitCode, failure) -> {
                admissions.cancelDrain(instance.id());
                if (failure != null) {
                  logger.error("Unable to stop idle instance " + instance.id(), failure);
                }
              });
    } catch (InstanceOperationException exception) {
      admissions.cancelDrain(instance.id());
      logger.warn("Unable to stop idle instance {}: {}", instance.id(), exception.getMessage());
    }
  }

  private void forget(String instanceId) {
    synchronized (emptySince) {
      emptySince.remove(instanceId);
    }
  }
}
