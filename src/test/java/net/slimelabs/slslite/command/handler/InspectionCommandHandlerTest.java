package net.slimelabs.slslite.command.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandInstanceAccess;
import net.slimelabs.slslite.command.CommandPermissions;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InspectionCommandHandlerTest {

  @TempDir Path temporaryDirectory;

  private BlueprintRepository blueprints;
  private InspectionCommandHandler handler;

  @BeforeEach
  void setUp() throws Exception {
    AdministratorStore administrators =
        new AdministratorStore(temporaryDirectory.resolve("security"));
    administrators.initialize();
    ServerController instances = controller();
    blueprints = new BlueprintRepository(temporaryDirectory.resolve("blueprints"));
    CommandInstanceAccess access = new CommandInstanceAccess(proxy(), instances);
    handler =
        new InspectionCommandHandler(
            blueprints,
            null,
            null,
            instances,
            null,
            null,
            null,
            null,
            null,
            new CommandAuthorizer(administrators),
            access);
  }

  @Test
  void singularBlueprintUsesDedicatedPermissionAndPrintsDetails() {
    installBlueprint();
    List<Component> denied = new ArrayList<>();
    handler.blueprint(
        source(Set.of("sls.command.blueprints"), denied), new String[] {"blueprint", "arena"});
    assertTrue(plainText(denied.getFirst()).contains("do not have permission"));

    List<Component> usage = new ArrayList<>();
    handler.blueprint(source(Set.of("sls.command.blueprint"), usage), new String[] {"blueprint"});
    assertTrue(plainText(usage.getFirst()).contains("Usage: /sls blueprint <id>"));

    List<Component> missing = new ArrayList<>();
    handler.blueprint(
        source(Set.of("sls.command.blueprint"), missing), new String[] {"blueprint", "missing"});
    assertTrue(plainText(missing.getFirst()).contains("Blueprint not found: missing"));

    List<Component> details = new ArrayList<>();
    handler.blueprint(
        source(Set.of("sls.command.blueprint"), details), new String[] {"blueprint", "arena"});
    assertEquals(2, details.size());
    assertTrue(plainText(details.getFirst()).contains("Blueprint minigame/arena"));
    assertTrue(plainText(details.get(1)).contains("Software: paper-auto 1.21.5"));
    assertTrue(plainText(details.get(1)).contains("Persistence: ephemeral"));
  }

  @Test
  void singularBlueprintSuggestionsArePermissionFiltered() {
    installBlueprint();
    assertEquals(
        List.of(),
        handler.suggestions(
            source(Set.of(), new ArrayList<>()), "blueprint", new String[] {"blueprint", ""}));
    assertEquals(
        List.of(),
        handler.suggestions(
            source(Set.of("sls.command.blueprints"), new ArrayList<>()),
            "blueprint",
            new String[] {"blueprint", ""}));
    assertEquals(
        List.of("arena"),
        handler.suggestions(
            source(Set.of("sls.command.blueprint"), new ArrayList<>()),
            "blueprint",
            new String[] {"blueprint", ""}));
  }

  private void installBlueprint() {
    blueprints.install(
        new BlueprintRepository.Snapshot(
            Map.of(
                "arena",
                new Blueprint(
                    "arena", "Arena", "minigame", "paper-auto", "1.21.5", 1024, false, Map.of()))));
  }

  @Test
  void publicEmptyCatalogAndInstanceListsPreserveMessages() {
    List<Component> registryMessages = new ArrayList<>();
    handler.registries(source(Set.of(), registryMessages));
    assertTrue(plainText(registryMessages.getFirst()).contains("No registries are loaded."));

    List<Component> instanceMessages = new ArrayList<>();
    handler.list(source(Set.of(), instanceMessages));
    assertTrue(plainText(instanceMessages.getFirst()).contains("No servers found."));
  }

  @Test
  void protectedInspectionChecksPermissionBeforeTargetAccess() {
    List<Component> messages = new ArrayList<>();

    handler.status(source(Set.of(), messages), new String[] {"status", "missing.abcdef"});

    assertEquals(1, messages.size());
    assertTrue(
        plainText(messages.getFirst())
            .contains("do not have permission to inspect managed instances"));
  }

  @Test
  void protectedTargetSuggestionsRetainThisAlias() {
    assertEquals(
        List.of("this"),
        handler.suggestions(
            source(Set.of(CommandPermissions.ADMIN), new ArrayList<>()),
            "status",
            new String[] {"status", ""}));
    assertEquals(
        List.of(),
        handler.suggestions(
            source(Set.of(), new ArrayList<>()), "status", new String[] {"status", ""}));
    assertEquals(
        List.of("1"),
        handler.suggestions(
            source(Set.of(CommandPermissions.ADMIN), new ArrayList<>()),
            "logs",
            new String[] {"logs", "server.abcdef", ""}));
    assertEquals(
        List.of("50", "100", "max"),
        handler.suggestions(
            source(Set.of(CommandPermissions.ADMIN), new ArrayList<>()),
            "logs",
            new String[] {"logs", "server.abcdef", "1", ""}));
  }

  @Test
  void logValidationPreservesExistingInvalidNumberMessage() {
    List<Component> messages = new ArrayList<>();

    handler.logs(
        source(Set.of(CommandPermissions.ADMIN), messages),
        new String[] {"logs", "server.abcdef", "zero"});

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("Invalid number zero"));
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

  private static ProxyServer proxy() {
    return (ProxyServer)
        Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (ignored, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static CommandSource source(Set<String> permissions, List<Component> messages) {
    return (CommandSource)
        Proxy.newProxyInstance(
            CommandSource.class.getClassLoader(),
            new Class<?>[] {CommandSource.class},
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
