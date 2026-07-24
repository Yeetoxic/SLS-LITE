package net.slimelabs.slslite.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.instance.InstanceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CommandMessagesTest {

    @Test
    void prefixMatchesTheVSLSChatIdentity() {
        Component prefix = CommandMessages.prefix();

        assertEquals("[SLS] ", plainText(prefix));
        assertNotNull(prefix.hoverEvent());
    }

    @Test
    void usageMatchesTheVSLSArgumentGrammar() {
        Component usage = CommandMessages.usage(
                "/sls", "join", "list", "find", "dequeue"
        );

        assertEquals(
                "[SLS] Usage: /sls <join | list | find | dequeue>",
                plainText(usage)
        );
    }

    @Test
    void listFrameMatchesTheVSLSLayout() {
        assertEquals("---- SERVER LIST ----", plainText(CommandMessages.listHeader()));
        assertEquals("----------------", plainText(CommandMessages.listFooter()));
        assertEquals(
                TextDecoration.State.TRUE,
                CommandMessages.listFooter().decoration(TextDecoration.STRIKETHROUGH)
        );
    }

    @Test
    void localStatesUseVSLSStatusNamesAndColors() {
        assertEquals("Running", CommandMessages.statusName(InstanceState.READY));
        assertEquals("Offline", CommandMessages.statusName(InstanceState.STOPPED));
        assertEquals("Starting", CommandMessages.statusName(InstanceState.STARTING));
        assertEquals(NamedTextColor.GREEN, CommandMessages.statusColor(InstanceState.READY));
        assertEquals(NamedTextColor.RED, CommandMessages.statusColor(InstanceState.FAILED));
        assertEquals(NamedTextColor.YELLOW, CommandMessages.statusColor(
                InstanceState.PREPARING
        ));
    }

    @Test
    void commandContractIsPinnedToTheReviewedVSLSRevision() {
        assertEquals("v0.2.0", VSLSCommandContract.RELEASE);
        assertEquals(
                "8e8b1e3cf7d2157887764c16f11b8901f8241121",
                VSLSCommandContract.COMMIT
        );
        assertEquals(
                List.of("join", "list", "find", "dequeue"),
                VSLSCommandContract.PUBLIC_ROOT
        );
        assertFalse(VSLSCommandContract.ADMIN_ROOT.isEmpty());
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
