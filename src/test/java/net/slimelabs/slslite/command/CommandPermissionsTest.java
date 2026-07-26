package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPermissionsTest {

    @Test
    void administratorPermissionCoversEveryAdministrativeOperation() {
        CommandSource source = sourceWith(CommandPermissions.ADMIN);

        assertTrue(CommandPermissions.canAdminister(source, "start"));
        assertTrue(CommandPermissions.canAdminister(source, "reload"));
        assertTrue(CommandPermissions.canTargetOthers(source, "join"));
    }

    @Test
    void granularPermissionsAreAdditiveAliases() {
        assertTrue(CommandPermissions.canAdminister(
                sourceWith("sls.command.start"),
                "start"
        ));
        assertTrue(CommandPermissions.canTargetOthers(
                sourceWith("sls.command.join.others"),
                "join"
        ));
        assertFalse(CommandPermissions.canAdminister(
                sourceWith("sls.command.join.others"),
                "stop"
        ));
        assertFalse(CommandPermissions.canAdminister(
                sourceWith("sls.command.stop"),
                "stop.force"
        ));
        assertTrue(CommandPermissions.canAdminister(
                sourceWith("sls.command.stop.force"),
                "stop.force"
        ));
    }

    private static CommandSource sourceWith(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class},
                (proxy, method, arguments) -> {
                    if ("hasPermission".equals(method.getName())) {
                        return granted.contains(arguments[0]);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                }
        );
    }
}
