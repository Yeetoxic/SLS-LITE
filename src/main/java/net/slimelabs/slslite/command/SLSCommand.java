package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.handler.AdminCommandHandler;
import net.slimelabs.slslite.command.handler.InspectionCommandHandler;
import net.slimelabs.slslite.command.handler.InstallationCommandHandler;
import net.slimelabs.slslite.command.handler.InstanceLifecycleCommandHandler;
import net.slimelabs.slslite.command.handler.PlayerRoutingCommandHandler;
import net.slimelabs.slslite.config.DefinitionReloader;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.config.SLSConfig;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityReport;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.security.AdminClaimService;
import net.slimelabs.slslite.security.AdministratorStore;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

public final class SLSCommand implements SimpleCommand {

  private static final List<String> PUBLIC_COMMANDS =
      List.of("admin", "dequeue", "find", "info", "join", "list", "registries", "version");
  private static final List<String> ADMIN_COMMANDS =
      List.of(
          "blueprint",
          "blueprints",
          "console",
          "create",
          "debug",
          "delete",
          "install",
          "kill",
          "logs",
          "node",
          "pause",
          "reload",
          "reset",
          "restart",
          "resume",
          "start",
          "stats",
          "status",
          "stop",
          "system");

  private final BlueprintRepository blueprints;
  private final SoftwareProfileRepository softwareProfiles;
  private final ServerController instances;
  private final SLSConfig activeConfig;
  private final CommandAuthorizer authorizer;
  private final CommandInstanceAccess instanceAccess;
  private final AdminCommandHandler adminHandler;
  private final InstanceLifecycleCommandHandler lifecycleHandler;
  private final InstallationCommandHandler installationHandler;
  private final InspectionCommandHandler inspectionHandler;
  private final PlayerRoutingCommandHandler playerRoutingHandler;
  private final DebugPlayerRegistry debugPlayers;
  private final Logger logger;

  public SLSCommand(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ProcessSupervisor processSupervisor,
      ServerController instances,
      LocalJoinService joinService,
      LobbyProvider lobbyProvider,
      ManagedOutputConfig outputConfig,
      SLSConfig activeConfig,
      HostCapabilityReport hostCapabilities,
      AdministratorStore administrators,
      AdminClaimService adminClaims,
      Logger logger) {
    this(
        proxy,
        blueprints,
        softwareProfiles,
        resourceBudget,
        processSupervisor,
        instances,
        joinService,
        lobbyProvider,
        outputConfig,
        activeConfig,
        hostCapabilities,
        administrators,
        adminClaims,
        null,
        logger);
  }

  public SLSCommand(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ProcessSupervisor processSupervisor,
      ServerController instances,
      LocalJoinService joinService,
      LobbyProvider lobbyProvider,
      ManagedOutputConfig outputConfig,
      SLSConfig activeConfig,
      HostCapabilityReport hostCapabilities,
      AdministratorStore administrators,
      AdminClaimService adminClaims,
      SoftwareInstallationService installationService,
      Logger logger) {
    this.blueprints = blueprints;
    this.softwareProfiles = softwareProfiles;
    this.instances = instances;
    this.activeConfig = activeConfig;
    this.authorizer = new CommandAuthorizer(administrators);
    this.instanceAccess = new CommandInstanceAccess(proxy, instances);
    this.adminHandler =
        new AdminCommandHandler(proxy, administrators, adminClaims, authorizer, logger);
    this.lifecycleHandler =
        new InstanceLifecycleCommandHandler(
            blueprints, instances, lobbyProvider, authorizer, instanceAccess, logger);
    this.installationHandler =
        new InstallationCommandHandler(
            blueprints, softwareProfiles, installationService, authorizer);
    this.inspectionHandler =
        new InspectionCommandHandler(
            blueprints,
            softwareProfiles,
            resourceBudget,
            instances,
            joinService,
            lobbyProvider,
            processSupervisor,
            outputConfig,
            hostCapabilities,
            authorizer,
            instanceAccess);
    this.playerRoutingHandler =
        new PlayerRoutingCommandHandler(
            proxy, blueprints, joinService, authorizer, instanceAccess, logger);
    this.debugPlayers = new DebugPlayerRegistry();
    this.logger = logger;
  }

  @Override
  public void execute(Invocation invocation) {
    String[] arguments = invocation.arguments();
    if (arguments.length == 0) {
      sendRootHelp(invocation.source());
      return;
    }

    String operation = arguments[0].toLowerCase(Locale.ROOT);
    debugPlayers.publish(
        "DEBUG",
        "Command /sls " + operation + " requested by " + commandSourceName(invocation.source()));
    switch (operation) {
      case "admin" -> adminHandler.execute(invocation.source(), arguments);
      case "blueprint" -> inspectionHandler.blueprint(invocation.source(), arguments);
      case "blueprints" -> inspectionHandler.blueprints(invocation.source(), arguments);
      case "console" -> console(invocation.source(), arguments);
      case "create" -> lifecycleHandler.create(invocation.source(), arguments);
      case "dequeue" -> playerRoutingHandler.dequeue(invocation.source(), arguments);
      case "delete" -> lifecycleHandler.delete(invocation.source(), arguments);
      case "debug" -> debug(invocation.source(), arguments);
      case "find" -> playerRoutingHandler.find(invocation.source(), arguments);
      case "info" -> inspectionHandler.info(invocation.source(), arguments);
      case "install" -> installationHandler.execute(invocation.source(), arguments);
      case "join" -> playerRoutingHandler.join(invocation.source(), arguments);
      case "kill" -> lifecycleHandler.kill(invocation.source(), arguments);
      case "list" -> inspectionHandler.list(invocation.source());
      case "logs" -> inspectionHandler.logs(invocation.source(), arguments);
      case "registries" -> inspectionHandler.registries(invocation.source());
      case "reload" -> reload(invocation.source(), arguments);
      case "reset" -> lifecycleHandler.reset(invocation.source(), arguments);
      case "restart" -> lifecycleHandler.restart(invocation.source(), arguments);
      case "start" -> lifecycleHandler.start(invocation.source(), arguments);
      case "stats" -> inspectionHandler.stats(invocation.source(), arguments);
      case "status" -> inspectionHandler.status(invocation.source(), arguments);
      case "stop" -> lifecycleHandler.stop(invocation.source(), arguments);
      case "system" -> inspectionHandler.system(invocation.source(), arguments);
      case "version" -> sendVersion(invocation.source());
      case "pause", "resume" -> unavailable(invocation.source(), arguments[0], false);
      case "node" -> unavailable(invocation.source(), arguments[0], true);
      default -> sendRootHelp(invocation.source());
    }
  }

  @Override
  public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
    String[] arguments = invocation.arguments();
    CommandSource source = invocation.source();
    if (arguments.length <= 1) {
      List<String> suggestions = new java.util.ArrayList<>(PUBLIC_COMMANDS);
      ADMIN_COMMANDS.stream()
          .filter(command -> authorizer.canAdminister(source, command))
          .forEach(suggestions::add);
      return completed(suggestions);
    }

    String operation = arguments[0].toLowerCase(Locale.ROOT);
    if (arguments.length == 2) {
      return switch (operation) {
        case "admin" -> completed(adminHandler.suggestions(source, arguments));
        case "blueprint" -> completed(inspectionHandler.suggestions(source, operation, arguments));
        case "blueprints" -> completed(inspectionHandler.suggestions(source, operation, arguments));
        case "console" ->
            authorizer.canAdminister(source, operation)
                ? completed(withPrefix("this", instanceIds()))
                : completed(List.of());
        case "logs" -> completed(inspectionHandler.suggestions(source, operation, arguments));
        case "find", "join" ->
            completed(playerRoutingHandler.suggestions(source, operation, arguments));
        case "install" -> completed(installationHandler.suggestions(source, arguments));
        case "dequeue" -> completed(playerRoutingHandler.suggestions(source, operation, arguments));
        case "reload" ->
            authorizer.canAdminister(source, "reload")
                ? completed(List.of("all", "blueprints", "software"))
                : completed(List.of());
        case "create", "delete", "kill", "start", "reset", "restart" ->
            completed(lifecycleHandler.suggestions(source, operation, arguments));
        case "info", "stats", "status" ->
            completed(inspectionHandler.suggestions(source, operation, arguments));
        case "stop" -> completed(lifecycleHandler.suggestions(source, operation, arguments));
        default -> completed(List.of());
      };
    }
    if (arguments.length == 3 && "join".equals(operation)) {
      return completed(playerRoutingHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 3
        && ("create".equals(operation) || "start".equals(operation))
        && authorizer.canAdminister(source, operation)) {
      return completed(lifecycleHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length >= 4
        && "create".equals(operation)
        && authorizer.canAdminister(source, operation)) {
      return completed(lifecycleHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 3 && "install".equals(operation)) {
      return completed(installationHandler.suggestions(source, arguments));
    }
    if (arguments.length == 3 && "admin".equals(operation)) {
      return completed(adminHandler.suggestions(source, arguments));
    }
    if (arguments.length == 3 && "logs".equals(operation)) {
      return completed(inspectionHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 3
        && "stop".equals(operation)
        && authorizer.canAdminister(source, "stop.force")) {
      return completed(lifecycleHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 3
        && "kill".equals(operation)
        && authorizer.canAdminister(source, "kill")) {
      return completed(lifecycleHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 3
        && ("restart".equals(operation) || "reset".equals(operation))
        && authorizer.canAdminister(source, operation + ".force")) {
      return completed(lifecycleHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 4 && "logs".equals(operation)) {
      return completed(inspectionHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 4 && "install".equals(operation)) {
      return completed(installationHandler.suggestions(source, arguments));
    }
    if (arguments.length == 4
        && "join".equals(operation)
        && authorizer.canTargetOthers(source, "join")) {
      return completed(playerRoutingHandler.suggestions(source, operation, arguments));
    }
    return completed(List.of());
  }

  private void sendRootHelp(CommandSource source) {
    source.sendMessage(CommandMessages.incorrectUsage());
    if (authorizer.canAdminister(source, "admin")) {
      source.sendMessage(
          CommandMessages.usage("/sls", VSLSCommandContract.ADMIN_ROOT.toArray(String[]::new)));
    } else {
      source.sendMessage(
          CommandMessages.usage("/sls", VSLSCommandContract.PUBLIC_ROOT.toArray(String[]::new)));
    }
  }

  private void debug(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "debug", "toggle SLS debug messages")) {
      return;
    }
    if (arguments.length != 1) {
      source.sendMessage(CommandMessages.usage("/sls", "debug"));
      return;
    }
    if (!(source instanceof Player player)) {
      source.sendMessage(
          CommandMessages.message("This command can only be run by a player.", NamedTextColor.RED));
      return;
    }

    boolean enabled = debugPlayers.toggle(player);
    logger.info("Debug mode {} for {}", enabled ? "enabled" : "disabled", player.getUsername());
    source.sendMessage(
        CommandMessages.message(
            enabled ? "Debug mode enabled." : "Debug mode disabled.", NamedTextColor.GRAY));
  }

  public void disconnectDebugPlayer(UUID playerId) {
    debugPlayers.remove(playerId);
  }

  public void close() {
    debugPlayers.clear();
  }

  private void console(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "console", "send managed server console commands")) {
      return;
    }
    if (arguments.length == 1) {
      source.sendMessage(CommandMessages.incorrectUsage());
      source.sendMessage(CommandMessages.usage("/sls console", "server"));
      return;
    }
    if (arguments.length == 2) {
      source.sendMessage(CommandMessages.incorrectUsage());
      source.sendMessage(CommandMessages.usage("/sls console " + arguments[1], "command"));
      return;
    }

    ManagedInstance instance = resolveInstance(source, arguments[1]);
    if (instance == null) {
      return;
    }
    String command = String.join(" ", java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
    try {
      instances.sendCommand(instance.id(), command);
      logger.info("Console command sent by {} to {}", commandSourceName(source), instance.id());
      source.sendMessage(
          CommandMessages.message("Command executed successfully", NamedTextColor.GRAY));
    } catch (InstanceOperationException exception) {
      source.sendMessage(
          CommandMessages.message(
              "Failed to send command to server " + instance.id() + ": " + exception.getMessage(),
              NamedTextColor.RED));
    }
  }

  static Component capabilityLine(HostCapability capability) {
    return InspectionCommandHandler.capabilityLine(capability);
  }

  private ManagedInstance resolveInstance(CommandSource source, String requestedId) {
    return instanceAccess.resolve(source, requestedId);
  }

  private void reload(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "reload", "reload SLS-LITE")) {
      return;
    }
    String mode = arguments.length == 1 ? "all" : arguments[1].toLowerCase(Locale.ROOT);
    if (arguments.length > 2 || !List.of("all", "blueprints", "software").contains(mode)) {
      source.sendMessage(CommandMessages.usage("/sls reload", "all", "blueprints", "software"));
      return;
    }
    try {
      DefinitionReloader.reload(
          activeConfig,
          blueprints,
          softwareProfiles,
          "all".equals(mode) || "blueprints".equals(mode),
          "all".equals(mode) || "software".equals(mode));
      source.sendMessage(
          CommandMessages.message(
              "Reloaded "
                  + mode
                  + ": "
                  + blueprints.getTypes().size()
                  + " registries, "
                  + blueprints.getAll().size()
                  + " blueprints, "
                  + softwareProfiles.getAll().size()
                  + " software profiles.",
              NamedTextColor.GREEN));
      source.sendMessage(
          CommandMessages.message(
              "Host config changes require a Velocity restart.", NamedTextColor.GRAY));
    } catch (Exception exception) {
      logger.error("Unable to reload SLS-LITE " + mode, exception);
      source.sendMessage(
          CommandMessages.message("Reload failed: " + rootMessage(exception), NamedTextColor.RED));
    }
  }

  private void sendVersion(CommandSource source) {
    source.sendMessage(
        CommandMessages.prefix()
            .append(
                Component.text("Version: ", NamedTextColor.DARK_AQUA).decorate(TextDecoration.BOLD))
            .append(Component.text(BuildInfo.VERSION, NamedTextColor.GOLD))
            .append(Component.text(" By: ", NamedTextColor.DARK_AQUA))
            .append(Component.text(BuildInfo.AUTHORS, NamedTextColor.GOLD)));
  }

  private boolean requireAdmin(CommandSource source, String permission, String operation) {
    if (authorizer.canAdminister(source, permission)) {
      return true;
    }
    permissionDenied(source, operation);
    return false;
  }

  private void permissionDenied(CommandSource source, String operation) {
    source.sendMessage(
        CommandMessages.message(
            "You do not have permission to " + operation + ".", NamedTextColor.RED));
  }

  private void unavailable(CommandSource source, String command, boolean distributedOnly) {
    if (!requireAdmin(source, command, "use /sls " + command)) {
      return;
    }
    String explanation =
        distributedOnly
            ? " is not available in local mode."
            : " is not available in this SLS-LITE build yet.";
    source.sendMessage(
        CommandMessages.prefix()
            .append(Component.text("/sls " + command, NamedTextColor.GOLD))
            .append(Component.text(explanation, NamedTextColor.GRAY)));
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

  private List<String> instanceIds() {
    return instanceAccess.activeIds();
  }

  private static List<String> withPrefix(String prefix, List<String> values) {
    List<String> result = new java.util.ArrayList<>(values.size() + 1);
    result.add(prefix);
    result.addAll(values);
    return List.copyOf(result);
  }

  private static CompletableFuture<List<String>> completed(List<String> values) {
    return CompletableFuture.completedFuture(values);
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
}
