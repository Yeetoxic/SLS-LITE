package net.slimelabs.slslite.command.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandInstanceAccess;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.security.AdministratorStore;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class PlayerRoutingCommandHandlerTest {

  @TempDir Path temporaryDirectory;

  private PlayerRoutingCommandHandler handler;
  private LocalJoinService joinService;

  @BeforeEach
  void setUp() throws Exception {
    AdministratorStore administrators =
        new AdministratorStore(temporaryDirectory.resolve("security"));
    administrators.initialize();
    ProxyServer proxy = proxy(List.of(player("Zulu"), player("Alpha")));
    CommandAuthorizer authorizer = new CommandAuthorizer(administrators);
    BlueprintRepository blueprints =
        new BlueprintRepository(temporaryDirectory.resolve("blueprints"));
    ServerController controller = controller();
    joinService = new LocalJoinService(proxy, blueprints, controller, Duration.ofSeconds(30));
    handler =
        new PlayerRoutingCommandHandler(
            proxy,
            blueprints,
            joinService,
            authorizer,
            new CommandInstanceAccess(proxy, controller),
            LoggerFactory.getLogger(PlayerRoutingCommandHandlerTest.class));
  }

  @AfterEach
  void closeJoinService() {
    joinService.close();
  }

  @Test
  void publicCompletionsRemainSortedAndIncludePlayerRoute() {
    CommandSource source = source(Set.of(), new ArrayList<>());

    assertEquals(
        List.of("Alpha", "Zulu"), handler.suggestions(source, "find", new String[] {"find", ""}));
    assertEquals(List.of("player"), handler.suggestions(source, "join", new String[] {"join", ""}));
    assertEquals(
        List.of("Alpha", "Zulu"),
        handler.suggestions(source, "join", new String[] {"join", "player", ""}));
  }

  @Test
  void dequeueCompletionsRequireTargetOthersPermission() {
    assertEquals(
        List.of(),
        handler.suggestions(
            source(Set.of(), new ArrayList<>()), "dequeue", new String[] {"dequeue", ""}));
    assertEquals(
        List.of("all", "local", "Alpha", "Zulu"),
        handler.suggestions(
            source(Set.of("sls.command.dequeue.others"), new ArrayList<>()),
            "dequeue",
            new String[] {"dequeue", ""}));
  }

  @Test
  void missingPlayerPreservesFindError() {
    List<Component> messages = new ArrayList<>();

    handler.find(source(Set.of(), messages), new String[] {"find", "Missing"});

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("Player Missing was not found."));
  }

  @Test
  void consoleDequeueWithoutTargetPreservesError() {
    List<Component> messages = new ArrayList<>();

    handler.dequeue(consoleSource(Set.of(), messages), new String[] {"dequeue"});

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("Console must specify all or a player."));
  }

  @Test
  void consoleLocalDequeueDoesNotEmitFalseSuccess() {
    List<Component> messages = new ArrayList<>();

    handler.dequeue(
        consoleSource(Set.of("sls.command.dequeue.others"), messages),
        new String[] {"dequeue", "local"});

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("cannot use the local player selector"));
  }

  @Test
  void forceJoinRequiresAdministrativeJoinAccessBeforeResolvingTarget() {
    List<Component> messages = new ArrayList<>();

    handler.join(
        playerSource("Self", messages), new String[] {"join", "player", "Alpha", "--force"});

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("do not have permission"));
  }

  @Test
  void dequeuePreservesPinnedBranchSpecificEmptyFeedback() {
    List<Component> selfMessages = new ArrayList<>();
    List<Component> targetMessages = new ArrayList<>();
    List<Component> allMessages = new ArrayList<>();

    handler.dequeue(playerSource("Self", selfMessages), new String[] {"dequeue"});
    handler.dequeue(
        source(Set.of("sls.command.dequeue.others"), targetMessages),
        new String[] {"dequeue", "Alpha"});
    handler.dequeue(
        source(Set.of("sls.command.dequeue.others"), allMessages), new String[] {"dequeue", "all"});

    assertTrue(plainText(selfMessages.getFirst()).contains("You are not in queue."));
    assertTrue(plainText(targetMessages.getFirst()).contains("Alpha is not in queue."));
    assertTrue(plainText(allMessages.getFirst()).contains("Dequeued all players"));
  }

  private static Player player(String username) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getUsername" -> username;
                  case "getUniqueId" ->
                      UUID.nameUUIDFromBytes(
                          username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static Player playerSource(String username, List<Component> messages) {
    UUID playerId =
        UUID.nameUUIDFromBytes(username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getUsername" -> username;
                  case "getUniqueId" -> playerId;
                  case "sendMessage" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static ProxyServer proxy(List<Player> players) {
    return (ProxyServer)
        Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getAllPlayers" -> players;
                  case "getPlayer" -> {
                    Object requested = arguments[0];
                    yield players.stream()
                        .filter(
                            player ->
                                requested.equals(player.getUsername())
                                    || requested.equals(player.getUniqueId()))
                        .findFirst();
                  }
                  case "getServer" -> Optional.empty();
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static ServerController controller() {
    return (ServerController)
        Proxy.newProxyInstance(
            ServerController.class.getClassLoader(),
            new Class<?>[] {ServerController.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getAll", "persistentInstanceIds" -> List.of();
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static CommandSource source(Set<String> permissions, List<Component> messages) {
    return commandSource(CommandSource.class, permissions, messages);
  }

  private static ConsoleCommandSource consoleSource(
      Set<String> permissions, List<Component> messages) {
    return commandSource(ConsoleCommandSource.class, permissions, messages);
  }

  @SuppressWarnings("unchecked")
  private static <T extends CommandSource> T commandSource(
      Class<T> type, Set<String> permissions, List<Component> messages) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "hasPermission" -> permissions.contains(arguments[0]);
                  case "sendMessage" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static String plainText(Component component) {
    StringBuilder output = new StringBuilder();
    appendPlainText(component, output);
    return output.toString();
  }

  private static void appendPlainText(Component component, StringBuilder output) {
    if (component instanceof TextComponent text) {
      output.append(text.content());
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
