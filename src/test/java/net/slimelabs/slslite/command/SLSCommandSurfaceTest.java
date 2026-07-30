package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityStatus;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SLSCommandSurfaceTest {

    @TempDir
    private Path temporaryDirectory;

    private SLSCommand command;

    @BeforeEach
    void setUp() throws Exception {
        AdministratorStore administrators =
                new AdministratorStore(temporaryDirectory);
        administrators.initialize();
        command = new SLSCommand(
                null, null, null, null, null, null, null, null, null, null,
                null, administrators, null,
                LoggerFactory.getLogger(SLSCommandSurfaceTest.class)
        );
    }

    @Test
    void rootSuggestionsExposeOnlyPublicCommandsWithoutPermission() {
        List<String> suggestions = command.suggestAsync(
                invocation(source(Set.of(), new ArrayList<>()), "")
        ).join();

        assertTrue(suggestions.containsAll(List.of(
                "admin", "dequeue", "find", "info", "join", "list",
                "registries", "version"
        )));
        assertTrue(java.util.Collections.disjoint(
                suggestions,
                List.of("start", "stop", "reload", "system")
        ));
    }

    @Test
    void administratorRootSuggestionsCoverThePinnedCommandContract() {
        List<String> suggestions = command.suggestAsync(
                invocation(source(Set.of(CommandPermissions.ADMIN), new ArrayList<>()), "")
        ).join();

        Set<String> expectedRoots = new HashSet<>();
        VSLSCommandContract.ADMIN_ROOT.forEach(entry ->
                expectedRoots.add(entry.substring(0, entry.indexOf(' ') < 0
                        ? entry.length()
                        : entry.indexOf(' ')))
        );
        assertTrue(suggestions.containsAll(expectedRoots));
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
                unknownMessages.stream().map(SLSCommandSurfaceTest::plainText).toList()
        );
        assertTrue(plainText(emptyMessages.get(1)).contains(
                "/sls <join | list | find | dequeue>"
        ));
    }

    @Test
    void distributedCommandReportsLocalModeInsteadOfSilentlyDisappearing() {
        List<Component> messages = new ArrayList<>();

        command.execute(invocation(
                source(Set.of(CommandPermissions.ADMIN), messages),
                "node"
        ));

        assertEquals(1, messages.size());
        assertTrue(plainText(messages.getFirst()).contains(
                "not available in local mode"
        ));
    }

    @Test
    void capabilityLinesIncludeDetailForConsoleSendersAndBoundLongValues() {
        String detail = "x".repeat(300);

        String text = plainText(SLSCommand.capabilityLine(new HostCapability(
                "Instance filesystem",
                HostCapabilityStatus.PASS,
                detail
        )));

        assertTrue(text.contains("Instance filesystem: PASS - "));
        assertTrue(text.endsWith("…"));
        assertTrue(text.length() < detail.length());
    }

    private static SimpleCommand.Invocation invocation(
            CommandSource source,
            String... arguments
    ) {
        return (SimpleCommand.Invocation) Proxy.newProxyInstance(
                SimpleCommand.Invocation.class.getClassLoader(),
                new Class<?>[]{SimpleCommand.Invocation.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "source" -> source;
                    case "arguments" -> arguments;
                    case "alias" -> "sls";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static CommandSource source(
            Set<String> permissions,
            List<Component> messages
    ) {
        return (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasPermission" -> permissions.contains(arguments[0]);
                    case "sendMessage" -> {
                        messages.add((Component) arguments[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
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
