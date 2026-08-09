package net.slimelabs.slslite.command;

import com.velocitypowered.api.proxy.Player;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Tracks online players who opted into bounded SLS debug chat and action-bar diagnostics.
 */
final class DebugPlayerRegistry implements AutoCloseable {

  private static final int MAX_DETAIL_LENGTH = 256;
  static final long ACTION_BAR_INTERVAL_MILLIS = 1000L;
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("MMM dd HH:mm:ss.SSS", Locale.ENGLISH);

  private final ConcurrentMap<UUID, Player> players = new ConcurrentHashMap<>();
  private final ActionBarFeed actionBars;
  private final Predicate<Player> authorized;
  private final ScheduledExecutorService scheduler;
  private ScheduledFuture<?> actionBarTask;
  private boolean closed;

  DebugPlayerRegistry() {
    this(ActionBarFeed.NONE, ignored -> true, null);
  }

  DebugPlayerRegistry(ActionBarFeed actionBars, Predicate<Player> authorized) {
    this(
        actionBars,
        authorized,
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "sls-lite-debug-action-bar");
              thread.setDaemon(true);
              return thread;
            }));
  }

  DebugPlayerRegistry(
      ActionBarFeed actionBars, Predicate<Player> authorized, ScheduledExecutorService scheduler) {
    this.actionBars = java.util.Objects.requireNonNull(actionBars, "actionBars");
    this.authorized = java.util.Objects.requireNonNull(authorized, "authorized");
    this.scheduler = scheduler;
  }

  synchronized boolean toggle(Player player) {
    if (closed) {
      return false;
    }
    UUID playerId = player.getUniqueId();
    AtomicBoolean enabled = new AtomicBoolean();
    players.compute(
        playerId,
        (ignored, current) -> {
          if (current != null) {
            return null;
          }
          enabled.set(true);
          return player;
        });
    if (enabled.get()) {
      startActionBars();
    } else {
      actionBars.remove(playerId);
      stopActionBarsIfIdle();
    }
    return enabled.get();
  }

  synchronized void remove(UUID playerId) {
    players.remove(playerId);
    actionBars.remove(playerId);
    stopActionBarsIfIdle();
  }

  synchronized void clear() {
    players.clear();
    actionBars.clear();
    stopActionBars();
  }

  int size() {
    return players.size();
  }

  void publish(String level, String detail) {
    String normalizedLevel =
        level == null || level.isBlank() ? "DEBUG" : level.strip().toUpperCase(Locale.ROOT);
    String normalizedDetail = normalizeDetail(detail);
    Component message =
        CommandMessages.prefix()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text(normalizedLevel, color(normalizedLevel)))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .append(Component.text(normalizedDetail, NamedTextColor.GRAY))
            .hoverEvent(
                HoverEvent.showText(
                    Component.text(
                        TIMESTAMP.format(LocalDateTime.now()), NamedTextColor.DARK_PURPLE)));
    players.forEach(
        (playerId, player) -> {
          if (!deliverable(player)) {
            remove(playerId);
            return;
          }
          player.sendMessage(message);
        });
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    clear();
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private synchronized void startActionBars() {
    if (scheduler == null || actionBarTask != null) {
      return;
    }
    actionBarTask =
        scheduler.scheduleAtFixedRate(
            this::publishActionBars, 0L, ACTION_BAR_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
  }

  private void publishActionBars() {
    players.forEach(
        (playerId, player) -> {
          if (!deliverable(player)) {
            remove(playerId);
            return;
          }
          try {
            actionBars.render(player).ifPresent(player::sendActionBar);
          } catch (RuntimeException ignored) {
            // A diagnostic surface must not terminate its bounded publisher.
          }
        });
  }

  private boolean deliverable(Player player) {
    try {
      return player.isActive() && authorized.test(player);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private synchronized void stopActionBarsIfIdle() {
    if (players.isEmpty()) {
      stopActionBars();
    }
  }

  private synchronized void stopActionBars() {
    if (actionBarTask != null) {
      actionBarTask.cancel(false);
      actionBarTask = null;
    }
  }

  private static String normalizeDetail(String detail) {
    String normalized = detail == null ? "" : detail.replace('\r', ' ').replace('\n', ' ').strip();
    if (normalized.length() <= MAX_DETAIL_LENGTH) {
      return normalized;
    }
    return normalized.substring(0, MAX_DETAIL_LENGTH - 1) + "\u2026";
  }

  private static NamedTextColor color(String level) {
    return switch (level) {
      case "INFO" -> NamedTextColor.AQUA;
      case "WARN" -> NamedTextColor.YELLOW;
      case "ERROR" -> NamedTextColor.DARK_RED;
      default -> NamedTextColor.GRAY;
    };
  }

  interface ActionBarFeed {

    ActionBarFeed NONE = player -> java.util.Optional.empty();

    java.util.Optional<Component> render(Player player);

    default void remove(UUID playerId) {}

    default void clear() {}
  }
}
