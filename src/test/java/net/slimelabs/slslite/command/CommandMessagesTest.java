package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
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
  void commandContractIsPinnedToTheReviewedVSLSRevision() {
    assertEquals("v0.2.0", VSLSCommandContract.RELEASE);
    assertEquals("8e8b1e3cf7d2157887764c16f11b8901f8241121", VSLSCommandContract.COMMIT);
    assertEquals(List.of("join", "list", "find", "dequeue"), VSLSCommandContract.PUBLIC_ROOT);
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
            2,
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
    assertTrue(text.contains("world: worlds/minigames/blastoff -> /world [cow]"));

    String directDetails = plainText(CommandMessages.blueprintDetails(blueprint, List.of()));
    assertTrue(directDetails.contains("Blueprint: minigame/blastoff"));
    assertFalse(directDetails.contains("Click to prepare or join"));
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
