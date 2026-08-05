package net.slimelabs.slslite.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.config.TransferActionBarConfig;
import org.junit.jupiter.api.Test;

class TransferActionBarTest {

  @Test
  void usesTheModernSlsLoadingFrames() {
    assertEquals(Component.text("▇▆▅▃▂▂▂▂▂", NamedTextColor.GOLD), TransferActionBar.frame(0));
    assertEquals(Component.text("▂▂▂▂▂▃▅▆▇", NamedTextColor.GOLD), TransferActionBar.frame(8));
    assertEquals(Component.text("▆▇▆▅▃▂▂▂▂", NamedTextColor.GOLD), TransferActionBar.frame(14));
    assertEquals(TransferActionBar.frame(0), TransferActionBar.frame(15));
    assertEquals(72, TransferActionBar.FRAME_INTERVAL_MILLIS);
  }

  @Test
  void animatesUntilJoiningMessageReplacesIt() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    List<Component> messages = new CopyOnWriteArrayList<>();
    UUID playerId = UUID.randomUUID();
    Player player = player(playerId, messages);
    try (TransferActionBar actionBar = new TransferActionBar(scheduler)) {
      actionBar.start(player);
      awaitMessages(messages, 3);

      assertTrue(actionBar.isRunning(playerId));
      actionBar.joining(player, "Smoke");

      assertFalse(actionBar.isRunning(playerId));
      assertEquals(Component.text("Joining Smoke", NamedTextColor.GREEN), messages.getLast());
      int stoppedAt = messages.size();
      Thread.sleep(TransferActionBar.FRAME_INTERVAL_MILLIS * 2);
      assertEquals(stoppedAt, messages.size());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void dequeueStopsAnimationAndShowsUpstreamFeedback() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    List<Component> messages = new CopyOnWriteArrayList<>();
    UUID playerId = UUID.randomUUID();
    Player player = player(playerId, messages);
    try (TransferActionBar actionBar = new TransferActionBar(scheduler)) {
      actionBar.start(player);
      awaitMessages(messages, 1);
      actionBar.dequeued(player);

      assertFalse(actionBar.isRunning(playerId));
      assertEquals(
          Component.text("You have been dequeued.", NamedTextColor.RED), messages.getLast());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void disabledConfigurationSendsNothingAndSchedulesNothing() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    List<Component> messages = new CopyOnWriteArrayList<>();
    UUID playerId = UUID.randomUUID();
    TransferActionBarConfig defaults = TransferActionBarConfig.defaults();
    TransferActionBarConfig disabled =
        new TransferActionBarConfig(
            false,
            defaults.joining(),
            defaults.forceJoining(),
            defaults.dequeued(),
            defaults.frames(),
            defaults.frameIntervalMillis());
    try (TransferActionBar actionBar = new TransferActionBar(scheduler, disabled)) {
      Player player = player(playerId, messages);
      actionBar.start(player);
      actionBar.joining(player, "Hidden");
      actionBar.forceJoining(player, "Hidden");
      actionBar.dequeued(player);
      Thread.sleep(100);

      assertFalse(actionBar.isRunning(playerId));
      assertTrue(messages.isEmpty());
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static Player player(UUID playerId, List<Component> messages) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> playerId;
                  case "isActive" -> true;
                  case "sendActionBar" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static void awaitMessages(List<Component> messages, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (messages.size() < expected && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(messages.size() >= expected);
  }

  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}
