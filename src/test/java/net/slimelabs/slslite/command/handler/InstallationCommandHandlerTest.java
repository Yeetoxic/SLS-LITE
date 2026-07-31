package net.slimelabs.slslite.command.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandPermissions;
import net.slimelabs.slslite.install.InstallationKey;
import net.slimelabs.slslite.install.InstallationSnapshot;
import net.slimelabs.slslite.install.InstallationState;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationCommandHandlerTest {

  @TempDir Path temporaryDirectory;

  private CommandAuthorizer authorizer;

  @BeforeEach
  void setUp() throws Exception {
    AdministratorStore administrators = new AdministratorStore(temporaryDirectory);
    administrators.initialize();
    authorizer = new CommandAuthorizer(administrators);
  }

  @Test
  void unavailableServicePreservesPermissionAndAvailabilityMessages() {
    InstallationCommandHandler handler =
        new InstallationCommandHandler(
            null, null, (InstallationCommandHandler.InstallationStatusSource) null, authorizer);
    List<Component> deniedMessages = new ArrayList<>();
    handler.execute(source(Set.of(), deniedMessages), new String[] {"install", "info"});
    assertTrue(
        plainText(deniedMessages.getFirst())
            .contains("do not have permission to inspect software installation"));

    List<Component> permittedMessages = new ArrayList<>();
    handler.execute(
        source(Set.of(CommandPermissions.ADMIN), permittedMessages),
        new String[] {"install", "info"});
    assertTrue(
        plainText(permittedMessages.getFirst())
            .contains("/sls install is not available in this SLS-LITE build yet"));
  }

  @Test
  void suggestionsPreservePermissionAndSubcommandSurface() {
    InstallationCommandHandler handler =
        new InstallationCommandHandler(
            null, null, (InstallationCommandHandler.InstallationStatusSource) null, authorizer);

    assertEquals(
        List.of(),
        handler.suggestions(source(Set.of(), new ArrayList<>()), new String[] {"install", ""}));
    assertEquals(
        List.of("info", "logs", "warmup", "cleanup"),
        handler.suggestions(
            source(Set.of(CommandPermissions.ADMIN), new ArrayList<>()),
            new String[] {"install", ""}));
  }

  @Test
  void logsRemainBoundedToLatestTenRetainedLines() {
    List<String> logs =
        java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(index -> "line-" + index).toList();
    InstallationSnapshot snapshot =
        new InstallationSnapshot(
            new InstallationKey("paper", "1.21.1"),
            InstallationState.FAILED,
            "download failed",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:01Z"),
            logs);
    InstallationCommandHandler.InstallationStatusSource statuses =
        new InstallationCommandHandler.InstallationStatusSource() {
          @Override
          public List<InstallationSnapshot> snapshots() {
            return List.of(snapshot);
          }

          @Override
          public InstallationSnapshot snapshot(String softwareId, String version) {
            return "paper".equals(softwareId) && "1.21.1".equals(version) ? snapshot : null;
          }
        };
    InstallationCommandHandler handler =
        new InstallationCommandHandler(null, null, statuses, authorizer);
    List<Component> messages = new ArrayList<>();

    handler.execute(
        source(Set.of(CommandPermissions.ADMIN), messages),
        new String[] {"install", "logs", "paper", "1.21.1"});

    List<String> text = messages.stream().map(InstallationCommandHandlerTest::plainText).toList();
    assertEquals(12, text.size());
    assertTrue(text.get(1).contains("latest 10 of 12"));
    assertFalse(text.contains("line-1"));
    assertFalse(text.contains("line-2"));
    assertEquals("line-3", text.get(2));
    assertEquals("line-12", text.getLast());
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
