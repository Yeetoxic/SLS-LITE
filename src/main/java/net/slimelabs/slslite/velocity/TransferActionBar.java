package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.slimelabs.slslite.config.TransferActionBarConfig;

public final class TransferActionBar implements AutoCloseable {

  static final long FRAME_INTERVAL_MILLIS = 72;
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private static final List<Component> DEFAULT_FRAMES =
      TransferActionBarConfig.defaults().frames().stream().map(MINI_MESSAGE::deserialize).toList();

  private final ScheduledExecutorService scheduler;
  private final TransferActionBarConfig config;
  private final List<Component> frames;
  private final Map<UUID, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

  public TransferActionBar(ScheduledExecutorService scheduler) {
    this(scheduler, TransferActionBarConfig.defaults());
  }

  public TransferActionBar(ScheduledExecutorService scheduler, TransferActionBarConfig config) {
    this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
    this.config = java.util.Objects.requireNonNull(config, "config");
    frames = config.frames().stream().map(MINI_MESSAGE::deserialize).toList();
  }

  public void start(Player player) {
    if (!config.enabled()) {
      return;
    }
    UUID playerId = player.getUniqueId();
    tasks.computeIfAbsent(
        playerId,
        ignored -> {
          AtomicInteger frame = new AtomicInteger();
          return scheduler.scheduleAtFixedRate(
              () -> {
                if (player.isActive()) {
                  player.sendActionBar(configuredFrame(frame.getAndIncrement()));
                }
              },
              0,
              config.frameIntervalMillis(),
              TimeUnit.MILLISECONDS);
        });
  }

  public void joining(Player player, String serverName) {
    stop(player.getUniqueId());
    send(player, config.joining(), serverName);
  }

  public void forceJoining(Player player, String serverName) {
    stop(player.getUniqueId());
    send(player, config.forceJoining(), serverName);
  }

  public void dequeued(Player player) {
    stop(player.getUniqueId());
    if (config.enabled()) {
      player.sendActionBar(MINI_MESSAGE.deserialize(config.dequeued()));
    }
  }

  private void send(Player player, String template, String serverName) {
    if (config.enabled()) {
      player.sendActionBar(
          MINI_MESSAGE.deserialize(template, Placeholder.unparsed("server", serverName)));
    }
  }

  public void stop(UUID playerId) {
    ScheduledFuture<?> task = tasks.remove(playerId);
    if (task != null) {
      task.cancel(false);
    }
  }

  private Component configuredFrame(int index) {
    return frames.get(Math.floorMod(index, frames.size()));
  }

  static Component frame(int index) {
    return DEFAULT_FRAMES.get(Math.floorMod(index, DEFAULT_FRAMES.size()));
  }

  boolean isRunning(UUID playerId) {
    return tasks.containsKey(playerId);
  }

  @Override
  public void close() {
    tasks.values().forEach(task -> task.cancel(false));
    tasks.clear();
  }
}
