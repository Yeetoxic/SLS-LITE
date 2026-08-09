package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ManagedInstanceTestFactory;
import net.slimelabs.slslite.instance.ServerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DebugInstanceActionBarTest {

  @TempDir Path temporaryDirectory;

  @Test
  void memorySummaryComparesResidentUseToTheBlueprintBudget() {
    assertEquals(
        "512 / 1024 MiB (50%)",
        plainText(DebugInstanceActionBar.memory(1024, OptionalLong.of(512L * 1024L * 1024L))));
    assertEquals(
        "1280 / 1024 MiB (125%)",
        plainText(DebugInstanceActionBar.memory(1024, OptionalLong.of(1280L * 1024L * 1024L))));
    assertEquals(
        "n/a / 1024 MiB", plainText(DebugInstanceActionBar.memory(1024, OptionalLong.empty())));
  }

  @Test
  void cpuSummaryAllowsMulticoreUseAndRejectsInvalidSamples() {
    assertEquals(
        200.0,
        DebugInstanceActionBar.calculateCpuPercent(2_000_000_000L, 1_000_000_000L).orElseThrow(),
        0.001);
    assertTrue(DebugInstanceActionBar.calculateCpuPercent(-1L, 1L).isEmpty());
    assertTrue(DebugInstanceActionBar.calculateCpuPercent(1L, 0L).isEmpty());
  }

  @Test
  void currentManagedInstanceIncludesBudgetPlayersAndStateButUnmanagedDoesNot() {
    ManagedInstance instance = managedInstance("arena.abcdef");
    CommandInstanceAccess access =
        new CommandInstanceAccess(proxy(instance.id(), 2), controller(instance));
    DebugInstanceActionBar actionBar = new DebugInstanceActionBar(access, null);

    Component rendered = actionBar.render(playerOn(instance.id(), ignored -> {})).orElseThrow();

    String text = plainText(rendered);
    assertTrue(text.contains("SLS Debug"));
    assertTrue(text.contains(instance.id()));
    assertTrue(text.contains("RSS n/a / 1024 MiB"));
    assertTrue(text.contains("CPU n/a"));
    assertTrue(text.contains("Players 2/20"));
    assertTrue(text.contains("Running"));
    assertTrue(actionBar.render(playerOn("external-lobby", ignored -> {})).isEmpty());
  }

  @Test
  void boundedPublisherStopsWhenPermissionIsLost() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch delivered = new CountDownLatch(1);
    CountDownLatch permissionLost = new CountDownLatch(1);
    AtomicBoolean permitted = new AtomicBoolean(true);
    List<Component> actionBars = new java.util.concurrent.CopyOnWriteArrayList<>();
    Player player =
        playerOn(
            "arena.abcdef",
            component -> {
              actionBars.add(component);
              delivered.countDown();
            });
    DebugPlayerRegistry registry =
        new DebugPlayerRegistry(
            ignored -> Optional.of(Component.text("live usage")),
            ignored -> {
              boolean current = permitted.get();
              if (!current) {
                permissionLost.countDown();
              }
              return current;
            },
            scheduler);
    try {
      assertTrue(registry.toggle(player));
      assertTrue(delivered.await(2, TimeUnit.SECONDS));
      assertEquals("live usage", plainText(actionBars.getFirst()));

      permitted.set(false);
      assertTrue(permissionLost.await(2, TimeUnit.SECONDS));
      awaitSubscriberCount(registry, 0);
    } finally {
      registry.close();
    }
    assertTrue(scheduler.isShutdown());
  }

  private ManagedInstance managedInstance(String id) {
    Blueprint blueprint =
        new Blueprint("arena", "Arena", "minigame", "paper-auto", "26.2", 1024, false, Map.of());
    return ManagedInstanceTestFactory.ready(id, blueprint, 25601, temporaryDirectory.resolve(id));
  }

  private static ServerController controller(ManagedInstance instance) {
    return new ServerController() {
      @Override
      public ManagedInstance start(String blueprintId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Collection<ManagedInstance> getAll() {
        return List.of(instance);
      }

      @Override
      public ManagedInstance get(String instanceId) {
        return instance;
      }

      @Override
      public java.util.concurrent.CompletableFuture<Integer> stop(String instanceId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void shutdown(Duration timeout) {}
    };
  }

  private static ProxyServer proxy(String instanceId, int playerCount) {
    RegisteredServer server =
        (RegisteredServer)
            Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[] {RegisteredServer.class},
                (ignored, method, arguments) ->
                    "getPlayersConnected".equals(method.getName())
                        ? java.util.stream.IntStream.range(0, playerCount)
                            .mapToObj(index -> playerOn(instanceId, ignoredMessage -> {}))
                            .toList()
                        : defaultValue(method.getReturnType()));
    return (ProxyServer)
        Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (ignored, method, arguments) ->
                "getServer".equals(method.getName())
                    ? Optional.of(server)
                    : defaultValue(method.getReturnType()));
  }

  private static Player playerOn(
      String instanceId, java.util.function.Consumer<Component> actionBarConsumer) {
    ServerInfo info = new ServerInfo(instanceId, new InetSocketAddress("127.0.0.1", 25601));
    ServerConnection connection =
        (ServerConnection)
            Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(),
                new Class<?>[] {ServerConnection.class},
                (ignored, method, arguments) ->
                    "getServerInfo".equals(method.getName())
                        ? info
                        : defaultValue(method.getReturnType()));
    UUID playerId = UUID.randomUUID();
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> playerId;
                  case "getCurrentServer" -> Optional.of(connection);
                  case "isActive" -> true;
                  case "sendActionBar" -> {
                    actionBarConsumer.accept((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static void awaitSubscriberCount(DebugPlayerRegistry registry, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (registry.size() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, registry.size());
  }

  private static String plainText(Component component) {
    StringBuilder text = new StringBuilder();
    appendPlainText(component, text);
    return text.toString();
  }

  private static void appendPlainText(Component component, StringBuilder output) {
    if (component instanceof TextComponent textComponent) {
      output.append(textComponent.content());
    }
    component.children().forEach(child -> appendPlainText(child, output));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class || type == short.class || type == byte.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    return '\0';
  }
}
