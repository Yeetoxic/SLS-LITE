package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandPermissions;
import net.slimelabs.slslite.security.AdminClaimService;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCommandHandlerTest {

    @TempDir
    Path temporaryDirectory;

    private AdministratorStore administrators;
    private AdminClaimService claims;

    @BeforeEach
    void setUp() throws Exception {
        administrators = new AdministratorStore(temporaryDirectory);
        administrators.initialize();
        claims = new AdminClaimService(
                administrators,
                true,
                false,
                Duration.ofMinutes(5)
        );
    }

    @Test
    void suggestionsPreserveSenderAndPermissionRules() {
        Player regular = player("Regular", Set.of(), new ArrayList<>());
        Player permitted = player(
                "Permitted",
                Set.of(CommandPermissions.ADMIN),
                new ArrayList<>()
        );
        ConsoleCommandSource console = console(new ArrayList<>());
        AdminCommandHandler handler = handler(List.of(regular, permitted));

        assertEquals(
                List.of("claim"),
                handler.suggestions(regular, new String[]{"admin", ""})
        );
        assertEquals(
                List.of("claim", "add", "list", "remove"),
                handler.suggestions(permitted, new String[]{"admin", ""})
        );
        assertEquals(
                List.of("add", "list", "remove", "code"),
                handler.suggestions(console, new String[]{"admin", ""})
        );
        assertEquals(
                List.of("Permitted", "Regular"),
                handler.suggestions(
                        permitted,
                        new String[]{"admin", "add", ""}
                )
        );
    }

    @Test
    void listPreservesPermissionDenialAndAdministratorOutput() throws Exception {
        List<Component> deniedMessages = new ArrayList<>();
        Player denied = player("Regular", Set.of(), deniedMessages);
        AdminCommandHandler handler = handler(List.of(denied));

        handler.execute(denied, new String[]{"admin", "list"});

        assertEquals(1, deniedMessages.size());
        assertTrue(plainText(deniedMessages.getFirst()).contains(
                "You do not have permission to list SLS-LITE administrators."
        ));

        administrators.add(UUID.randomUUID(), "ExistingAdmin");
        List<Component> permittedMessages = new ArrayList<>();
        Player permitted = player(
                "Permitted",
                Set.of(CommandPermissions.ADMIN),
                permittedMessages
        );

        handler.execute(permitted, new String[]{"admin", "list"});

        assertEquals(1, permittedMessages.size());
        assertTrue(plainText(permittedMessages.getFirst()).contains(
                "SLS-LITE administrators: ExistingAdmin"
        ));
    }

    @Test
    void claimCodeRemainsConsoleOnly() {
        List<Component> playerMessages = new ArrayList<>();
        Player player = player(
                "Permitted",
                Set.of(CommandPermissions.ADMIN),
                playerMessages
        );
        AdminCommandHandler handler = handler(List.of(player));

        handler.execute(player, new String[]{"admin", "code"});

        assertEquals(1, playerMessages.size());
        assertTrue(plainText(playerMessages.getFirst()).contains(
                "can only be generated from the proxy console"
        ));

        List<Component> consoleMessages = new ArrayList<>();
        handler.execute(console(consoleMessages), new String[]{"admin", "code"});

        assertEquals(1, consoleMessages.size());
        assertTrue(plainText(consoleMessages.getFirst()).contains(
                "Administrator claim code:"
        ));
    }

    private AdminCommandHandler handler(Collection<Player> players) {
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getAllPlayers" -> players;
                    case "getPlayer" -> players.stream()
                            .filter(player -> player.getUsername()
                                    .equalsIgnoreCase((String) arguments[0]))
                            .findFirst();
                    default -> defaultValue(method.getReturnType());
                }
        );
        return new AdminCommandHandler(
                proxy,
                administrators,
                claims,
                new CommandAuthorizer(administrators),
                LoggerFactory.getLogger(AdminCommandHandlerTest.class)
        );
    }

    private static Player player(
            String name,
            Set<String> permissions,
            List<Component> messages
    ) {
        UUID uniqueId = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getUsername" -> name;
                    case "getUniqueId" -> uniqueId;
                    case "hasPermission" -> permissions.contains(arguments[0]);
                    case "sendMessage" -> {
                        messages.add((Component) arguments[0]);
                        yield null;
                    }
                    case "getCurrentServer" -> Optional.empty();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static ConsoleCommandSource console(List<Component> messages) {
        return (ConsoleCommandSource) Proxy.newProxyInstance(
                ConsoleCommandSource.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSource.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        messages.add((Component) arguments[0]);
                        yield null;
                    }
                    case "hasPermission" -> false;
                    default -> defaultValue(method.getReturnType());
                }
        );
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
