package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandInstanceAccess;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import org.slf4j.Logger;

final class PersistentInstanceCommandHandler {

  private final ServerController instances;
  private final LobbyProvider lobbyProvider;
  private final CommandAuthorizer authorizer;
  private final CommandInstanceAccess instanceAccess;
  private final Logger logger;

  PersistentInstanceCommandHandler(
      ServerController instances,
      LobbyProvider lobbyProvider,
      CommandAuthorizer authorizer,
      CommandInstanceAccess instanceAccess,
      Logger logger) {
    this.instances = instances;
    this.lobbyProvider = lobbyProvider;
    this.authorizer = authorizer;
    this.instanceAccess = instanceAccess;
    this.logger = logger;
  }

  void restart(CommandSource source, String[] arguments) {
    cycle(source, arguments, false);
  }

  void reset(CommandSource source, String[] arguments) {
    cycle(source, arguments, true);
  }

  private void cycle(CommandSource source, String[] arguments, boolean reset) {
    String operation = reset ? "reset" : "restart";
    if (!requireAdmin(source, operation, operation + " persistent servers")) {
      return;
    }
    if (arguments.length < 1
        || arguments.length > 3
        || arguments.length == 3 && !"--force".equalsIgnoreCase(arguments[2])) {
      source.sendMessage(CommandMessages.usage("/sls " + operation, "server", "server --force"));
      return;
    }
    boolean force = arguments.length == 3;
    if (force
        && !requireAdmin(
            source, operation + ".force", "force-" + operation + " protected managed servers")) {
      return;
    }

    String instanceId = arguments.length == 1 ? "this" : arguments[1];
    ManagedInstance active;
    if ("this".equalsIgnoreCase(instanceId)) {
      active = instanceAccess.resolve(source, instanceId);
      if (active == null) {
        return;
      }
      instanceId = active.id();
    } else {
      active = instanceAccess.find(instanceId);
    }
    boolean protectedLobby = lobbyProvider.isLobby(instanceId);
    if (protectedLobby && !force) {
      source.sendMessage(
          CommandMessages.message(
              "The active lobby is protected. Use /sls "
                  + operation
                  + " "
                  + instanceId
                  + " --force to "
                  + operation
                  + " it intentionally.",
              NamedTextColor.RED));
      return;
    }
    if (force && !protectedLobby) {
      source.sendMessage(
          CommandMessages.message(
              instanceId + " is not protected; use /sls " + operation + " " + instanceId + ".",
              NamedTextColor.YELLOW));
      return;
    }
    if (protectedLobby) {
      if (active == null) {
        cycleUnavailableProtectedLobby(source, instanceId, reset);
      } else {
        cycleProtectedLobby(source, active, reset);
      }
      return;
    }
    cycleOrdinary(source, instanceId, active, reset);
  }

  private void cycleOrdinary(
      CommandSource source, String instanceId, ManagedInstance active, boolean reset) {
    String operation = reset ? "reset" : "restart";
    Runnable begin =
        () -> {
          try {
            logger.info(
                "{} command accepted from {} for {}",
                capitalize(operation),
                commandSourceName(source),
                instanceId);
            source.sendMessage(
                CommandMessages.message(
                    (reset ? "Resetting" : "Restarting")
                        + " persistent server "
                        + instanceId
                        + "...",
                    NamedTextColor.YELLOW));
            CompletableFuture<ManagedInstance> cycle =
                reset ? instances.reset(instanceId) : instances.restart(instanceId);
            cycle.whenComplete(
                (restarted, failure) -> {
                  if (failure != null) {
                    sendFailure(source, operation, failure);
                    return;
                  }
                  restarted
                      .readyFuture()
                      .whenComplete(
                          (ready, readyFailure) -> {
                            if (readyFailure == null) {
                              source.sendMessage(
                                  CommandMessages.message(
                                      "Server "
                                          + ready.id()
                                          + " "
                                          + (reset ? "reset" : "restarted")
                                          + ".",
                                      NamedTextColor.GREEN));
                            } else {
                              sendFailure(source, operation, readyFailure);
                            }
                          });
                });
          } catch (InstanceOperationException exception) {
            source.sendMessage(CommandMessages.message(exception.getMessage(), NamedTextColor.RED));
          }
        };
    if (active == null) {
      begin.run();
      return;
    }
    source.sendMessage(
        CommandMessages.message(
            "Moving players to the lobby before "
                + (reset ? "resetting " : "restarting ")
                + instanceId
                + "...",
            NamedTextColor.YELLOW));
    lobbyProvider
        .evacuate(instanceId)
        .whenComplete(
            (ignored, failure) -> {
              if (failure == null) {
                begin.run();
              } else {
                source.sendMessage(
                    CommandMessages.message(
                        capitalize(operation) + " cancelled: " + rootMessage(failure),
                        NamedTextColor.RED));
              }
            });
  }

  private void cycleProtectedLobby(CommandSource source, ManagedInstance instance, boolean reset) {
    String operation = reset ? "reset" : "restart";
    if (!lobbyProvider.beginIntentionalStop(instance.id())) {
      source.sendMessage(
          CommandMessages.message(
              "The active lobby is already draining or changed.", NamedTextColor.RED));
      return;
    }
    logger.warn(
        "Forced managed lobby {} requested by {} for {}",
        operation,
        commandSourceName(source),
        instance.id());
    source.sendMessage(
        CommandMessages.message(
            "Moving players to SLS-Limbo before "
                + (reset ? "resetting " : "restarting ")
                + instance.id()
                + "...",
            NamedTextColor.YELLOW));
    lobbyProvider
        .evacuateForIntentionalStop(instance.id())
        .whenComplete(
            (ignored, evacuationFailure) -> {
              if (evacuationFailure != null) {
                lobbyProvider.cancelIntentionalStop(instance.id());
                source.sendMessage(
                    CommandMessages.message(
                        capitalize(operation) + " cancelled: " + rootMessage(evacuationFailure),
                        NamedTextColor.RED));
                return;
              }
              source.sendMessage(
                  CommandMessages.message(
                      (reset ? "Resetting" : "Restarting")
                          + " protected lobby "
                          + instance.id()
                          + "...",
                      NamedTextColor.YELLOW));
              lobbyProvider
                  .cyclePrimary(instance.id(), reset)
                  .whenComplete(
                      (server, cycleFailure) -> {
                        if (cycleFailure == null) {
                          source.sendMessage(
                              CommandMessages.message(
                                  "Server "
                                      + instance.id()
                                      + " "
                                      + (reset ? "reset" : "restarted")
                                      + ".",
                                  NamedTextColor.GREEN));
                        } else {
                          sendFailure(source, operation, cycleFailure);
                        }
                      });
            });
  }

  private void cycleUnavailableProtectedLobby(
      CommandSource source, String instanceId, boolean reset) {
    String operation = reset ? "reset" : "restart";
    if (!lobbyProvider.beginIntentionalStop(instanceId)) {
      source.sendMessage(
          CommandMessages.message(
              "The active lobby is already draining or changed.", NamedTextColor.RED));
      return;
    }
    logger.warn(
        "Forced offline managed lobby {} requested by {} for {}",
        operation,
        commandSourceName(source),
        instanceId);
    source.sendMessage(
        CommandMessages.message(
            (reset ? "Resetting" : "Restarting") + " offline protected lobby " + instanceId + "...",
            NamedTextColor.YELLOW));
    lobbyProvider
        .cyclePrimary(instanceId, reset)
        .whenComplete(
            (server, failure) -> {
              if (failure == null) {
                source.sendMessage(
                    CommandMessages.message(
                        "Server " + instanceId + " " + (reset ? "reset" : "restarted") + ".",
                        NamedTextColor.GREEN));
              } else {
                lobbyProvider.cancelIntentionalStop(instanceId);
                sendFailure(source, operation, failure);
              }
            });
  }

  private boolean requireAdmin(CommandSource source, String permission, String operation) {
    if (authorizer.canAdminister(source, permission)) {
      return true;
    }
    source.sendMessage(
        CommandMessages.message(
            "You do not have permission to " + operation + ".", NamedTextColor.RED));
    return false;
  }

  private static void sendFailure(CommandSource source, String operation, Throwable failure) {
    source.sendMessage(
        CommandMessages.message(
            capitalize(operation) + " failed: " + rootMessage(failure), NamedTextColor.RED));
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
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private static String capitalize(String value) {
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }
}
