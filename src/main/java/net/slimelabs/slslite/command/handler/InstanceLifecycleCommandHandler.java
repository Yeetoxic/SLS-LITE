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
import net.slimelabs.slslite.command.VSLSCommandContract;
import net.slimelabs.slslite.instance.InstanceDeletionResult;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import org.slf4j.Logger;

/**
 * Owns create, start, stop, kill, delete, restart, and reset execution and completion behavior.
 */
public final class InstanceLifecycleCommandHandler {

  private final BlueprintRepository blueprints;
  private final ServerController instances;
  private final LobbyProvider lobbyProvider;
  private final CommandAuthorizer authorizer;
  private final CommandInstanceAccess instanceAccess;
  private final Logger logger;
  private final PersistentInstanceCommandHandler persistentCommands;

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
    this.persistentCommands =
        new PersistentInstanceCommandHandler(
            instances, lobbyProvider, authorizer, instanceAccess, logger);
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
    if (arguments.length < 1
        || arguments.length > 3
        || arguments.length == 3 && !isForceModifier(arguments[2])) {
      source.sendMessage(CommandMessages.usage("/sls stop", "server", "all", "server force"));
      return;
    }
    boolean force = arguments.length == 3;
    String requested = arguments.length == 1 ? "this" : arguments[1];
    if ("all".equalsIgnoreCase(requested)) {
      stopAll(source, force);
      return;
    }
    ManagedInstance instance = instanceAccess.resolve(source, requested);
    if (instance == null) {
      return;
    }
    boolean protectedLobby = lobbyProvider.isLobby(instance.id());
    if (force
        && protectedLobby
        && !requireAdmin(source, "stop.force", "force-stop protected managed servers")) {
      return;
    }
    if (protectedLobby && !force) {
      source.sendMessage(
          CommandMessages.message(
              "The active lobby is protected. Use /sls stop "
                  + instance.id()
                  + " --force to stop it intentionally.",
              NamedTextColor.RED));
      return;
    }
    reportStopStart(source, instance.id(), protectedLobby);
    stopTarget(source, instance.id(), protectedLobby)
        .whenComplete(
            (exitCode, failure) ->
                reportStop(source, instance.id(), protectedLobby, exitCode, failure));
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

  public void kill(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "kill", "force-terminate managed servers")) {
      return;
    }
    if (arguments.length < 1
        || arguments.length > 3
        || arguments.length == 3 && !isForceModifier(arguments[2])) {
      source.sendMessage(CommandMessages.usage("/sls kill", "server", "all", "server force"));
      return;
    }
    boolean forceCleanup = arguments.length == 3;
    String requested = arguments.length == 1 ? "this" : arguments[1];
    if ("all".equalsIgnoreCase(requested)) {
      killAll(source, forceCleanup);
      return;
    }

    ManagedInstance instance = instanceAccess.resolve(source, requested);
    if (instance == null) {
      return;
    }
    boolean protectedLobby = lobbyProvider.isLobby(instance.id());
    if (protectedLobby && !forceCleanup) {
      source.sendMessage(
          CommandMessages.message(
              "The active lobby is protected. Use /sls kill "
                  + instance.id()
                  + " force to terminate it intentionally.",
              NamedTextColor.RED));
      return;
    }
    if (protectedLobby
        && !requireAdmin(source, "kill.force", "force-terminate protected managed lobby servers")) {
      return;
    }

    reportKillStart(source, instance.id(), protectedLobby);
    killTarget(instance.id(), protectedLobby, forceCleanup)
        .whenComplete((exitCode, failure) -> reportKill(source, instance.id(), exitCode, failure));
  }

  public void restart(CommandSource source, String[] arguments) {
    persistentCommands.restart(source, arguments);
  }

  public void reset(CommandSource source, String[] arguments) {
    persistentCommands.reset(source, arguments);
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
        case "kill" ->
            authorizer.canAdminister(source, operation)
                ? withPrefix("all", withPrefix("this", instanceAccess.activeIds()))
                : List.of();
        case "stop" ->
            authorizer.canAdminister(source, "stop")
                ? withPrefix("all", withPrefix("this", instanceAccess.activeIds()))
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
        && authorizer.canAdminister(source, "stop")) {
      if ("all".equalsIgnoreCase(arguments[1])) {
        boolean hasProtected = instanceAccess.activeIds().stream().anyMatch(lobbyProvider::isLobby);
        return !hasProtected || authorizer.canAdminister(source, "stop.force")
            ? List.of(VSLSCommandContract.FORCE, VSLSCommandContract.ADDITIVE_FORCE)
            : List.of();
      }
      ManagedInstance target = completionTarget(source, arguments[1]);
      if (target != null
          && (!lobbyProvider.isLobby(target.id())
              || authorizer.canAdminister(source, "stop.force"))) {
        return List.of(VSLSCommandContract.FORCE, VSLSCommandContract.ADDITIVE_FORCE);
      }
    }
    if (arguments.length == 3
        && "kill".equals(operation)
        && authorizer.canAdminister(source, "kill")) {
      return List.of("force", "--force");
    }
    if (arguments.length == 3
        && ("restart".equals(operation) || "reset".equals(operation))
        && authorizer.canAdminister(source, operation + ".force")) {
      ManagedInstance target = completionTarget(source, arguments[1]);
      String targetId = target == null ? arguments[1] : target.id();
      return lobbyProvider.isLobby(targetId) ? List.of("--force") : List.of();
    }
    return List.of();
  }

  private ManagedInstance completionTarget(CommandSource source, String requested) {
    if ("this".equalsIgnoreCase(requested) && source instanceof Player player) {
      return player
          .getCurrentServer()
          .map(connection -> connection.getServerInfo().getName())
          .map(instanceAccess::find)
          .orElse(null);
    }
    return instanceAccess.find(requested);
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
    SequentialCommandBatch.run(
            targets,
            instanceId -> {
              ManagedInstance active = instanceAccess.find(instanceId);
              return deleteTarget(instanceId, active)
                  .whenComplete(
                      (result, failure) -> reportDelete(source, instanceId, result, failure));
            })
        .thenAccept(
            result ->
                source.sendMessage(
                    CommandMessages.message(
                        "Delete-all complete: "
                            + result.completed()
                            + " deleted, "
                            + result.failures()
                            + " failed"
                            + (protectedIds.isEmpty()
                                ? "."
                                : ", " + protectedIds.size() + " protected lobby skipped."),
                        result.failures() == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
  }

  private void killAll(CommandSource source, boolean forceCleanup) {
    List<String> activeIds = instanceAccess.activeIds();
    List<String> protectedIds = activeIds.stream().filter(lobbyProvider::isLobby).toList();
    List<String> ordinaryIds = activeIds.stream().filter(id -> !lobbyProvider.isLobby(id)).toList();
    List<String> targets = new ArrayList<>(ordinaryIds);
    if (forceCleanup
        && !protectedIds.isEmpty()
        && !requireAdmin(source, "kill.force", "force-terminate protected managed lobby servers")) {
      return;
    }
    if (forceCleanup) {
      targets.addAll(protectedIds);
    }
    if (targets.isEmpty()) {
      source.sendMessage(
          CommandMessages.message(
              protectedIds.isEmpty()
                  ? "No servers are running."
                  : "There are no killable servers; the managed lobby is protected.",
              protectedIds.isEmpty() ? NamedTextColor.RED : NamedTextColor.YELLOW));
      return;
    }

    logger.warn(
        "Kill-all command accepted from {} for {} server(s); " + "{} protected lobby server(s) {}",
        commandSourceName(source),
        targets.size(),
        protectedIds.size(),
        forceCleanup ? "included" : "skipped");
    source.sendMessage(CommandMessages.message("Killing all servers.", NamedTextColor.GRAY));
    SequentialCommandBatch.run(
            targets,
            instanceId -> {
              boolean protectedLobby = lobbyProvider.isLobby(instanceId);
              reportKillStart(source, instanceId, protectedLobby);
              return killTarget(instanceId, protectedLobby, forceCleanup)
                  .whenComplete(
                      (exitCode, failure) -> reportKill(source, instanceId, exitCode, failure));
            })
        .thenAccept(
            result ->
                source.sendMessage(
                    CommandMessages.message(
                        "Kill-all complete: "
                            + result.completed()
                            + " terminated, "
                            + result.failures()
                            + " failed"
                            + (!forceCleanup && !protectedIds.isEmpty()
                                ? ", " + protectedIds.size() + " protected lobby skipped."
                                : "."),
                        result.failures() == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
  }

  private void reportKillStart(CommandSource source, String instanceId, boolean protectedLobby) {
    logger.warn(
        "Kill command accepted from {} for {}{}",
        commandSourceName(source),
        instanceId,
        protectedLobby ? " (forced protected lobby)" : "");
    source.sendMessage(
        CommandMessages.message(
            protectedLobby
                ? "Moving players to SLS-Limbo before force-terminating " + instanceId + "..."
                : "Moving players to the lobby before force-terminating " + instanceId + "...",
            NamedTextColor.YELLOW));
  }

  private CompletableFuture<Integer> killTarget(
      String instanceId, boolean protectedLobby, boolean forceCleanup) {
    if (!protectedLobby) {
      return lobbyProvider
          .evacuate(instanceId)
          .thenCompose(ignored -> invokeKill(instanceId, forceCleanup));
    }
    if (!lobbyProvider.beginIntentionalStop(instanceId)) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("The active lobby is already stopping or changed"));
    }
    return lobbyProvider
        .evacuateForIntentionalStop(instanceId)
        .handle(
            (ignored, failure) -> {
              if (failure != null) {
                lobbyProvider.cancelIntentionalStop(instanceId);
                throw new java.util.concurrent.CompletionException(rootCause(failure));
              }
              if (!lobbyProvider.prepareIntentionalStop(instanceId)) {
                lobbyProvider.cancelIntentionalStop(instanceId);
                throw new java.util.concurrent.CompletionException(
                    new IllegalStateException("The active lobby changed during evacuation"));
              }
              return null;
            })
        .thenCompose(
            ignored ->
                rollbackIntentionalStopOnFailure(instanceId, invokeKill(instanceId, forceCleanup)));
  }

  private CompletableFuture<Integer> invokeKill(String instanceId, boolean forceCleanup) {
    try {
      return instances.kill(instanceId, forceCleanup);
    } catch (InstanceOperationException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private static void reportKill(
      CommandSource source, String instanceId, Integer exitCode, Throwable failure) {
    if (failure != null) {
      source.sendMessage(
          CommandMessages.message(
              "Failed to kill server " + instanceId + ": " + rootMessage(failure),
              NamedTextColor.RED));
      return;
    }
    source.sendMessage(CommandMessages.message("Killed " + instanceId, NamedTextColor.GRAY));
  }

  private static boolean isForceModifier(String argument) {
    return VSLSCommandContract.FORCE.equalsIgnoreCase(argument)
        || VSLSCommandContract.ADDITIVE_FORCE.equalsIgnoreCase(argument);
  }

  private void stopAll(CommandSource source, boolean force) {
    List<String> activeIds = instanceAccess.activeIds();
    List<String> protectedIds = activeIds.stream().filter(lobbyProvider::isLobby).toList();
    List<String> ordinaryIds = activeIds.stream().filter(id -> !lobbyProvider.isLobby(id)).toList();
    List<String> targets = new ArrayList<>(ordinaryIds);
    if (force
        && !protectedIds.isEmpty()
        && !requireAdmin(source, "stop.force", "force-stop protected managed lobby servers")) {
      return;
    }
    if (force) {
      targets.addAll(protectedIds);
    }
    if (targets.isEmpty()) {
      source.sendMessage(
          CommandMessages.message(
              protectedIds.isEmpty()
                  ? "No servers are running."
                  : "There are no stoppable servers; the managed lobby is protected.",
              protectedIds.isEmpty() ? NamedTextColor.RED : NamedTextColor.YELLOW));
      return;
    }

    logger.info(
        "Stop-all command accepted from {} for {} server(s); {} protected lobby server(s) {}",
        commandSourceName(source),
        targets.size(),
        protectedIds.size(),
        force ? "included" : "skipped");
    source.sendMessage(CommandMessages.message("Stopping all servers.", NamedTextColor.GRAY));
    SequentialCommandBatch.run(
            targets,
            instanceId -> {
              boolean protectedLobby = lobbyProvider.isLobby(instanceId);
              reportStopStart(source, instanceId, protectedLobby);
              return stopTarget(source, instanceId, protectedLobby)
                  .whenComplete(
                      (exitCode, failure) ->
                          reportStop(source, instanceId, protectedLobby, exitCode, failure));
            })
        .thenAccept(
            result ->
                source.sendMessage(
                    CommandMessages.message(
                        "Stop-all complete: "
                            + result.completed()
                            + " stopped, "
                            + result.failures()
                            + " failed"
                            + (!force && !protectedIds.isEmpty()
                                ? ", " + protectedIds.size() + " protected lobby skipped."
                                : "."),
                        result.failures() == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
  }

  private void reportStopStart(CommandSource source, String instanceId, boolean protectedLobby) {
    logger.info(
        "Stop command accepted from {} for {}{}",
        commandSourceName(source),
        instanceId,
        protectedLobby ? " (forced protected stop)" : "");
    source.sendMessage(
        CommandMessages.message(
            protectedLobby
                ? "Moving players to SLS-Limbo before stopping " + instanceId + "..."
                : "Moving players to the lobby before stopping " + instanceId + "...",
            NamedTextColor.YELLOW));
  }

  private CompletableFuture<Integer> stopTarget(
      CommandSource source, String instanceId, boolean protectedLobby) {
    if (!protectedLobby) {
      return lobbyProvider
          .evacuate(instanceId)
          .handle(
              (ignored, failure) -> {
                if (failure != null) {
                  throw new StopCancelledException(rootMessage(failure));
                }
                return null;
              })
          .thenCompose(ignored -> invokeStop(instanceId));
    }
    logger.warn(
        "Forced managed lobby stop requested by {} for {}", commandSourceName(source), instanceId);
    if (!lobbyProvider.beginIntentionalStop(instanceId)) {
      return CompletableFuture.failedFuture(
          new StopCancelledException("The active lobby is already stopping or changed"));
    }
    return lobbyProvider
        .evacuateForIntentionalStop(instanceId)
        .handle(
            (ignored, failure) -> {
              if (failure != null) {
                lobbyProvider.cancelIntentionalStop(instanceId);
                throw new StopCancelledException(rootMessage(failure));
              }
              if (!lobbyProvider.prepareIntentionalStop(instanceId)) {
                lobbyProvider.cancelIntentionalStop(instanceId);
                throw new StopCancelledException("The active lobby changed during evacuation");
              }
              source.sendMessage(
                  CommandMessages.message(
                      "Automatic managed-lobby recovery is suppressed until Velocity restarts.",
                      NamedTextColor.GRAY));
              return null;
            })
        .thenCompose(
            ignored -> rollbackIntentionalStopOnFailure(instanceId, invokeStop(instanceId)));
  }

  private <T> CompletableFuture<T> rollbackIntentionalStopOnFailure(
      String instanceId, CompletableFuture<T> operation) {
    return operation.whenComplete(
        (ignored, failure) -> {
          if (failure != null) {
            lobbyProvider.cancelIntentionalStop(instanceId);
          }
        });
  }

  private CompletableFuture<Integer> invokeStop(String instanceId) {
    try {
      return instances.stop(instanceId);
    } catch (InstanceOperationException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private void reportStop(
      CommandSource source,
      String instanceId,
      boolean protectedLobby,
      Integer exitCode,
      Throwable failure) {
    if (failure == null) {
      source.sendMessage(
          CommandMessages.message(
              "Stopped " + instanceId + " with exit code " + exitCode + ".", NamedTextColor.GREEN));
      if (protectedLobby) {
        logger.info(
            "Forced managed lobby stop by {} for {} completed with exit code {}",
            commandSourceName(source),
            instanceId,
            exitCode);
      }
      return;
    }
    source.sendMessage(
        CommandMessages.message(
            (rootCause(failure) instanceof StopCancelledException
                    ? "Stop cancelled: "
                    : "Stop failed: ")
                + rootMessage(failure),
            NamedTextColor.RED));
    if (protectedLobby) {
      logger.warn(
          "Forced managed lobby stop by {} for {} failed: {}",
          commandSourceName(source),
          instanceId,
          rootMessage(failure));
    }
  }

  private static final class StopCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private StopCancelledException(String message) {
      super(message);
    }
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
