package net.slimelabs.slslite.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ManagedInstanceTestFactory;
import net.slimelabs.slslite.instance.ServerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class BlueprintJoinActionServiceTest {

  @TempDir Path temporaryDirectory;

  @Test
  void runsActionsOncePerManagedBackendTransitionAndCleansDisconnects() {
    Blueprint blueprint =
        new Blueprint(
            "game",
            "Game",
            "game",
            "paper",
            "1.21.11",
            512,
            20,
            1,
            false,
            Map.of(
                "vsls",
                Map.of(
                    "on-join",
                    List.of(
                        Map.of("run", "op {PLAYER_NAME}"),
                        Map.of("run", "say welcome {PLAYER_NAME} {PLAYER_UUID}")))));
    CapturingController controller = new CapturingController(instance("game.abc123", blueprint));
    BlueprintJoinActionService service = new BlueprintJoinActionService(controller, logger());
    UUID playerId = UUID.randomUUID();
    Player player = player(playerId, "QueueTester");
    RegisteredServer managed = server("game.abc123");
    RegisteredServer external = server("external");

    service.connected(player, managed);
    service.connected(player, managed);
    service.connected(player, external);
    service.connected(player, managed);
    service.disconnect(playerId);
    service.connected(player, managed);

    assertEquals(
        List.of(
            "op QueueTester",
            "say welcome QueueTester " + playerId,
            "op QueueTester",
            "say welcome QueueTester " + playerId,
            "op QueueTester",
            "say welcome QueueTester " + playerId),
        controller.commands);
  }

  private ManagedInstance instance(String id, Blueprint blueprint) {
    return ManagedInstanceTestFactory.preparing(
        id, blueprint, 25600, temporaryDirectory.resolve(id));
  }

  private static Player player(UUID id, String name) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> id;
                  case "getUsername" -> name;
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static RegisteredServer server(String name) {
    ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25600));
    return (RegisteredServer)
        Proxy.newProxyInstance(
            RegisteredServer.class.getClassLoader(),
            new Class<?>[] {RegisteredServer.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getServerInfo" -> info;
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static Logger logger() {
    return (Logger)
        Proxy.newProxyInstance(
            Logger.class.getClassLoader(),
            new Class<?>[] {Logger.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
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

  private static final class CapturingController implements ServerController {
    private final ManagedInstance instance;
    private final List<String> commands = new ArrayList<>();

    private CapturingController(ManagedInstance instance) {
      this.instance = instance;
    }

    @Override
    public ManagedInstance start(String blueprintId) {
      return instance;
    }

    @Override
    public Collection<ManagedInstance> getAll() {
      return List.of(instance);
    }

    @Override
    public ManagedInstance get(String instanceId) throws InstanceOperationException {
      if (!instance.id().equals(instanceId)) {
        throw new InstanceOperationException("Unknown instance");
      }
      return instance;
    }

    @Override
    public void sendCommand(String instanceId, String command) {
      commands.add(command);
    }

    @Override
    public CompletableFuture<Integer> stop(String instanceId) {
      return CompletableFuture.completedFuture(0);
    }

    @Override
    public void shutdown(Duration timeout) {}
  }
}
