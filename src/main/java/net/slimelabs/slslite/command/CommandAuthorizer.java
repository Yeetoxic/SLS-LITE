package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import net.slimelabs.slslite.security.AdministratorStore;

public final class CommandAuthorizer {

    private final AdministratorStore administrators;

    public CommandAuthorizer(AdministratorStore administrators) {
        this.administrators = administrators;
    }

    public boolean isBuiltInAdministrator(CommandSource source) {
        return source instanceof ConsoleCommandSource
                || source instanceof Player player
                && administrators.contains(player.getUniqueId());
    }

    public boolean canAdminister(CommandSource source, String operation) {
        return isBuiltInAdministrator(source)
                || CommandPermissions.canAdminister(source, operation);
    }

    public boolean canTargetOthers(CommandSource source, String operation) {
        return isBuiltInAdministrator(source)
                || CommandPermissions.canTargetOthers(source, operation);
    }
}
