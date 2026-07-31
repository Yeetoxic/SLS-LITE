package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.security.AdminClaimService;
import net.slimelabs.slslite.security.Administrator;
import net.slimelabs.slslite.security.AdministratorStore;
import org.slf4j.Logger;

/**
 * Owns the complete {@code /sls admin} execution and completion surface.
 */
public final class AdminCommandHandler {

  private final ProxyServer proxy;
  private final AdministratorStore administrators;
  private final AdminClaimService claims;
  private final CommandAuthorizer authorizer;
  private final Logger logger;

  public AdminCommandHandler(
      ProxyServer proxy,
      AdministratorStore administrators,
      AdminClaimService claims,
      CommandAuthorizer authorizer,
      Logger logger) {
    this.proxy = proxy;
    this.administrators = administrators;
    this.claims = claims;
    this.authorizer = authorizer;
    this.logger = logger;
  }

  public void execute(CommandSource source, String[] arguments) {
    if (arguments.length < 2) {
      sendUsage(source);
      return;
    }
    switch (arguments[1].toLowerCase(Locale.ROOT)) {
      case "claim" -> claim(source, arguments);
      case "add" -> add(source, arguments);
      case "remove" -> remove(source, arguments);
      case "list" -> list(source, arguments);
      case "code" -> issueCode(source, arguments);
      default -> sendUsage(source);
    }
  }

  public List<String> suggestions(CommandSource source, String[] arguments) {
    if (arguments.length == 2) {
      List<String> actions = new ArrayList<>();
      if (source instanceof Player) {
        actions.add("claim");
      }
      if (authorizer.canAdminister(source, "admin")) {
        actions.addAll(List.of("add", "list", "remove"));
      }
      if (source instanceof ConsoleCommandSource) {
        actions.add("code");
      }
      return List.copyOf(actions);
    }
    if (arguments.length == 3 && authorizer.canAdminister(source, "admin")) {
      if ("add".equalsIgnoreCase(arguments[1])) {
        return proxy.getAllPlayers().stream().map(Player::getUsername).sorted().toList();
      }
      if ("remove".equalsIgnoreCase(arguments[1])) {
        return administrators.list().stream().map(Administrator::lastKnownName).toList();
      }
    }
    return List.of();
  }

  private void claim(CommandSource source, String[] arguments) {
    if (!(source instanceof Player player)) {
      source.sendMessage(
          CommandMessages.message(
              "Only a player can claim in-game administration.", NamedTextColor.RED));
      return;
    }
    if (arguments.length != 3) {
      source.sendMessage(CommandMessages.usage("/sls admin claim", "<code>"));
      return;
    }
    try {
      AdminClaimService.ClaimResult result = claims.claim(player, arguments[2]);
      switch (result) {
        case CLAIMED ->
            source.sendMessage(
                CommandMessages.message(
                    "You are now an SLS-LITE administrator.", NamedTextColor.GREEN));
        case ALREADY_ADMINISTRATOR ->
            source.sendMessage(
                CommandMessages.message(
                    "You are already an SLS-LITE administrator.", NamedTextColor.YELLOW));
        case INVALID ->
            source.sendMessage(
                CommandMessages.message(
                    "That administrator claim code is invalid.", NamedTextColor.RED));
        case EXPIRED ->
            source.sendMessage(
                CommandMessages.message(
                    "That administrator claim code has expired. Generate another "
                        + "from the proxy console.",
                    NamedTextColor.RED));
        case NO_ACTIVE_CODE ->
            source.sendMessage(
                CommandMessages.message(
                    "No administrator claim code is active. Generate one from "
                        + "the proxy console.",
                    NamedTextColor.RED));
        case OFFLINE_MODE_BLOCKED ->
            source.sendMessage(
                CommandMessages.message(
                    "In-game administrator claims are disabled while Velocity "
                        + "is in offline mode.",
                    NamedTextColor.RED));
      }
    } catch (IOException exception) {
      logger.error("Unable to persist claimed SLS-LITE administrator", exception);
      source.sendMessage(
          CommandMessages.message("Unable to save the administrator claim.", NamedTextColor.RED));
    }
  }

  private void add(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "add an SLS-LITE administrator")) {
      return;
    }
    if (arguments.length != 3) {
      source.sendMessage(CommandMessages.usage("/sls admin add", "<online-player>"));
      return;
    }
    Player player = proxy.getPlayer(arguments[2]).orElse(null);
    if (player == null) {
      source.sendMessage(
          CommandMessages.message("Online player not found: " + arguments[2], NamedTextColor.RED));
      return;
    }
    try {
      claims.requireSecureIdentity();
      administrators.add(player.getUniqueId(), player.getUsername());
      source.sendMessage(
          CommandMessages.message(
              "Added SLS-LITE administrator " + player.getUsername() + ".", NamedTextColor.GREEN));
    } catch (AdminClaimService.InsecureOfflineModeException exception) {
      source.sendMessage(CommandMessages.message(exception.getMessage() + ".", NamedTextColor.RED));
    } catch (IOException exception) {
      logger.error("Unable to persist SLS-LITE administrator", exception);
      source.sendMessage(
          CommandMessages.message("Unable to save the administrator.", NamedTextColor.RED));
    }
  }

  private void remove(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "remove an SLS-LITE administrator")) {
      return;
    }
    if (arguments.length != 3) {
      source.sendMessage(CommandMessages.usage("/sls admin remove", "<player>"));
      return;
    }
    try {
      Optional<Administrator> removed = administrators.remove(arguments[2]);
      if (removed.isEmpty()) {
        source.sendMessage(
            CommandMessages.message(
                "SLS-LITE administrator not found: " + arguments[2], NamedTextColor.RED));
        return;
      }
      source.sendMessage(
          CommandMessages.message(
              "Removed SLS-LITE administrator " + removed.get().lastKnownName() + ".",
              NamedTextColor.GREEN));
    } catch (IOException exception) {
      logger.error("Unable to remove SLS-LITE administrator", exception);
      source.sendMessage(
          CommandMessages.message("Unable to save the administrator change.", NamedTextColor.RED));
    }
  }

  private void list(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "list SLS-LITE administrators")) {
      return;
    }
    if (arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls admin", "list"));
      return;
    }
    List<Administrator> current = administrators.list();
    String names =
        current.isEmpty()
            ? "none"
            : current.stream()
                .map(Administrator::lastKnownName)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    source.sendMessage(
        CommandMessages.message("SLS-LITE administrators: " + names, NamedTextColor.GRAY));
  }

  private void issueCode(CommandSource source, String[] arguments) {
    if (!(source instanceof ConsoleCommandSource)) {
      source.sendMessage(
          CommandMessages.message(
              "Administrator claim codes can only be generated from " + "the proxy console.",
              NamedTextColor.RED));
      return;
    }
    if (arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls admin", "code"));
      return;
    }
    try {
      String code = claims.issueCode();
      source.sendMessage(
          CommandMessages.message("Administrator claim code: " + code, NamedTextColor.GOLD));
    } catch (AdminClaimService.InsecureOfflineModeException exception) {
      source.sendMessage(
          CommandMessages.message(
              exception.getMessage()
                  + ". Enable online mode or explicitly allow insecure "
                  + "offline administrators in config.yml.",
              NamedTextColor.RED));
    }
  }

  private boolean requireAdmin(CommandSource source, String operation) {
    if (authorizer.canAdminister(source, "admin")) {
      return true;
    }
    source.sendMessage(
        CommandMessages.message(
            "You do not have permission to " + operation + ".", NamedTextColor.RED));
    return false;
  }

  private static void sendUsage(CommandSource source) {
    source.sendMessage(
        CommandMessages.usage(
            "/sls admin",
            "claim <code>",
            "add <online-player>",
            "remove <player>",
            "list",
            "code"));
  }
}
