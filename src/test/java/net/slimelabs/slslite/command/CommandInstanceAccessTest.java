package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.instance.ServerController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandInstanceAccessTest {

    @Test
    void persistentIdsRemainSortedAndDeduplicated() {
        CommandInstanceAccess access = new CommandInstanceAccess(
                proxy(),
                controller(List.of("zeta.abcdef", "alpha.abcdef", "zeta.abcdef"))
        );

        assertEquals(
                List.of("alpha.abcdef", "zeta.abcdef"),
                access.persistentIds()
        );
    }

    @Test
    void missingExplicitTargetPreservesCommandError() {
        List<Component> messages = new ArrayList<>();
        CommandInstanceAccess access = new CommandInstanceAccess(
                proxy(),
                controller(List.of())
        );

        assertNull(access.resolve(source(messages), "missing.abcdef"));

        assertEquals(1, messages.size());
        assertTrue(plainText(messages.getFirst()).contains(
                "No such server missing.abcdef"
        ));
    }

    private static ServerController controller(Collection<String> persistentIds) {
        return (ServerController) Proxy.newProxyInstance(
                ServerController.class.getClassLoader(),
                new Class<?>[]{ServerController.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getAll" -> List.of();
                    case "persistentInstanceIds" -> persistentIds;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static ProxyServer proxy() {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (ignored, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static CommandSource source(List<Component> messages) {
        return (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class},
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
