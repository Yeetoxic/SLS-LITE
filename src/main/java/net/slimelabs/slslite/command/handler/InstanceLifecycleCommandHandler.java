package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandInstanceAccess;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.command.CreateOverrideParser;
import net.slimelabs.slslite.instance.InstanceDeletionResult;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import org.slf4j.Logger;

/**
 * Owns create, start, stop, restart, and reset execution and completion behavior.
 */
public final class InstanceLifecycleCommandHandler {

  private final BlueprintRepository blueprints;
  private final ServerController instances;
  private final LobbyProvider lobbyProvider;
  private final CommandAuthorizer authorizer;
  private final CommandInstanceAccess instanceAccess;
  private final Logger logger;

  public InstanceLifecycleCommandHandler(
      BlueprintRepository blueprints,
      ServerController instances,
      LobbyProvider lobbyProvider,
      CommandAuthorizer authorizer,
      CommandInstanceAccess instanceAccess,
      Logger logger) {
    this.blueprints = blueprints;
    this.instances = instances;
    this.lobbyProvider = lobbyProvider;
    this.authorizer = authorizer;
    this.instanceAccess = instanceAccess;
    this.logger = logger;
  }

  public void start(CommandSource source, String[] arguments) {
    launch(source, arguments, "start");
  }

  public void create(CommandSource source, String[] arguments) {
    launch(source, arguments, "create");
  }

  private void launch(CommandSource source, String[] arguments, String operation) {
    if (!requireAdmin(source, operation, operation + " managed servers")) {
      return;
    }
    if ("create".equals(operation) && arguments.length < 3) {
      source.sendMessage(CommandMessages.usage("/sls create", "type", "blueprint"));
      return;
    }
    if ("start".equals(operation) && (arguments.length < 2 || arguments.length > 3)) {
      source.sendMessage(CommandMessages.usage("/sls start", "type", "blueprint"));
      return;
    }
    Optional<Blueprint> blueprint = resolveBlueprint(arguments);
    if (blueprint.isEmpty()) {
      source.sendMessage(CommandMessages.usage("/sls " + operation, "type", "blueprint"));
      return;
    }

    try {
      ManagedInstance instance =
          "create".equals(operation)
              ? instances.create(blueprint.get().id(), CreateOverrideParser.parse(arguments))
              : instances.start(blueprint.get().id());
      logger.info(
          "{} command accepted from {} for {}/{} as {}",
          Character.toUpperCase(operation.charAt(0)) + operation.substring(1),
          commandSourceName(source),
          blueprint.get().type(),
          blueprint.get().id(),
          instance.id());
      source.sendMessage(
          CommandMessages.message(
              "Preparing "
                  + instance.id()
                  + " from "
                  + blueprint.get().type()
                  + "/"
                  + blueprint.get().id()
                  + "...",
              NamedTextColor.YELLOW));
      instance
          .readyFuture()
          .whenComplete(
              (ready, failure) -> {
                if (failure == null) {
                  source.sendMessage(
                      CommandMessages.message(
                          "Server " + ready.id() + " is running.", NamedTextColor.GREEN));
                } else if (rootCause(failure) instanceof CancellationException) {
                  source.sendMessage(
                      CommandMessages.message(
                          "Server " + instance.id() + " startup cancelled.",
                          NamedTextColor.YELLOW));
                } else {
                  source.sendMessage(
                      CommandMessages.message(
                          "Server " + instance.id() + " failed: " + rootMessage(failure),
                          NamedTextColor.RED));
                }
              });
    } catch (InstanceOperationException | IllegalArgumentException exception) {
      source.sendMessage(CommandMessages.message(exception.getMessage(), NamedTextColor.RED));
    }
  }

  public void stop(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "stop", "stop managed servers")) {
      return;
    }
    if (arguments.length < 2
        || arguments.length > 3
        || arguments.length == 3 && !"--force".equalsIgnoreCase(arguments[2])) {
      source.sendMessage(CommandMessages.usage("/sls stop", "server", "server --force"));
      return;
    }
    boolean force = arguments.length == 3;
    if (force && !requireAdmin(source, "stop.force", "force-stop protected managed servers")) {
      return;
    }
    ManagedInstance instance = instanceAccess.resolve(source, arguments[1]);
    if (instance == null) {
      return;
    }
    boolean protectedLobby = lobbyProvider.isLobby(instance.id());
    if (protectedLobby && !force) {
      source.sendMessage(
          CommandMessages.message(
              "The active lobby is protected. Use /sls stop "
                  + instance.id()
                  + " --force to stop it intentionally.",
              NamedTextColor.RED));
      return;
    }
    if (force && !protectedLobby) {
      source.sendMessage(
          CommandMessages.message(
              instance.id() + " is not protected; use /sls stop " + instance.id() + ".",
              NamedTextColor.YELLOW));
      return;
    }
    if (force) {
      logger.warn(
          "Forced managed lobby stop requested by {} for {}",
          commandSourceName(source),
          instance.id());
      if (!lobbyProvider.beginIntentionalStop(instance.id())) {
        logger.warn(
            "Forced managed lobby stop by {} for {} was cancelled because "
                + "the lobby is already stopping or changed",
            commandSourceName(source),
            instance.id());
        source.sendMessage(
            CommandMessages.message(
                "Stop cancelled: the active lobby is already stopping or changed.",
                NamedTextColor.RED));
        return;
      }
    }
    source.sendMessage(
        CommandMessages.message(
            protectedLobby
                ? "Moving players to SLS-Limbo before stopping " + instance.id() + "..."
                : "Moving players to the lobby before stopping " + instance.id() + "...",
            NamedTextColor.YELLOW));
    CompletableFuture<Void> evacuation =
        protectedLobby
            ? lobbyProvider.evacuateForIntentionalStop(instance.id())
            : lobbyProvider.evacuate(instance.id());
    evacuation.whenComplete(
        (ignored, evacuationFailure) -> {
          if (evacuationFailure != null) {
            if (force) {
              lobbyProvider.cancelIntentionalStop(instance.id());
              logger.warn(
                  "Forced managed lobby stop by {} for {} was cancelled: {}",
                  commandSourceName(source),
                  instance.id(),
                  rootMessage(evacuationFailure));
            }
            source.sendMessage(
                CommandMessages.message(
                    "Stop cancelled: " + rootMessage(evacuationFailure), NamedTextColor.RED));
            return;
          }
          if (protectedLobby && !lobbyProvider.prepareIntentionalStop(instance.id())) {
            lobbyProvider.cancelIntentionalStop(instance.id());
            logger.warn(
                "Forced managed lobby stop by {} for {} was cancelled because "
                    + "the lobby changed during evacuation",
                commandSourceName(source),
                instance.id());
            source.sendMessage(
                CommandMessages.message(
                    "Stop cancelled: the active lobby changed during evacuation.",
                    NamedTextColor.RED));
            return;
          }
          if (protectedLobby) {
            source.sendMessage(
                CommandMessages.message(
                    "Automatic managed-lobby recovery is suppressed until " + "Velocity restarts.",
                    NamedTextColor.GRAY));
          }
          stopInstance(source, instance.id(), force);
        });
  }

  public void delete(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "delete", "delete managed servers")) {
      return;
    }
    if (arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls delete", "server", "all"));
      return;
    }
    if ("all".equalsIgnoreCase(arguments[1])) {
      deleteAll(source);
      return;
    }

    String targetId;
    ManagedInstance active;
    if ("this".equalsIgnoreCase(arguments[1])) {
      active = instanceAccess.resolve(source, arguments[1]);
      if (active == null) {
        return;
      }
      targetId = active.id();
    } else {
      targetId = arguments[1];
      active = instanceAccess.find(targetId);
      if (active == null && !instanceAccess.persistentIds().contains(targetId)) {
        source.sendMessage(
            CommandMessages.message("No such server " + targetId, NamedTextColor.RED));
        return;
      }
    }

    if (lobbyProvider.isLobby(targetId)) {
      source.sendMessage(
          CommandMessages.message(
              "The managed lobby is protected and cannot be deleted.", NamedTextColor.RED));
      return;
    }

    logger.warn("Delete command accepted from {} for {}", commandSourceName(source), targetId);
    source.sendMessage(
        CommandMessages.message(
            active == null
                ? "Deleting persistent server " + targetId + "..."
                : "Moving players to the lobby before deleting " + targetId + "...",
            NamedTextColor.YELLOW));
    deleteTarget(targetId, active)
        .whenComplete(
            (result, deleteFailure) -> reportDelete(source, targetId, result, deleteFailure));
  }

  public void restart(CommandSource source, String[] arguments) {
    cyclePersistent(source, arguments, false);
  }

  public void reset(CommandSource source, String[] arguments) {
    cyclePersistent(source, arguments, true);
  }

  public List<String> suggestions(CommandSource source, String operation, String[] arguments) {
    if (arguments.length == 2) {
      return switch (operation) {
        case "create", "start" ->
            authorizer.canAdminister(source, operation)
                ? blueprints.getTypes().stream().sorted().toList()
                : List.of();
        case "reset", "restart" ->
            authorizer.canAdminister(source, operation)
                ? withPrefix("this", instanceAccess.persistentIds())
                : List.of();
        case "delete" ->
            authorizer.canAdminister(source, operation)
                ? withPrefix("all", withPrefix("this", instanceAccess.persistentIds()))
                : List.of();
        case "stop" ->
            authorizer.canAdminister(source, "stop")
                ? withPrefix("this", instanceAccess.activeIds())
                : List.of();
        default -> List.of();
      };
    }
    if (arguments.length == 3
        && ("create".equals(operation) || "start".equals(operation))
        && authorizer.canAdminister(source, operation)) {
      return blueprints.getByType(arguments[1]).stream().map(Blueprint::id).toList();
    }
    if (arguments.length >= 4
        && "create".equals(operation)
        && authorizer.canAdminister(source, operation)) {
      String current = arguments[arguments.length - 1].toLowerCase(java.util.Locale.ROOT);
      if (current.startsWith("--save=") || current.startsWith("--enable-command-block=")) {
        String flag = current.substring(0, current.indexOf('=') + 1);
        return List.of(flag + "true", flag + "false").stream()
            .filter(value -> value.startsWith(current))
            .toList();
      }
      java.util.Set<String> used =
          java.util.Arrays.stream(arguments)
              .skip(3)
              .map(value -> value.substring(0, Math.max(0, value.indexOf('=') + 1)))
              .collect(java.util.stream.Collectors.toSet());
      return CreateOverrideParser.FLAGS.stream().filter(flag -> !used.contains(flag)).toList();
    }
    if (arguments.length == 3
        && "stop".equals(operation)
        && authorizer.canAdminister(source, "stop.force")) {
      return List.of("--force");
    }
    if (arguments.length == 3
        && ("restart".equals(operation) || "reset".equals(operation))
        && authorizer.canAdminister(source, operation + ".force")) {
      return List.of("--force");
    }
    return List.of();
  }

  private static void reportDelete(
      CommandSource source, String instanceId, InstanceDeletionResult result, Throwable failure) {
    if (failure != null) {
      source.sendMessage(
          CommandMessages.message("Delete failed: " + rootMessage(failure), NamedTextColor.RED));
      return;
    }
    source.sendMessage(
        CommandMessages.message("Deleted server " + instanceId + ".", NamedTextColor.GREEN));
    if (!result.tombstoneCleaned()) {
      source.sendMessage(
          CommandMessages.message(
              "Storage deletion committed; deferred cleanup will retry at startup.",
              NamedTextColor.YELLOW));
    }
  }

  private CompletableFuture<InstanceDeletionResult> deleteTarget(
      String instanceId, ManagedInstance active) {
    CompletableFuture<Void> evacuation =
        active == null
            ? CompletableFuture.completedFuture(null)
            : lobbyProvider.evacuate(instanceId);
    return evacuation.thenCompose(
        ignored -> {
          try {
            return instances.delete(instanceId);
          } catch (InstanceOperationException exception) {
            return CompletableFuture.failedFuture(exception);
          }
        });
  }

  private void deleteAll(CommandSource source) {
    List<String> candidates = instanceAccess.persistentIds();
    List<String> protectedIds = candidates.stream().filter(lobbyProvider::isLobby).toList();
    List<String> targets = candidates.stream().filter(id -> !lobbyProvider.isLobby(id)).toList();
    if (targets.isEmpty()) {
      source.sendMessage(
          CommandMessages.message(
              protectedIds.isEmpty()
                  ? "There are no managed servers to delete."
                  : "There are no deletable servers; the managed lobby is protected.",
              NamedTextColor.YELLOW));
      return;
    }

    logger.warn(
        "Delete-all command accepted from {} for {} server(s); "
            + "{} protected lobby server(s) skipped",
        commandSourceName(source),
        targets.size(),
        protectedIds.size());
    source.sendMessage(
        CommandMessages.message(
            "Deleting " + targets.size() + " managed server(s)...", NamedTextColor.YELLOW));
    int[] completed = {0};
    int[] failures = {0};
    CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
    for (String instanceId : targets) {
      sequence =
          sequence.thenCompose(
              ignored -> {
                ManagedInstance active = instanceAccess.find(instanceId);
                return deleteTarget(instanceId, active)
                    .handle(
                        (result, failure) -> {
                          reportDelete(source, instanceId, result, failure);
                          if (failure == null) {
                            completed[0]++;
                          } else {
                            failures[0]++;
                          }
                          return null;
                        });
              });
    }
    sequence.thenRun(
        () ->
            source.sendMessage(
                CommandMessages.message(
                    "Delete-all complete: "
                        + completed[0]
                        + " deleted, "
                        + failures[0]
                        + " failed"
                        + (protectedIds.isEmpty()
                            ? "."
                            : ", " + protectedIds.size() + " protected lobby skipped."),
                    failures[0] == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
  }

  private void stopInstance(CommandSource source, String instanceId, boolean forcedProtectedStop) {
    try {
      logger.info(
          "Stop command accepted from {} for {}{}",
          commandSourceName(source),
          instanceId,
          forcedProtectedStop ? " (forced protected stop)" : "");
      source.sendMessage(
          CommandMessages.message("Stopping " + instanceId + "...", NamedTextColor.YELLOW));
      instances
          .stop(instanceId)
          .whenComplete(
              (exitCode, failure) -> {
                if (failure == null) {
                  source.sendMessage(
                      CommandMessages.message(
                          "Stopped " + instanceId + " with exit code " + exitCode + ".",
                          NamedTextColor.GREEN));
                  if (forcedProtectedStop) {
                    logger.info(
                        "Forced managed lobby stop by {} for {} completed " + "with exit code {}",
                        commandSourceName(source),
                        instanceId,
                        exitCode);
                  }
                } else {
                  source.sendMessage(
                      CommandMessages.message(
                          "Stop failed: " + rootMessage(failure), NamedTextColor.RED));
                  if (forcedProtectedStop) {
                    logger.warn(
                        "Forced managed lobby stop by {} for {} failed: {}",
                        commandSourceName(source),
                        instanceId,
                        rootMessage(failure));
                  }
                }
              });
    } catch (InstanceOperationException exception) {
      if (forcedProtectedStop) {
        logger.warn(
            "Forced managed lobby stop by {} for {} failed: {}",
            commandSourceName(source),
            instanceId,
            exception.getMessage());
      }
      source.sendMessage(CommandMessages.message(exception.getMessage(), NamedTextColor.RED));
    }
  }

  private void cyclePersistent(CommandSource source, String[] arguments, boolean reset) {
    String operation = reset ? "reset" : "restart";
    if (!requireAdmin(source, operation, operation + " persistent servers")) {
      return;
    }
    if (arguments.length < 2
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

    String instanceId = arguments[1];
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

    String targetId = instanceId;
    Runnable beginRestart =
        () -> {
          try {
            logger.info(
                "{} command accepted from {} for {}",
                capitalize(operation),
                commandSourceName(source),
                targetId);
            source.sendMessage(
                CommandMessages.message(
                    (reset ? "Resetting" : "Restarting") + " persistent server " + targetId + "...",
                    NamedTextColor.YELLOW));
            CompletableFuture<ManagedInstance> cycle =
                reset ? instances.reset(targetId) : instances.restart(targetId);
            cycle.whenComplete(
                (restarted, failure) -> {
                  if (failure != null) {
                    source.sendMessage(
                        CommandMessages.message(
                            capitalize(operation) + " failed: " + rootMessage(failure),
                            NamedTextColor.RED));
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
                              source.sendMessage(
                                  CommandMessages.message(
                                      capitalize(operation)
                                          + " failed: "
                                          + rootMessage(readyFailure),
                                      NamedTextColor.RED));
                            }
                          });
                });
          } catch (InstanceOperationException exception) {
            source.sendMessage(CommandMessages.message(exception.getMessage(), NamedTextColor.RED));
          }
        };

    if (active == null) {
      beginRestart.run();
      return;
    }
    source.sendMessage(
        CommandMessages.message(
            "Moving players to the lobby before "
                + (reset ? "resetting " : "restarting ")
                + targetId
                + "...",
            NamedTextColor.YELLOW));
    lobbyProvider
        .evacuate(targetId)
        .whenComplete(
            (ignored, failure) -> {
              if (failure == null) {
                beginRestart.run();
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
                          source.sendMessage(
                              CommandMessages.message(
                                  capitalize(operation) + " failed: " + rootMessage(cycleFailure),
                                  NamedTextColor.RED));
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
                source.sendMessage(
                    CommandMessages.message(
                        capitalize(operation) + " failed: " + rootMessage(failure),
                        NamedTextColor.RED));
              }
            });
  }

  private Optional<Blueprint> resolveBlueprint(String[] arguments) {
    if (arguments.length >= 3) {
      return blueprints.get(arguments[1], arguments[2]);
    }
    if (arguments.length == 2) {
      return blueprints.get(arguments[1]);
    }
    return Optional.empty();
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

  private static List<String> withPrefix(String prefix, List<String> values) {
    List<String> result = new ArrayList<>(values.size() + 1);
    result.add(prefix);
    result.addAll(values);
    return List.copyOf(result);
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
    Throwable current = rootCause(throwable);
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String capitalize(String value) {
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }
}
