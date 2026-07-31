package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityStatus;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SLSCommandSurfaceTest {

  @TempDir private Path temporaryDirectory;

  private SLSCommand command;

  @BeforeEach
  void setUp() throws Exception {
    AdministratorStore administrators = new AdministratorStore(temporaryDirectory);
    administrators.initialize();
    BlueprintRepository blueprints =
        new BlueprintRepository(temporaryDirectory.resolve("blueprints"));
    blueprints.install(
        new BlueprintRepository.Snapshot(
            Map.of(
                "arena",
                new Blueprint(
                    "arena", "Arena", "minigame", "paper-auto", "1.21.5", 1024, false, Map.of()))));
    command =
        new SLSCommand(
            null,
            blueprints,
            null,
            null,
            null,
            controller(),
            null,
            null,
            null,
            null,
            null,
            administrators,
            null,
            LoggerFactory.getLogger(SLSCommandSurfaceTest.class));
  }

  @Test
  void singularBlueprintDispatchAndCompletionUseThePinnedRoot() {
    List<Component> messages = new ArrayList<>();
    CommandSource permitted = source(Set.of("sls.command.blueprint"), messages);

    command.execute(invocation(permitted, "blueprint", "arena"));

    assertEquals(2, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("Blueprint minigame/arena"));
    assertEquals(
        List.of("arena"), command.suggestAsync(invocation(permitted, "blueprint", "")).join());
  }

  @Test
  void createUsesItsDedicatedPermissionAndPinnedTypeIdShape() {
    List<Component> messages = new ArrayList<>();
    AtomicReference<String> startedBlueprint = new AtomicReference<>();
    command = command(controllerThatRecordsCreate(startedBlueprint, new AtomicReference<>()));
    CommandSource permitted = source(Set.of("sls.command.create"), messages);

    command.execute(invocation(permitted, "create", "minigame", "arena"));

    assertEquals("arena", startedBlueprint.get());
    assertTrue(plainText(messages.getFirst()).contains("fixture create reached controller"));
    assertEquals(
        List.of("minigame"), command.suggestAsync(invocation(permitted, "create", "")).join());
    assertEquals(
        List.of("arena"),
        command.suggestAsync(invocation(permitted, "create", "minigame", "")).join());
  }

  @Test
  void createRejectsShorthandAndPassesSafeOverridesToTheController() {
    List<Component> shorthandMessages = new ArrayList<>();
    List<Component> createMessages = new ArrayList<>();
    AtomicReference<String> createdBlueprint = new AtomicReference<>();
    AtomicReference<InstanceLaunchOverrides> capturedOverrides = new AtomicReference<>();
    command = command(controllerThatRecordsCreate(createdBlueprint, capturedOverrides));
    CommandSource permitted = source(Set.of("sls.command.create"), shorthandMessages);

    command.execute(invocation(permitted, "create", "arena"));
    command.execute(
        invocation(
            source(Set.of("sls.command.create"), createMessages),
            "create",
            "minigame",
            "arena",
            "--memory=2048",
            "--save=true"));

    assertTrue(plainText(shorthandMessages.getFirst()).contains("/sls create <type | blueprint>"));
    assertEquals("arena", createdBlueprint.get());
    assertEquals(2048, capturedOverrides.get().memoryLimitMiB());
    assertEquals(true, capturedOverrides.get().save());
    assertTrue(plainText(createMessages.getFirst()).contains("fixture create reached controller"));
    assertTrue(
        command
            .suggestAsync(invocation(permitted, "create", "minigame", "arena", ""))
            .join()
            .contains("--memory="));
  }

  @Test
  void createCompletionIsHiddenWithoutCreatePermission() {
    CommandSource unpermitted = source(Set.of(), new ArrayList<>());

    assertEquals(List.of(), command.suggestAsync(invocation(unpermitted, "create", "")).join());
    assertEquals(
        List.of(), command.suggestAsync(invocation(unpermitted, "create", "minigame", "")).join());
  }

  @Test
  void rootSuggestionsExposeOnlyPublicCommandsWithoutPermission() {
    List<String> suggestions =
        command.suggestAsync(invocation(source(Set.of(), new ArrayList<>()), "")).join();

    assertTrue(
        suggestions.containsAll(
            List.of("admin", "dequeue", "find", "info", "join", "list", "registries", "version")));
    assertTrue(
        java.util.Collections.disjoint(suggestions, List.of("start", "stop", "reload", "system")));
  }

  @Test
  void administratorRootSuggestionsCoverThePinnedCommandContract() {
    List<String> suggestions =
        command
            .suggestAsync(
                invocation(source(Set.of(CommandPermissions.ADMIN), new ArrayList<>()), ""))
            .join();

    Set<String> expectedRoots = new HashSet<>();
    VSLSCommandContract.ADMIN_ROOT.forEach(
        entry ->
            expectedRoots.add(
                entry.substring(0, entry.indexOf(' ') < 0 ? entry.length() : entry.indexOf(' '))));
    assertTrue(suggestions.containsAll(expectedRoots));
  }

  @Test
  void builtInAdministratorAndConsoleReceiveAdministrativeRootSuggestions() throws Exception {
    UUID playerId = UUID.randomUUID();
    AdministratorStore administrators =
        new AdministratorStore(temporaryDirectory.resolve("built-in-administrators"));
    administrators.initialize();
    administrators.add(playerId, "BuiltIn");
    command = command(controller(), administrators);

    List<String> builtInSuggestions =
        command.suggestAsync(invocation(player(playerId, new ArrayList<>()), "")).join();
    List<String> consoleSuggestions =
        command.suggestAsync(invocation(console(new ArrayList<>()), "")).join();

    assertTrue(builtInSuggestions.containsAll(VSLSCommandContract.ADMIN_SUGGESTIONS));
    assertTrue(consoleSuggestions.containsAll(VSLSCommandContract.ADMIN_SUGGESTIONS));
  }

  @Test
  void emptyAndUnknownExecutionReturnTheSamePublicUsageSurface() {
    List<Component> emptyMessages = new ArrayList<>();
    List<Component> unknownMessages = new ArrayList<>();

    command.execute(invocation(source(Set.of(), emptyMessages)));
    command.execute(invocation(source(Set.of(), unknownMessages), "not-a-command"));

    assertEquals(2, emptyMessages.size());
    assertEquals(
        emptyMessages.stream().map(SLSCommandSurfaceTest::plainText).toList(),
        unknownMessages.stream().map(SLSCommandSurfaceTest::plainText).toList());
    assertTrue(
        plainText(emptyMessages.get(1)).contains("/sls <join | list | find | dequeue | version>"));
  }

  @Test
  void granularProviderHelpExposesOnlyItsAdministrativeBranch() {
    List<Component> messages = new ArrayList<>();

    command.execute(invocation(source(Set.of("sls.command.stop"), messages)));

    String usage = plainText(messages.getLast());
    assertTrue(usage.contains("stop"));
    assertTrue(!usage.contains("kill"));
    assertTrue(usage.contains("version"));
  }

  @Test
  void zeroArgumentPublicBranchesRejectTrailingInput() {
    for (String operation : List.of("list", "registries", "version")) {
      List<Component> messages = new ArrayList<>();

      command.execute(invocation(source(Set.of(), messages), operation, "unexpected"));

      assertEquals(1, messages.size(), operation);
      assertTrue(plainText(messages.getFirst()).contains("Usage: /sls " + operation), operation);
    }
  }

  @Test
  void distributedCommandReportsLocalModeInsteadOfSilentlyDisappearing() {
    List<Component> messages = new ArrayList<>();

    command.execute(invocation(source(Set.of(CommandPermissions.ADMIN), messages), "node"));

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("not available in local mode"));
    assertTrue(plainText(messages.getFirst()).contains("/sls system"));
    assertTrue(plainText(messages.getFirst()).contains("no daemon/node control plane"));
  }

  @Test
  void unavailableProcessSuspensionCommandsNameSafePersistentAlternatives() {
    List<Component> pauseMessages = new ArrayList<>();
    List<Component> resumeMessages = new ArrayList<>();

    command.execute(
        invocation(source(Set.of("sls.command.pause"), pauseMessages), "pause", "server.abcdef"));
    command.execute(
        invocation(
            source(Set.of("sls.command.resume"), resumeMessages), "resume", "server.abcdef"));

    assertTrue(plainText(pauseMessages.getFirst()).contains("/sls stop"));
    assertTrue(plainText(pauseMessages.getFirst()).contains("persistent instance"));
    assertTrue(plainText(resumeMessages.getFirst()).contains("/sls restart <server>"));
  }

  @Test
  void configReloadModifierExplainsTheSafeLocalAlternative() {
    List<Component> messages = new ArrayList<>();
    CommandSource source = source(Set.of("sls.command.reload"), messages);

    command.execute(invocation(source, "reload", "config"));

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("restart Velocity"));
    assertTrue(command.suggestAsync(invocation(source, "reload", "")).join().contains("config"));
  }

  @Test
  void remoteStatusModifierIsSuggestedOnlyWithStatusPermission() {
    assertEquals(
        List.of("remote"),
        command
            .suggestAsync(
                invocation(
                    source(Set.of("sls.command.status"), new ArrayList<>()),
                    "status",
                    "server.abcdef",
                    ""))
            .join());
    assertEquals(
        List.of(),
        command
            .suggestAsync(
                invocation(source(Set.of(), new ArrayList<>()), "status", "server.abcdef", ""))
            .join());
  }

  @Test
  void capabilityLinesIncludeDetailForConsoleSendersAndBoundLongValues() {
    String detail = "x".repeat(300);

    String text =
        plainText(
            SLSCommand.capabilityLine(
                new HostCapability("Instance filesystem", HostCapabilityStatus.PASS, detail)));

    assertTrue(text.contains("Instance filesystem: PASS - "));
    assertTrue(text.endsWith("…"));
    assertTrue(text.length() < detail.length());
  }

  private static SimpleCommand.Invocation invocation(CommandSource source, String... arguments) {
    return (SimpleCommand.Invocation)
        Proxy.newProxyInstance(
            SimpleCommand.Invocation.class.getClassLoader(),
            new Class<?>[] {SimpleCommand.Invocation.class},
            (proxy, method, values) ->
                switch (method.getName()) {
                  case "source" -> source;
                  case "arguments" -> arguments;
                  case "alias" -> "sls";
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static CommandSource source(Set<String> permissions, List<Component> messages) {
    return (CommandSource)
        Proxy.newProxyInstance(
            CommandSource.class.getClassLoader(),
            new Class<?>[] {CommandSource.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "hasPermission" -> permissions.contains(arguments[0]);
                  case "sendMessage" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
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

  private SLSCommand command(ServerController controller) {
    try {
      AdministratorStore administrators = new AdministratorStore(temporaryDirectory);
      administrators.initialize();
      return command(controller, administrators);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private SLSCommand command(ServerController controller, AdministratorStore administrators) {
    try {
      BlueprintRepository blueprints =
          new BlueprintRepository(temporaryDirectory.resolve("command-blueprints"));
      blueprints.install(
          new BlueprintRepository.Snapshot(
              Map.of(
                  "arena",
                  new Blueprint(
                      "arena",
                      "Arena",
                      "minigame",
                      "paper-auto",
                      "1.21.5",
                      1024,
                      false,
                      Map.of()))));
      return new SLSCommand(
          null,
          blueprints,
          null,
          null,
          null,
          controller,
          null,
          null,
          null,
          null,
          null,
          administrators,
          null,
          LoggerFactory.getLogger(SLSCommandSurfaceTest.class));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
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
                  case "getUsername" -> "BuiltIn";
                  case "hasPermission" -> false;
                  case "sendMessage" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static ConsoleCommandSource console(List<Component> messages) {
    return (ConsoleCommandSource)
        Proxy.newProxyInstance(
            ConsoleCommandSource.class.getClassLoader(),
            new Class<?>[] {ConsoleCommandSource.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "hasPermission" -> false;
                  case "sendMessage" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static ServerController controllerThatRecordsCreate(
      AtomicReference<String> startedBlueprint,
      AtomicReference<InstanceLaunchOverrides> capturedOverrides) {
    return (ServerController)
        Proxy.newProxyInstance(
            ServerController.class.getClassLoader(),
            new Class<?>[] {ServerController.class},
            (ignored, method, arguments) -> {
              if ("create".equals(method.getName())) {
                startedBlueprint.set((String) arguments[0]);
                capturedOverrides.set((InstanceLaunchOverrides) arguments[1]);
                throw new net.slimelabs.slslite.instance.InstanceOperationException(
                    "fixture create reached controller");
              }
              return switch (method.getName()) {
                case "getAll", "persistentInstanceIds" -> List.of();
                default -> defaultValue(method.getReturnType());
              };
            });
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
    if (type == char.class) {
      return '\0';
    }
    return null;
  }
}
