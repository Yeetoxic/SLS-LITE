package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;

public final class FallbackLobbyProvider implements LobbyProvider {

  private final ProxyServer proxy;
  private final LobbyProvider primary;
  private final LobbyProvider limbo;
  private final Logger logger;
  private final ScheduledExecutorService healthScheduler;
  private final List<Consumer<RegisteredServer>> primaryReadyListeners =
      new CopyOnWriteArrayList<>();
  private final CompletableFuture<RegisteredServer> ready = new CompletableFuture<>();
  private final AtomicBoolean externalProbeInFlight = new AtomicBoolean();
  private volatile boolean primaryAvailable;
  private volatile String drainingPrimary;
  private volatile String suppressedPrimary;
  private volatile boolean dualFailureReported;
  private boolean started;
  private volatile boolean closed;

  public FallbackLobbyProvider(
      ProxyServer proxy, LobbyProvider primary, LobbyProvider limbo, Logger logger) {
    this(
        proxy,
        primary,
        limbo,
        logger,
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "sls-lite-primary-health");
              thread.setDaemon(true);
              return thread;
            }));
  }

  FallbackLobbyProvider(
      ProxyServer proxy,
      LobbyProvider primary,
      LobbyProvider limbo,
      Logger logger,
      ScheduledExecutorService healthScheduler) {
    this.proxy = proxy;
    this.primary = primary;
    this.limbo = limbo;
    this.logger = logger;
    this.healthScheduler = healthScheduler;
  }

  @Override
  public synchronized void start() {
    if (started || closed) {
      return;
    }
    started = true;
    primaryAvailable = primary.server().isPresent();
    AtomicInteger failures = new AtomicInteger();
    connectReadiness(primary, failures, true);
    connectReadiness(limbo, failures, false);
    limbo.start();
    primary.start();
    healthScheduler.scheduleWithFixedDelay(
        this::refreshPrimaryAvailability, 1, 1, TimeUnit.SECONDS);
  }

  @Override
  public Optional<RegisteredServer> server() {
    Optional<RegisteredServer> preferred = primaryAvailable ? primary.server() : Optional.empty();
    return preferred.isPresent() ? preferred : limbo.server();
  }

  @Override
  public CompletableFuture<RegisteredServer> readyFuture() {
    return ready;
  }

  @Override
  public LobbyStatus status() {
    if (primaryAvailable && primary.server().isPresent()) {
      return primary.status();
    }
    return limbo.status();
  }

  @Override
  public boolean isLobby(String serverName) {
    return primary.isLobby(serverName) || limbo.isLobby(serverName);
  }

  @Override
  public boolean ownsPrimaryLifecycle(String serverName) {
    return primary.ownsPrimaryLifecycle(serverName);
  }

  @Override
  public Optional<RegisteredServer> fallbackServer(String failedLobbyName) {
    if (primary.isLobby(failedLobbyName)) {
      markPrimaryUnavailable(failedLobbyName);
      return limbo.server();
    }
    if (limbo.isLobby(failedLobbyName)) {
      return primaryServer();
    }
    return Optional.empty();
  }

  @Override
  public boolean isHoldingLobby(String serverName) {
    return limbo.isLobby(serverName);
  }

  @Override
  public Optional<RegisteredServer> primaryServer() {
    return primaryAvailable ? primary.server() : Optional.empty();
  }

  @Override
  public void addPrimaryReadyListener(Consumer<RegisteredServer> listener) {
    primaryReadyListeners.add(listener);
    primaryServer().ifPresent(listener);
  }

  @Override
  public void markPrimaryUnavailable(String serverName) {
    if (primary.isLobby(serverName)) {
      primaryAvailable = false;
    }
  }

  @Override
  public Optional<SLSLimboDiagnostics> limboDiagnostics() {
    return limbo.limboDiagnostics();
  }

  @Override
  public boolean bothUnavailable() {
    return server().isEmpty();
  }

  @Override
  public CompletableFuture<Void> evacuate(String serverName) {
    if (isLobby(serverName)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("The active lobby cannot be evacuated"));
    }
    RegisteredServer source = proxy.getServer(serverName).orElse(null);
    if (source == null || source.getPlayersConnected().isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    RegisteredServer target = server().orElse(null);
    if (target == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("No lobby is ready"));
    }
    List<CompletableFuture<Void>> transfers =
        source.getPlayersConnected().stream().map(player -> transfer(player, target)).toList();
    return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new));
  }

  @Override
  public CompletableFuture<Void> evacuateForIntentionalStop(String serverName) {
    if (!isLobby(serverName)) {
      return evacuate(serverName);
    }
    RegisteredServer target;
    if (primary.isLobby(serverName)) {
      target = limbo.server().orElse(null);
    } else if (limbo.isLobby(serverName)) {
      target = primaryServer().orElse(null);
    } else {
      target = null;
    }
    RegisteredServer source = proxy.getServer(serverName).orElse(null);
    if (source == null || source.getPlayersConnected().isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    if (target == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("No alternate lobby is ready"));
    }
    List<CompletableFuture<Void>> transfers =
        source.getPlayersConnected().stream().map(player -> transfer(player, target)).toList();
    return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new));
  }

  @Override
  public synchronized boolean beginIntentionalStop(String serverName) {
    if (closed
        || drainingPrimary != null
        || suppressedPrimary != null
        || !primary.ownsPrimaryLifecycle(serverName)) {
      return false;
    }
    drainingPrimary = serverName;
    primaryAvailable = false;
    return true;
  }

  @Override
  public synchronized void cancelIntentionalStop(String serverName) {
    if (!serverName.equals(drainingPrimary) && !serverName.equals(suppressedPrimary)) {
      return;
    }
    primary.cancelIntentionalStop(serverName);
    drainingPrimary = null;
    suppressedPrimary = null;
    refreshPrimaryAvailability();
  }

  @Override
  public synchronized boolean prepareIntentionalStop(String serverName) {
    if (!serverName.equals(drainingPrimary)) {
      return false;
    }
    boolean prepared = primary.prepareIntentionalStop(serverName);
    drainingPrimary = null;
    suppressedPrimary = prepared ? serverName : null;
    primaryAvailable = false;
    if (!prepared) {
      refreshPrimaryAvailability();
    }
    return prepared;
  }

  @Override
  public CompletableFuture<RegisteredServer> cyclePrimary(String serverName, boolean reset) {
    synchronized (this) {
      if (closed
          || !serverName.equals(drainingPrimary)
          || !primary.ownsPrimaryLifecycle(serverName)) {
        return CompletableFuture.failedFuture(
            new IllegalStateException(
                "The active primary lobby is not prepared for " + (reset ? "reset" : "restart")));
      }
    }

    CompletableFuture<RegisteredServer> cycle = primary.cyclePrimary(serverName, reset);
    cycle.whenComplete(
        (server, failure) -> {
          synchronized (this) {
            if (!serverName.equals(drainingPrimary)) {
              return;
            }
            drainingPrimary = null;
            if (failure == null && !closed) {
              publishPrimaryReady(server);
            } else {
              primaryAvailable = false;
            }
          }
        });
    return cycle;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    primary.close();
    limbo.close();
    healthScheduler.shutdownNow();
    if (!ready.isDone()) {
      ready.completeExceptionally(new IllegalStateException("Lobby providers are shutting down"));
    }
  }

  private void connectReadiness(
      LobbyProvider provider, AtomicInteger failures, boolean primaryProvider) {
    provider
        .readyFuture()
        .whenComplete(
            (server, failure) -> {
              if (failure == null) {
                if (primaryProvider) {
                  publishPrimaryReady(server);
                }
                ready.complete(server);
              } else if (failures.incrementAndGet() == 2) {
                ready.completeExceptionally(
                    new IllegalStateException(
                        "The primary lobby and SLS-Limbo both failed", failure));
              }
            });
  }

  void refreshPrimaryAvailability() {
    if (closed || drainingPrimary != null) {
      return;
    }
    reportDualFailureState();
    RegisteredServer candidate = primary.server().orElse(null);
    if (candidate == null) {
      primaryAvailable = false;
      return;
    }
    if (primary.status() != LobbyStatus.EXTERNAL) {
      if (primary.status() == LobbyStatus.READY) {
        publishPrimaryReady(candidate);
      } else {
        primaryAvailable = false;
      }
      return;
    }
    if (primaryAvailable || !externalProbeInFlight.compareAndSet(false, true)) {
      return;
    }
    try {
      candidate
          .ping()
          .orTimeout(2, TimeUnit.SECONDS)
          .whenComplete(
              (ping, failure) -> {
                externalProbeInFlight.set(false);
                if (failure == null && !closed) {
                  publishPrimaryReady(candidate);
                }
              });
    } catch (RuntimeException exception) {
      externalProbeInFlight.set(false);
    }
  }

  private void reportDualFailureState() {
    boolean terminalFailure =
        primary.status() == LobbyStatus.OFFLINE && limbo.status() == LobbyStatus.OFFLINE;
    if (terminalFailure && !dualFailureReported) {
      dualFailureReported = true;
      logger.error(
          "No safe lobby is available: primary={} and SLS-Limbo={}. "
              + "Players without a backend will be disconnected. "
              + "Check /sls system and the preceding startup errors.",
          primary.status(),
          limbo.status());
    } else if (!terminalFailure && dualFailureReported) {
      dualFailureReported = false;
      logger.info(
          "Safe lobby service recovered: primary={}, SLS-Limbo={}",
          primary.status(),
          limbo.status());
    }
  }

  private void publishPrimaryReady(RegisteredServer server) {
    if (closed || drainingPrimary != null) {
      return;
    }
    boolean changed = !primaryAvailable;
    primaryAvailable = true;
    if (changed) {
      primaryReadyListeners.forEach(listener -> listener.accept(server));
    }
  }

  private static CompletableFuture<Void> transfer(Player player, RegisteredServer target) {
    return player
        .createConnectionRequest(target)
        .connect()
        .thenAccept(
            result -> {
              if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS
                  && result.getStatus() != ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                throw new CompletionException(
                    new IllegalStateException(
                        "Unable to move "
                            + player.getUsername()
                            + " to lobby: "
                            + result.getStatus()));
              }
            });
  }
}
