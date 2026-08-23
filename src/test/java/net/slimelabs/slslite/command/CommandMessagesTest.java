package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ManagedInstanceTestFactory;
import net.slimelabs.slslite.instance.model.InstanceState;
import org.junit.jupiter.api.Test;

class CommandMessagesTest {

  @Test
  void prefixRetainsTheVSLSVisualIdentityWithoutTheRedundantProjectHover() {
    Component prefix = CommandMessages.prefix();

    assertEquals("[SLS] ", plainText(prefix));
    assertNull(prefix.hoverEvent());
  }

  @Test
  void usageMatchesTheVSLSArgumentGrammar() {
    Component usage = CommandMessages.usage("/sls", "join", "list", "find", "dequeue");

    assertEquals("[SLS] Usage: /sls <join | list | find | dequeue>", plainText(usage));
  }

  @Test
  void listFrameMatchesTheVSLSLayout() {
    assertEquals("---- SERVER LIST ----", plainText(CommandMessages.listHeader()));
    assertEquals("----------------", plainText(CommandMessages.listFooter()));
    assertEquals(
        TextDecoration.State.TRUE,
        CommandMessages.listFooter().decoration(TextDecoration.STRIKETHROUGH));
  }

  @Test
  void localStatesUseVSLSStatusNamesAndColors() {
    assertEquals("Running", CommandMessages.statusName(InstanceState.READY));
    assertEquals("Offline", CommandMessages.statusName(InstanceState.STOPPED));
    assertEquals("Starting", CommandMessages.statusName(InstanceState.STARTING));
    assertEquals(NamedTextColor.GREEN, CommandMessages.statusColor(InstanceState.READY));
    assertEquals(NamedTextColor.RED, CommandMessages.statusColor(InstanceState.FAILED));
    assertEquals(NamedTextColor.YELLOW, CommandMessages.statusColor(InstanceState.PREPARING));
  }

  @Test
  void commandContractTracksTheReviewedVSLSMainBranch() {
    assertEquals("main", VSLSCommandContract.UPSTREAM_BRANCH);
    assertEquals(
        List.of("join", "list", "find", "dequeue", "version"), VSLSCommandContract.PUBLIC_ROOT);
    assertFalse(VSLSCommandContract.ADMIN_ROOT.isEmpty());
  }

  @Test
  void blueprintHoverProvidesOperationalDetails() {
    Blueprint blueprint =
        new Blueprint(
            "blastoff",
            "Legacy Blastoff",
            "minigame",
            "paper-auto",
            "1.11.2",
            1024,
            8,
            Integer.MAX_VALUE,
            false,
            java.util.Map.of(),
            List.of(
                new BlueprintVolume(
                    "world", "worlds/minigames/blastoff", "/world", BlueprintVolume.Mode.COW)));

    Component component = CommandMessages.blueprint(blueprint, List.of());
    assertEquals(null, component.hoverEvent());
    assertEquals(null, component.clickEvent());
    assertEquals(2, component.children().size());
    Component interactiveName = component.children().get(0);
    Component summary = component.children().get(1);
    assertNotNull(interactiveName.hoverEvent());
    assertNotNull(interactiveName.clickEvent());
    assertEquals(null, summary.hoverEvent());
    assertEquals(null, summary.clickEvent());
    Component hover = (Component) interactiveName.hoverEvent().value();
    String text = plainText(hover);

    assertTrue(text.contains("Blueprint: minigame/blastoff"));
    assertTrue(text.contains("Software: paper-auto 1.11.2"));
    assertTrue(text.contains("Capacity: 8 players per instance"));
    assertTrue(text.contains("Instance limit: unlimited"));
    assertTrue(text.contains("world: worlds/minigames/blastoff -> /world [cow]"));

    String directDetails = plainText(CommandMessages.blueprintDetails(blueprint, List.of()));
    assertTrue(directDetails.contains("Blueprint: minigame/blastoff"));
    assertFalse(directDetails.contains("Click to prepare or join"));
  }

  @Test
  void fullServerConfirmationClearlyIdentifiesPlayersAndRunsTheForceCommand() {
    Blueprint blueprint =
        new Blueprint(
            "arena",
            "Arena",
            "minigame",
            "paper-auto",
            "1.21.8",
            1024,
            1,
            1,
            false,
            java.util.Map.of(),
            List.of());
    ManagedInstance instance =
        ManagedInstanceTestFactory.ready(
            "minigame.arena.01", blueprint, 25600, temporaryPath("confirmation"));
    Player joiningPlayer = player("Administrator");
    Player targetPlayer = player("TargetPlayer");

    Component message =
        CommandMessages.fullServerJoinConfirmation(joiningPlayer, targetPlayer, instance, 1, 1);

    assertEquals(
        "[SLS] Blueprint capacity reached: minigame.arena.01 is full (1/1).\n"
            + "Joining Administrator with TargetPlayer will exceed this blueprint's matchmaking limit. "
            + "[Join Anyway]",
        plainText(message));
    Component confirmation = findText(message, "[Join Anyway]");
    assertNotNull(confirmation);
    assertNotNull(confirmation.hoverEvent());
    assertEquals(ClickEvent.Action.RUN_COMMAND, confirmation.clickEvent().action());
    assertEquals(
        "/sls join player TargetPlayer --force",
        ((ClickEvent.Payload.Text) confirmation.clickEvent().payload()).value());
  }

  private static Path temporaryPath(String name) {
    return Path.of(System.getProperty("java.io.tmpdir"), "sls-lite-command-message-test", name);
  }

  private static Player player(String username) {
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
                  case "getCurrentServer" -> java.util.Optional.empty();
                  default -> null;
                });
  }

  private static Component findText(Component component, String text) {
    if (component instanceof TextComponent textComponent && text.equals(textComponent.content())) {
      return component;
    }
    for (Component child : component.children()) {
      Component match = findText(child, text);
      if (match != null) {
        return match;
      }
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
