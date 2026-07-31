package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;

public final class CommandPermissions {

  public static final String ADMIN = "sls.command.admin";
  private static final String PREFIX = "sls.command.";

  private CommandPermissions() {}

  public static boolean canAdminister(CommandSource source, String operation) {
    return source.hasPermission(ADMIN) || source.hasPermission(PREFIX + operation);
  }

  public static boolean canTargetOthers(CommandSource source, String operation) {
    return canAdminister(source, operation) || source.hasPermission(PREFIX + operation + ".others");
  }
}
