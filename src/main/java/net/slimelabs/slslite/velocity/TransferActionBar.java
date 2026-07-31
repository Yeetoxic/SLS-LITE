package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.Player;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class TransferActionBar implements AutoCloseable {

  static final long FRAME_INTERVAL_MILLIS = 72;

  private static final Component[] FRAMES = {
    Component.text("▇▆▅▃▂▂▂▂▂", NamedTextColor.GOLD),
    Component.text("▆▇▆▅▃▂▂▂▂", NamedTextColor.GOLD),
    Component.text("▅▆▇▆▅▃▂▂▂", NamedTextColor.GOLD),
    Component.text("▃▅▆▇▆▅▃▂▂", NamedTextColor.GOLD),
    Component.text("▂▃▅▆▇▆▅▃▂", NamedTextColor.GOLD),
    Component.text("▂▂▃▅▆▇▆▅▃", NamedTextColor.GOLD),
    Component.text("▂▂▂▃▅▆▇▆▅", NamedTextColor.GOLD),
    Component.text("▂▂▂▂▃▅▆▇▆", NamedTextColor.GOLD),
    Component.text("▂▂▂▂▂▃▅▆▇", NamedTextColor.GOLD),
    Component.text("▂▂▂▂▃▅▆▇▆", NamedTextColor.GOLD),
    Component.text("▂▂▂▃▅▆▇▆▅", NamedTextColor.GOLD),
    Component.text("▂▃▅▆▇▆▅▃▂", NamedTextColor.GOLD),
    Component.text("▃▅▆▇▆▅▃▂▂", NamedTextColor.GOLD),
    Component.text("▅▆▇▆▅▃▂▂▂", NamedTextColor.GOLD),
    Component.text("▆▇▆▅▃▂▂▂▂", NamedTextColor.GOLD)
  };

  private final ScheduledExecutorService scheduler;
  private final Map<UUID, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

  public TransferActionBar(ScheduledExecutorService scheduler) {
    this.scheduler = scheduler;
  }

  public void start(Player player) {
    UUID playerId = player.getUniqueId();
    tasks.computeIfAbsent(
        playerId,
        ignored -> {
          AtomicInteger frame = new AtomicInteger();
          return scheduler.scheduleAtFixedRate(
              () -> {
                if (player.isActive()) {
                  player.sendActionBar(frame(frame.getAndIncrement()));
                }
              },
              0,
              FRAME_INTERVAL_MILLIS,
              TimeUnit.MILLISECONDS);
        });
  }

  public void joining(Player player, String serverName) {
    stop(player.getUniqueId());
    player.sendActionBar(Component.text("Joining " + serverName, NamedTextColor.GREEN));
  }

  public void forceJoining(Player player, String serverName) {
    stop(player.getUniqueId());
    player.sendActionBar(Component.text("Force joining " + serverName, NamedTextColor.YELLOW));
  }

  public void dequeued(Player player) {
    stop(player.getUniqueId());
    player.sendActionBar(Component.text("You have been dequeued.", NamedTextColor.RED));
  }

  public void stop(UUID playerId) {
    ScheduledFuture<?> task = tasks.remove(playerId);
    if (task != null) {
      task.cancel(false);
    }
  }

  static Component frame(int index) {
    return FRAMES[Math.floorMod(index, FRAMES.length)];
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
