package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandInstanceAccess;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

/**
 * Owns player-facing managed-server routing, queue removal, lookup, and completion.
 */
public final class PlayerRoutingCommandHandler {

  private final ProxyServer proxy;
  private final BlueprintRepository blueprints;
  private final LocalJoinService joinService;
  private final CommandAuthorizer authorizer;
  private final CommandInstanceAccess instances;
  private final Logger logger;

  public PlayerRoutingCommandHandler(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      LocalJoinService joinService,
      CommandAuthorizer authorizer,
      CommandInstanceAccess instances,
      Logger logger) {
    this.proxy = proxy;
    this.blueprints = blueprints;
    this.joinService = joinService;
    this.authorizer = authorizer;
    this.instances = instances;
    this.logger = logger;
  }

  public void join(CommandSource source, String[] arguments) {
    if (arguments.length >= 2 && "player".equalsIgnoreCase(arguments[1])) {
      joinPlayer(source, arguments);
      return;
    }
    if (arguments.length < 3 || arguments.length > 4) {
      source.sendMessage(CommandMessages.incorrectUsage());
      source.sendMessage(CommandMessages.usage("/sls join", "type", "player"));
      return;
    }
    List<Player> targets;
    if (arguments.length == 4) {
      if (!authorizer.canTargetOthers(source, "join")) {
        permissionDenied(source, "join other players");
        return;
      }
      targets = resolveTargets(source, arguments[3]);
    } else if (source instanceof Player player) {
      targets = List.of(player);
    } else {
      source.sendMessage(
          CommandMessages.message(
              "Console must specify a player: " + "/sls join <registry> <server> <player>",
              NamedTextColor.RED));
      return;
    }
    if (targets.isEmpty()) {
      return;
    }

    for (Player target : targets) {
      try {
        LocalJoinService.JoinAttempt attempt = joinService.join(target, arguments[1], arguments[2]);
        ManagedInstance instance = attempt.instance();
        logger.info(
            "Join requested by {} for player {} to {}/{} via {} " + "({}, queue expiry {})",
            commandSourceName(source),
            target.getUsername(),
            arguments[1],
            arguments[2],
            instance.id(),
            attempt.created() ? "created" : "existing",
            joinService.queueTimeoutSeconds(instance.blueprint()) == 0
                ? "disabled"
                : joinService.queueTimeoutSeconds(instance.blueprint()) + " seconds");
        String action = attempt.created() ? "Preparing" : "Queued for";
        source.sendMessage(
            CommandMessages.prefix()
                .append(
                    Component.text(
                        action + " ",
                        attempt.created() ? NamedTextColor.YELLOW : NamedTextColor.GREEN))
                .append(CommandMessages.player(target))
                .append(Component.text(" for ", NamedTextColor.GRAY))
                .append(CommandMessages.server(instance, instances.playersOn(instance).size()))
                .append(Component.text(".", NamedTextColor.GRAY)));
        if (source != target) {
          target.sendMessage(
              CommandMessages.message(
                  "Queued for " + arguments[1] + "/" + arguments[2] + ".", NamedTextColor.YELLOW));
        }
        attempt
            .connection()
            .whenComplete(
                (result, failure) -> reportConnection(source, target, instance, result, failure));
      } catch (InstanceOperationException exception) {
        source.sendMessage(
            CommandMessages.message(
                target.getUsername() + ": " + exception.getMessage(), NamedTextColor.RED));
      }
    }
  }

  public void dequeue(CommandSource source, String[] arguments) {
    if (arguments.length > 2) {
      source.sendMessage(CommandMessages.usage("/sls dequeue", "all", "local", "player"));
      return;
    }

    List<LocalJoinService.QueueTicket> removed;
    String selector = arguments.length == 1 ? "self" : arguments[1];
    if (arguments.length == 1) {
      if (!(source instanceof Player player)) {
        source.sendMessage(
            CommandMessages.message("Console must specify all or a player.", NamedTextColor.RED));
        return;
      }
      removed = joinService.dequeue(player.getUniqueId()).stream().toList();
    } else {
      if (!authorizer.canTargetOthers(source, "dequeue")) {
        permissionDenied(source, "dequeue other players");
        return;
      }
      String target = arguments[1];
      if ("all".equalsIgnoreCase(target)) {
        removed = joinService.dequeueAll();
      } else if ("local".equalsIgnoreCase(target)) {
        if (!(source instanceof Player player)) {
          source.sendMessage(
              CommandMessages.message(
                  "Console cannot use the local player selector.", NamedTextColor.RED));
          return;
        }
        if (player.getCurrentServer().isEmpty()) {
          source.sendMessage(
              CommandMessages.message(
                  "You are not connected to a backend server.", NamedTextColor.RED));
          return;
        }
        List<Player> local = resolveTargets(source, "local");
        removed = joinService.dequeue(local.stream().map(Player::getUniqueId).toList());
      } else {
        Player player = proxy.getPlayer(target).orElse(null);
        if (player == null) {
          sendPlayerNotFound(source, target);
          return;
        }
        removed = joinService.dequeue(player.getUniqueId()).stream().toList();
      }
    }

    if (removed.isEmpty()) {
      if ("self".equals(selector)) {
        source.sendMessage(CommandMessages.message("You are not in queue.", NamedTextColor.GRAY));
      } else if ("all".equalsIgnoreCase(selector)) {
        source.sendMessage(
            CommandMessages.message("Dequeued all players", NamedTextColor.DARK_AQUA));
      } else if ("local".equalsIgnoreCase(selector)) {
        source.sendMessage(
            CommandMessages.message("Dequeued local players", NamedTextColor.DARK_AQUA));
      } else {
        source.sendMessage(
            CommandMessages.message(selector + " is not in queue.", NamedTextColor.RED));
      }
      return;
    }
    if ("self".equals(selector)) {
      source.sendMessage(CommandMessages.message("You have been dequeued.", NamedTextColor.RED));
      return;
    }
    removed.forEach(
        ticket ->
            proxy
                .getPlayer(ticket.playerId())
                .ifPresent(
                    player ->
                        player.sendMessage(
                            CommandMessages.message(
                                "You have been dequeued.", NamedTextColor.RED))));
    String feedback =
        "all".equalsIgnoreCase(selector)
            ? "Dequeued all players"
            : "local".equalsIgnoreCase(selector)
                ? "Dequeued local players"
                : "Dequeued " + selector;
    source.sendMessage(CommandMessages.message(feedback, NamedTextColor.DARK_AQUA));
  }

  public void find(CommandSource source, String[] arguments) {
    if (arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls find", "player"));
      return;
    }
    Player player = proxy.getPlayer(arguments[1]).orElse(null);
    if (player == null) {
      sendPlayerNotFound(source, arguments[1]);
      return;
    }
    String current =
        player
            .getCurrentServer()
            .map(connection -> connection.getServerInfo().getName())
            .orElse(null);
    ManagedInstance instance = current == null ? null : instances.find(current);
    if (instance == null) {
      source.sendMessage(
          CommandMessages.prefix()
              .append(CommandMessages.player(player))
              .append(Component.text(" is not on an SLS-LITE server. ", NamedTextColor.RED))
              .append(
                  CommandMessages.labelValue(
                      "Current server:", current == null ? "none" : current)));
      sendActionBar(
          source,
          Component.text(
              player.getUsername() + " is not on an SLS-LITE server", NamedTextColor.RED));
      return;
    }
    source.sendMessage(
        CommandMessages.prefix()
            .append(CommandMessages.player(player))
            .append(Component.text(" is currently on ", NamedTextColor.GRAY))
            .append(CommandMessages.server(instance, instances.playersOn(instance).size())));
    sendActionBar(
        source,
        Component.text(player.getUsername() + " is on " + instance.id(), NamedTextColor.GREEN));
  }

  public List<String> suggestions(CommandSource source, String operation, String[] arguments) {
    if (arguments.length == 2) {
      return switch (operation) {
        case "find" -> playerNames();
        case "join" -> withPrefix("player", blueprints.getTypes().stream().sorted().toList());
        case "dequeue" -> authorizer.canTargetOthers(source, "dequeue") ? joinTargets() : List.of();
        default -> List.of();
      };
    }
    if (arguments.length == 3 && "join".equals(operation)) {
      if ("player".equalsIgnoreCase(arguments[1])) {
        return playerNames();
      }
      return blueprints.getByType(arguments[1]).stream().map(Blueprint::id).toList();
    }
    if (arguments.length == 4
        && "join".equals(operation)
        && authorizer.canTargetOthers(source, "join")) {
      if ("player".equalsIgnoreCase(arguments[1])) {
        return authorizer.canAdminister(source, "join") ? List.of("--force") : List.of();
      }
      return joinTargets();
    }
    return List.of();
  }

  private void joinPlayer(CommandSource source, String[] arguments) {
    if (arguments.length < 3 || arguments.length > 4) {
      source.sendMessage(CommandMessages.usage("/sls join player", "player"));
      return;
    }
    if (!(source instanceof Player player)) {
      source.sendMessage(
          CommandMessages.message(
              "Console cannot join another player's server.", NamedTextColor.RED));
      return;
    }
    boolean force = arguments.length == 4;
    if (force) {
      if (!"--force".equalsIgnoreCase(arguments[3])) {
        source.sendMessage(CommandMessages.usage("/sls join player", "player", "--force"));
        return;
      }
      if (!authorizer.canAdminister(source, "join")) {
        permissionDenied(source, "force a player join");
        return;
      }
    }

    Player target = proxy.getPlayer(arguments[2]).orElse(null);
    if (target == null) {
      sendPlayerNotFound(source, arguments[2]);
      return;
    }
    try {
      LocalJoinService.DirectJoin directJoin = joinService.joinPlayer(player, target, force);
      source.sendMessage(
          CommandMessages.prefix()
              .append(
                  Component.text(
                      force ? "Force joining " : "Joining ",
                      force ? NamedTextColor.YELLOW : NamedTextColor.GREEN))
              .append(CommandMessages.player(target))
              .append(Component.text(" on ", NamedTextColor.GRAY))
              .append(
                  CommandMessages.server(
                      directJoin.instance(), instances.playersOn(directJoin.instance()).size()))
              .append(Component.text(".", NamedTextColor.GRAY)));
      directJoin
          .connection()
          .whenComplete(
              (result, failure) ->
                  reportConnection(source, player, directJoin.instance(), result, failure));
    } catch (InstanceOperationException exception) {
      source.sendMessage(CommandMessages.message(exception.getMessage(), NamedTextColor.RED));
    }
  }

  private List<Player> resolveTargets(CommandSource source, String target) {
    if ("all".equalsIgnoreCase(target)) {
      return List.copyOf(proxy.getAllPlayers());
    }
    if ("local".equalsIgnoreCase(target)) {
      if (!(source instanceof Player player)) {
        source.sendMessage(
            CommandMessages.message(
                "Console cannot use the local player selector.", NamedTextColor.RED));
        return List.of();
      }
      return player
          .getCurrentServer()
          .map(connection -> List.copyOf(connection.getServer().getPlayersConnected()))
          .orElseGet(
              () -> {
                source.sendMessage(
                    CommandMessages.message(
                        "You are not connected to a backend server.", NamedTextColor.RED));
                return List.of();
              });
    }
    Player player = proxy.getPlayer(target).orElse(null);
    if (player == null) {
      sendPlayerNotFound(source, target);
      return List.of();
    }
    return List.of(player);
  }

  private void reportConnection(
      CommandSource source,
      Player target,
      ManagedInstance instance,
      ConnectionRequestBuilder.Result result,
      Throwable failure) {
    if (failure != null) {
      if (rootCause(failure) instanceof LocalJoinService.QueueCancelledException) {
        logger.info("Join for player {} to {} was cancelled", target.getUsername(), instance.id());
        return;
      }
      logger.warn(
          "Join failed for player {} to {}: {}",
          target.getUsername(),
          instance.id(),
          rootMessage(failure));
      source.sendMessage(
          CommandMessages.message(
              "Unable to connect " + target.getUsername() + ": " + rootMessage(failure),
              NamedTextColor.RED));
      return;
    }
    if (result.isSuccessful()
        || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
      logger.info(
          "Player {} connected to {} ({})",
          target.getUsername(),
          instance.id(),
          result.getStatus());
      source.sendMessage(
          CommandMessages.message(
              "Connected " + target.getUsername() + " to " + instance.id() + ".",
              NamedTextColor.GREEN));
      return;
    }
    logger.warn(
        "Connection failed for player {} to {}: {}",
        target.getUsername(),
        instance.id(),
        result.getStatus());
    source.sendMessage(
        CommandMessages.message(
            "Connection to " + instance.id() + " failed: " + result.getStatus(),
            NamedTextColor.RED));
  }

  private List<String> playerNames() {
    return proxy.getAllPlayers().stream().map(Player::getUsername).sorted().toList();
  }

  private List<String> joinTargets() {
    Set<String> targets = new LinkedHashSet<>();
    targets.add("all");
    targets.add("local");
    targets.addAll(playerNames());
    return List.copyOf(targets);
  }

  private static List<String> withPrefix(String prefix, List<String> values) {
    List<String> result = new ArrayList<>(values.size() + 1);
    result.add(prefix);
    result.addAll(values);
    return List.copyOf(result);
  }

  private static void permissionDenied(CommandSource source, String operation) {
    source.sendMessage(
        CommandMessages.message(
            "You do not have permission to " + operation + ".", NamedTextColor.RED));
  }

  private static void sendPlayerNotFound(CommandSource source, String playerName) {
    source.sendMessage(
        CommandMessages.prefix()
            .append(Component.text("Player ", NamedTextColor.RED))
            .append(Component.text(playerName, NamedTextColor.DARK_AQUA))
            .append(Component.text(" was not found.", NamedTextColor.RED)));
    sendActionBar(source, Component.text("Player not found: " + playerName, NamedTextColor.RED));
  }

  private static void sendActionBar(CommandSource source, Component component) {
    if (source instanceof Player player) {
      player.sendActionBar(component);
    }
  }

  private static String commandSourceName(CommandSource source) {
    if (source instanceof Player player) {
      return player.getUsername();
    }
    if (source instanceof ConsoleCommandSource) {
      return "CONSOLE";
    }
    return source.getClass().getSimpleName();
  }

  private static String rootMessage(Throwable throwable) {
    return net.slimelabs.slslite.log.DiagnosticMessages.rootCause(throwable);
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
