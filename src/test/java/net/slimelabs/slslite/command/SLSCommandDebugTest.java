package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SLSCommandDebugTest {

  @TempDir Path temporaryDirectory;

  private SLSCommand command;

  @BeforeEach
  void setUp() throws Exception {
    AdministratorStore administrators = new AdministratorStore(temporaryDirectory);
    administrators.initialize();
    command =
        new SLSCommand(
            null,
            null,
            null,
            null,
            null,
            emptyController(),
            null,
            null,
            null,
            null,
            null,
            administrators,
            null,
            LoggerFactory.getLogger(SLSCommandDebugTest.class));
  }

  @Test
  void debugRequiresItsDedicatedPermission() {
    List<Component> messages = new ArrayList<>();

    command.execute(invocation(player(Set.of(), messages, true), "debug"));

    assertEquals(1, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("permission"));
  }

  @Test
  void debugRejectsConsoleWithPinnedMessage() {
    List<Component> messages = new ArrayList<>();

    command.execute(invocation(source(Set.of("sls.command.debug"), messages), "debug"));

    assertEquals(1, messages.size());
    assertTrue(
        plainText(messages.getFirst()).contains("This command can only be run by a player."));
  }

  @Test
  void playerToggleUsesPinnedFeedbackAndReceivesSanitizedDispatchEvents() {
    List<Component> messages = new ArrayList<>();
    Player player = player(Set.of("sls.command.debug"), messages, true);

    command.execute(invocation(player, "debug"));
    assertTrue(plainText(messages.getLast()).contains("Debug mode enabled."));

    command.execute(invocation(player, "version"));
    Component debugMessage =
        messages.stream()
            .filter(message -> plainText(message).contains("[DEBUG]"))
            .findFirst()
            .orElseThrow();
    assertTrue(plainText(debugMessage).contains("Command /sls version requested by Debugger"));
    assertNotNull(debugMessage.hoverEvent());

    command.execute(invocation(player, "not-a-command", "super-secret-value"));
    assertTrue(
        messages.stream()
            .map(SLSCommandDebugTest::plainText)
            .noneMatch(message -> message.contains("super-secret-value")));

    command.execute(invocation(player, "debug"));
    assertTrue(plainText(messages.getLast()).contains("Debug mode disabled."));
    long debugMessagesBefore = debugMessageCount(messages);
    command.execute(invocation(player, "version"));
    assertEquals(debugMessagesBefore, debugMessageCount(messages));
  }

  @Test
  void disconnectRemovesSubscriptionAndInactivePlayersArePruned() {
    List<Component> messages = new ArrayList<>();
    UUID playerId = UUID.randomUUID();
    Player player = player(playerId, Set.of("sls.command.debug"), messages, true);
    command.execute(invocation(player, "debug"));
    command.disconnectDebugPlayer(playerId);

    command.execute(invocation(player, "version"));

    assertEquals(0, debugMessageCount(messages));

    DebugPlayerRegistry registry = new DebugPlayerRegistry();
    List<Component> inactiveMessages = new ArrayList<>();
    registry.toggle(player(UUID.randomUUID(), Set.of(), inactiveMessages, false));
    registry.publish("debug", "line one\n" + "x".repeat(400));
    assertEquals(0, registry.size());
    assertTrue(inactiveMessages.isEmpty());
  }

  private static long debugMessageCount(List<Component> messages) {
    return messages.stream().filter(message -> plainText(message).contains("[DEBUG]")).count();
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

  private static Player player(Set<String> permissions, List<Component> messages, boolean active) {
    return player(UUID.randomUUID(), permissions, messages, active);
  }

  private static Player player(
      UUID playerId, Set<String> permissions, List<Component> messages, boolean active) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> playerId;
                  case "getUsername" -> "Debugger";
                  case "hasPermission" -> permissions.contains(arguments[0]);
                  case "isActive" -> active;
                  case "sendMessage" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static ServerController emptyController() {
    return new ServerController() {
      @Override
      public ManagedInstance start(String blueprintId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Collection<ManagedInstance> getAll() {
        return List.of();
      }

      @Override
      public ManagedInstance get(String instanceId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.concurrent.CompletableFuture<Integer> stop(String instanceId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void shutdown(java.time.Duration timeout) {}
    };
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
}
