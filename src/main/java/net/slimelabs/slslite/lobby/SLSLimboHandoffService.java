package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.velocity.TransferActionBar;
import org.slf4j.Logger;

public final class SLSLimboHandoffService implements AutoCloseable {

  private final LobbyProvider lobbies;
  private final Logger logger;
  private final Map<UUID, WaitingPlayer> waiting = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;
  private final TransferActionBar actionBar;
  private volatile boolean closed;

  public SLSLimboHandoffService(LobbyProvider lobbies, Logger logger) {
    this(
        lobbies,
        logger,
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "sls-lite-limbo-handoff");
              thread.setDaemon(true);
              return thread;
            }));
  }

  SLSLimboHandoffService(LobbyProvider lobbies, Logger logger, ScheduledExecutorService scheduler) {
    this.lobbies = lobbies;
    this.logger = logger;
    this.scheduler = scheduler;
    this.actionBar = new TransferActionBar(scheduler);
    lobbies.addPrimaryReadyListener(this::primaryReady);
  }

  public void awaitPrimary(Player player) {
    if (closed) {
      return;
    }
    waiting.computeIfAbsent(player.getUniqueId(), ignored -> new WaitingPlayer(player));
  }

  public void connected(Player player, RegisteredServer server) {
    UUID playerId = player.getUniqueId();
    String serverName = server.getServerInfo().getName();
    if (!lobbies.isHoldingLobby(serverName)) {
      if (lobbies.isLobby(serverName)) {
        remove(playerId);
      }
      return;
    }
    WaitingPlayer entry = waiting.computeIfAbsent(playerId, ignored -> new WaitingPlayer(player));
    player.sendActionBar(Component.text("Waiting for a safe destination...", NamedTextColor.GOLD));
    lobbies.primaryServer().ifPresent(primary -> transfer(entry, primary));
  }

  public void disconnect(UUID playerId) {
    remove(playerId);
  }

  int waitingCount() {
    return waiting.size();
  }

  @Override
  public void close() {
    closed = true;
    waiting.clear();
    actionBar.close();
    scheduler.shutdownNow();
  }

  private void primaryReady(RegisteredServer primary) {
    if (closed) {
      return;
    }
    waiting
        .values()
        .forEach(
            entry -> {
              boolean inLimbo =
                  entry
                      .player()
                      .getCurrentServer()
                      .map(connection -> connection.getServer())
                      .map(server -> server.getServerInfo().getName())
                      .map(lobbies::isHoldingLobby)
                      .orElse(false);
              if (inLimbo) {
                transfer(entry, primary);
              }
            });
  }

  private void transfer(WaitingPlayer entry, RegisteredServer primary) {
    if (System.nanoTime() < entry.nextAttemptNanos().get()) {
      return;
    }
    if (!entry.transferring().compareAndSet(false, true)) {
      return;
    }
    Player player = entry.player();
    actionBar.joining(player, primary.getServerInfo().getName());
    player
        .createConnectionRequest(primary)
        .connect()
        .whenComplete(
            (result, failure) -> {
              entry.transferring().set(false);
              if (failure != null) {
                transferFailed(
                    entry,
                    primary,
                    failure.getMessage(),
                    connectionFailureReason(failure.getMessage()));
                return;
              }
              if (result.getStatus() == ConnectionRequestBuilder.Status.SUCCESS
                  || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                remove(player.getUniqueId());
                return;
              }
              transferFailed(
                  entry,
                  primary,
                  result.getStatus().name(),
                  result
                      .getReasonComponent()
                      .orElseGet(() -> statusFailureReason(result.getStatus())));
            });
  }

  private void transferFailed(
      WaitingPlayer entry, RegisteredServer primary, String detail, Component playerReason) {
    Player player = entry.player();
    int failures = entry.failures().incrementAndGet();
    long retrySeconds = Math.min(60L, 10L << Math.min(failures - 1, 3));
    entry.nextAttemptNanos().set(System.nanoTime() + TimeUnit.SECONDS.toNanos(retrySeconds));
    lobbies.markPrimaryUnavailable(primary.getServerInfo().getName());
    actionBar.stop(player.getUniqueId());
    if (!closed && player.isActive()) {
      player.sendActionBar(
          Component.text("Destination unavailable; staying in SLS-Limbo.", NamedTextColor.YELLOW));
      if (entry.playerNotified().compareAndSet(false, true)) {
        player.sendMessage(failureNotice(primary.getServerInfo().getName(), playerReason));
      }
    }
    scheduleRetry(entry, retrySeconds);
    if (failures == 1) {
      logger.warn(
          "Unable to hand {} from SLS-Limbo to the primary lobby: {}. "
              + "Retrying in {} seconds; repeated failures are "
              + "logged at debug level.",
          player.getUsername(),
          detail,
          retrySeconds);
    } else {
      logger.debug(
          "SLS-Limbo handoff retry {} for {} failed: {}; next retry " + "in {} seconds",
          failures,
          player.getUsername(),
          detail,
          retrySeconds);
    }
  }

  private static Component failureNotice(String destination, Component reason) {
    return Component.text("SLS-LITE: ", NamedTextColor.GOLD)
        .append(Component.text("Unable to connect you to ", NamedTextColor.YELLOW))
        .append(Component.text(destination, NamedTextColor.WHITE))
        .append(Component.text(": ", NamedTextColor.YELLOW))
        .append(reason)
        .append(
            Component.text(
                " You will remain in SLS-Limbo while SLS-LITE retries.", NamedTextColor.YELLOW));
  }

  private static Component connectionFailureReason(String detail) {
    String normalized = Optional.ofNullable(detail).orElse("").toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("connection refused")) {
      return Component.text(
          "The destination server is offline or still starting.", NamedTextColor.RED);
    }
    if (normalized.contains("timed out") || normalized.contains("timeout")) {
      return Component.text("The destination server did not respond in time.", NamedTextColor.RED);
    }
    return Component.text("The destination server could not be reached.", NamedTextColor.RED);
  }

  private static Component statusFailureReason(ConnectionRequestBuilder.Status status) {
    return switch (status.name()) {
      case "SERVER_DISCONNECTED" ->
          Component.text("The destination server rejected the connection.", NamedTextColor.RED);
      case "CONNECTION_CANCELLED" ->
          Component.text(
              "The connection was cancelled by the proxy or another " + "plugin.",
              NamedTextColor.RED);
      case "CONNECTION_IN_PROGRESS" ->
          Component.text("Another connection attempt is already in progress.", NamedTextColor.RED);
      default ->
          Component.text("The destination is not accepting connections.", NamedTextColor.RED);
    };
  }

  private void scheduleRetry(WaitingPlayer entry, long delaySeconds) {
    ScheduledFuture<?> previous = entry.retryTask();
    if (previous != null) {
      previous.cancel(false);
    }
    entry.retryTask(
        scheduler.schedule(
            () -> {
              if (!closed && waiting.get(entry.player().getUniqueId()) == entry) {
                lobbies.primaryServer().ifPresent(primary -> transfer(entry, primary));
              }
            },
            delaySeconds,
            TimeUnit.SECONDS));
  }

  private void remove(UUID playerId) {
    WaitingPlayer removed = waiting.remove(playerId);
    if (removed != null && removed.retryTask() != null) {
      removed.retryTask().cancel(false);
    }
    actionBar.stop(playerId);
  }

  private static final class WaitingPlayer {

    private final Player player;
    private final AtomicBoolean transferring = new AtomicBoolean();
    private final AtomicBoolean playerNotified = new AtomicBoolean();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong nextAttemptNanos = new AtomicLong();
    private volatile ScheduledFuture<?> retryTask;

    private WaitingPlayer(Player player) {
      this.player = player;
    }

    private Player player() {
      return player;
    }

    private AtomicBoolean transferring() {
      return transferring;
    }

    private AtomicBoolean playerNotified() {
      return playerNotified;
    }

    private AtomicInteger failures() {
      return failures;
    }

    private AtomicLong nextAttemptNanos() {
      return nextAttemptNanos;
    }

    private ScheduledFuture<?> retryTask() {
      return retryTask;
    }

    private void retryTask(ScheduledFuture<?> task) {
      retryTask = task;
    }
  }
}
