package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessCatalog;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessDiagnostics;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessSummary;
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
import net.slimelabs.slslite.instance.DefinitionChangeImpact;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.instance.lifecycle.MaintenanceStatus;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.log.CorrelationIds;
import net.slimelabs.slslite.log.SLSDetailLog;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.security.AdminClaimService;
import net.slimelabs.slslite.security.AdministratorStore;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.BackendRegistry;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

public final class SLSCommand implements SimpleCommand {

  private final BlueprintRepository blueprints;
  private final ProxyServer proxy;
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
  private final ConsoleOutputSessions consoleOutput;
  private final OperatorJoinProbeService joinProbes;
  private final Logger logger;
  private final SLSDetailLog detailLog;
  private final BackendRegistry backendRegistry;
  private final Consumer<DefinitionReloader.DefinitionReloadTransition> reloadObserver;
  private BlueprintReadinessCatalog readinessCatalog;

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
        installationService,
        ignored -> {},
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
      Consumer<DefinitionReloader.DefinitionReloadTransition> reloadObserver,
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
        installationService,
        reloadObserver,
        SLSDetailLog.disabled(),
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
      Consumer<DefinitionReloader.DefinitionReloadTransition> reloadObserver,
      SLSDetailLog detailLog,
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
        installationService,
        reloadObserver,
        detailLog,
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
      Consumer<DefinitionReloader.DefinitionReloadTransition> reloadObserver,
      SLSDetailLog detailLog,
      BackendRegistry backendRegistry,
      Logger logger) {
    this.proxy = proxy;
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
            blueprints, instances, joinService, lobbyProvider, authorizer, instanceAccess, logger);
    this.installationHandler =
        new InstallationCommandHandler(
            blueprints, softwareProfiles, installationService, authorizer, instances);
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
            instanceAccess,
            detailLog);
    this.playerRoutingHandler =
        new PlayerRoutingCommandHandler(
            proxy, blueprints, joinService, authorizer, instanceAccess, logger);
    this.debugPlayers =
        new DebugPlayerRegistry(
            new DebugInstanceActionBar(instanceAccess, joinService),
            player -> authorizer.canAdminister(player, "debug"));
    this.consoleOutput = new ConsoleOutputSessions();
    this.joinProbes = new OperatorJoinProbeService();
    this.reloadObserver = java.util.Objects.requireNonNull(reloadObserver, "reloadObserver");
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
    this.backendRegistry = backendRegistry;
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
      case "join-test" -> joinTest(invocation.source(), arguments);
      case "kill" -> lifecycleHandler.kill(invocation.source(), arguments);
      case "list" -> list(invocation.source(), arguments);
      case "logs" -> logs(invocation.source(), arguments);
      case "maintenance" -> maintenance(invocation.source(), arguments);
      case "registries" -> registries(invocation.source(), arguments);
      case "reload" -> reload(invocation.source(), arguments);
      case "reset" -> lifecycleHandler.reset(invocation.source(), arguments);
      case "restart" -> lifecycleHandler.restart(invocation.source(), arguments);
      case "start" -> lifecycleHandler.start(invocation.source(), arguments);
      case "stats" -> inspectionHandler.stats(invocation.source(), arguments);
      case "status" -> inspectionHandler.status(invocation.source(), arguments);
      case "stop" -> lifecycleHandler.stop(invocation.source(), arguments);
      case "system" -> inspectionHandler.system(invocation.source(), arguments);
      case "version" -> sendVersion(invocation.source(), arguments);
      case "pause", "resume" -> unavailable(invocation.source(), arguments[0], false);
      case "node" -> unavailable(invocation.source(), arguments[0], true);
      default -> sendRootHelp(invocation.source());
    }
  }

  /** Installs the shared readiness snapshot before this command is registered. */
  public void installReadinessCatalog(BlueprintReadinessCatalog catalog) {
    if (readinessCatalog != null) {
      throw new IllegalStateException("Blueprint readiness catalog is already installed");
    }
    readinessCatalog = java.util.Objects.requireNonNull(catalog, "catalog");
    inspectionHandler.installReadinessCatalog(catalog);
  }

  /** Installs the bounded extension diagnostic snapshot used by `/sls system`. */
  public void installExtensionDiagnostics(
      java.util.function.Supplier<java.util.List<net.slimelabs.slslite.api.ExtensionDiagnosticView>>
          diagnostics) {
    inspectionHandler.installExtensionDiagnostics(diagnostics);
  }

  @Override
  public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
    String[] arguments = invocation.arguments();
    CommandSource source = invocation.source();
    if (arguments.length <= 1) {
      List<String> suggestions = new java.util.ArrayList<>(VSLSCommandContract.PUBLIC_SUGGESTIONS);
      VSLSCommandContract.ADMIN_SUGGESTIONS.stream()
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
        case "logs" ->
            authorizer.canAdminister(source, "logs")
                ? completed(withPrefix(VSLSCommandContract.CONSOLE_UNFOLLOW, instanceIds()))
                : completed(List.of());
        case "find", "join" ->
            completed(playerRoutingHandler.suggestions(source, operation, arguments));
        case "join-test" ->
            authorizer.canAdminister(source, operation)
                ? completed(withPrefix("this", instanceIds()))
                : completed(List.of());
        case "install" -> completed(installationHandler.suggestions(source, arguments));
        case "maintenance" ->
            authorizer.canAdminister(source, operation)
                ? completed(List.of("on", "off", "status"))
                : completed(List.of());
        case "dequeue" -> completed(playerRoutingHandler.suggestions(source, operation, arguments));
        case "reload" ->
            authorizer.canAdminister(source, "reload")
                ? completed(
                    List.of("all", "blueprints", "software", VSLSCommandContract.RELOAD_CONFIG))
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
    if (arguments.length == 3
        && "console".equals(operation)
        && authorizer.canAdminister(source, "console")) {
      return completed(
          List.of(VSLSCommandContract.CONSOLE_FOLLOW, VSLSCommandContract.CONSOLE_UNFOLLOW));
    }
    if (arguments.length == 3 && "logs".equals(operation)) {
      return authorizer.canAdminister(source, "logs")
          ? completed(List.of(VSLSCommandContract.CONSOLE_FOLLOW, "1"))
          : completed(List.of());
    }
    if (arguments.length == 3 && "status".equals(operation)) {
      return completed(inspectionHandler.suggestions(source, operation, arguments));
    }
    if (arguments.length == 3
        && "stop".equals(operation)
        && authorizer.canAdminister(source, "stop")) {
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
    List<String> options = new java.util.ArrayList<>(VSLSCommandContract.PUBLIC_ROOT);
    if (authorizer.canAdminister(source, "admin")) {
      options = VSLSCommandContract.ADMIN_ROOT;
    } else {
      for (String entry : VSLSCommandContract.ADMIN_ROOT) {
        int separator = entry.indexOf(' ');
        String root = entry.substring(0, separator < 0 ? entry.length() : separator);
        if (!options.contains(entry) && authorizer.canAdminister(source, root)) {
          options.add(entry);
        }
      }
    }
    source.sendMessage(CommandMessages.usage("/sls", options.toArray(String[]::new)));
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
    disconnectPlayer(playerId);
  }

  public void disconnectPlayer(UUID playerId) {
    debugPlayers.remove(playerId);
    consoleOutput.remove(playerId);
  }

  public void close() {
    debugPlayers.close();
    installationHandler.close();
    consoleOutput.close();
    joinProbes.close();
  }

  private void joinTest(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "join-test", "run managed backend join tests")) {
      return;
    }
    if (arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls join-test", "server"));
      return;
    }
    ManagedInstance instance = resolveInstance(source, arguments[1]);
    if (instance == null) {
      return;
    }
    RegisteredServer registered =
        proxy == null ? null : proxy.getServer(instance.id()).orElse(null);
    if (registered == null) {
      source.sendMessage(
          CommandMessages.message(
              "Join test cannot run because "
                  + instance.id()
                  + " is not registered as a ready Velocity backend.",
              NamedTextColor.RED));
      return;
    }

    source.sendMessage(
        CommandMessages.message(
            "Running a bounded status probe against " + instance.id() + "...",
            NamedTextColor.GRAY));
    joinProbes
        .probe(instance.id(), registered)
        .thenAccept(result -> sendJoinTestResult(source, instance.id(), result));
  }

  private static void sendJoinTestResult(
      CommandSource source, String instanceId, OperatorJoinProbeService.Result result) {
    if (source instanceof Player player && !player.isActive()) {
      return;
    }
    switch (result.status()) {
      case SUCCESS ->
          source.sendMessage(
              CommandMessages.message(
                  "Join test passed for "
                      + instanceId
                      + " in "
                      + result.elapsedMillis()
                      + " ms: backend reported "
                      + result.versionName()
                      + " (protocol "
                      + result.protocol()
                      + ").",
                  NamedTextColor.GREEN));
      case FAILED ->
          source.sendMessage(
              CommandMessages.message(
                  "Join test failed for " + instanceId + ": " + result.detail(),
                  NamedTextColor.RED));
      case REJECTED ->
          source.sendMessage(CommandMessages.message(result.detail(), NamedTextColor.YELLOW));
    }
    source.sendMessage(
        CommandMessages.message(
            "This verifies backend reachability and Minecraft status negotiation only; "
                + "it does not verify player login, forwarding, permissions, or transfer.",
            NamedTextColor.GRAY));
  }

  private void console(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "console", "send managed server console commands")) {
      return;
    }
    if (arguments.length == 1) {
      source.sendMessage(CommandMessages.incorrectUsage());
      source.sendMessage(CommandMessages.usage("/sls console", "server", "command"));
      source.sendMessage(
          CommandMessages.message(
              "To read live output, use /sls logs <server|this> --follow.", NamedTextColor.GRAY));
      return;
    }
    if (arguments.length == 2) {
      sendConsoleUsage(source);
      return;
    }

    if (isFollowModifier(arguments[2]) && arguments.length != 3) {
      sendConsoleUsage(source);
      return;
    }

    if (arguments.length == 3
        && VSLSCommandContract.CONSOLE_UNFOLLOW.equalsIgnoreCase(arguments[2])) {
      stopFollowing(source);
      return;
    }
    ManagedInstance instance = resolveInstance(source, arguments[1]);
    if (instance == null) {
      return;
    }
    if (arguments.length == 3
        && VSLSCommandContract.CONSOLE_FOLLOW.equalsIgnoreCase(arguments[2])) {
      startFollowing(source, instance, "console");
      return;
    }
    String command = String.join(" ", java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
    try {
      long outputCursor = instance.outputCursor();
      instances.sendCommand(instance.id(), command);
      logger.info("Console command sent by {} to {}", commandSourceName(source), instance.id());
      source.sendMessage(
          CommandMessages.message(
              "Sent command to " + instance.id() + "; capturing its bounded response.",
              NamedTextColor.GRAY));
      if (!consoleOutput.isFollowing(source)
          && !consoleOutput.capture(source, instance, outputCursor)) {
        source.sendMessage(
            CommandMessages.message(
                "Console output capture is already pending or temporarily at capacity.",
                NamedTextColor.YELLOW));
      }
    } catch (InstanceOperationException exception) {
      source.sendMessage(
          CommandMessages.message(
              "Failed to send command to server " + instance.id() + ": " + exception.getMessage(),
              NamedTextColor.RED));
    }
  }

  private void logs(CommandSource source, String[] arguments) {
    if (arguments.length == 2
        && VSLSCommandContract.CONSOLE_UNFOLLOW.equalsIgnoreCase(arguments[1])) {
      if (requireAdmin(source, "logs", "view managed server logs")) {
        stopFollowing(source);
      }
      return;
    }
    if (arguments.length == 3
        && VSLSCommandContract.CONSOLE_FOLLOW.equalsIgnoreCase(arguments[2])) {
      if (!requireAdmin(source, "logs", "view managed server logs")) {
        return;
      }
      ManagedInstance instance = resolveInstance(source, arguments[1]);
      if (instance != null) {
        startFollowing(source, instance, "logs");
      }
      return;
    }
    if (containsFollowModifier(arguments)) {
      sendLogsUsage(source);
      return;
    }
    inspectionHandler.logs(source, arguments);
  }

  private static boolean containsFollowModifier(String[] arguments) {
    return java.util.Arrays.stream(arguments).skip(1).anyMatch(SLSCommand::isFollowModifier);
  }

  private static boolean isFollowModifier(String argument) {
    return VSLSCommandContract.CONSOLE_FOLLOW.equalsIgnoreCase(argument)
        || VSLSCommandContract.CONSOLE_UNFOLLOW.equalsIgnoreCase(argument);
  }

  private static void sendConsoleUsage(CommandSource source) {
    source.sendMessage(CommandMessages.incorrectUsage());
    source.sendMessage(CommandMessages.usage("/sls console", "server", "command"));
    source.sendMessage(
        CommandMessages.message(
            "To read live output, use /sls logs <server|this> --follow.", NamedTextColor.GRAY));
  }

  private static void sendLogsUsage(CommandSource source) {
    source.sendMessage(CommandMessages.incorrectUsage());
    source.sendMessage(CommandMessages.usage("/sls logs", "server|this", "--follow"));
    source.sendMessage(CommandMessages.usage("/sls logs", "--unfollow"));
  }

  private void startFollowing(
      CommandSource source, ManagedInstance instance, String permissionOperation) {
    ConsoleOutputSessions.FollowResult result =
        consoleOutput.follow(
            source, instance, () -> authorizer.canAdminister(source, permissionOperation));
    switch (result.status()) {
      case STARTED ->
          source.sendMessage(
              followFeedback(source, "Following live output from " + result.instanceId() + "."));
      case MOVED ->
          source.sendMessage(
              followFeedback(
                  source,
                  "Live output moved from "
                      + result.previousInstanceId()
                      + " to "
                      + result.instanceId()
                      + "."));
      case CAPACITY ->
          source.sendMessage(
              CommandMessages.message(
                  "Live-output capacity is currently full; try again later.",
                  NamedTextColor.YELLOW));
      case CLOSED ->
          source.sendMessage(
              CommandMessages.message(
                  "Live output is unavailable while SLS-LITE is shutting down.",
                  NamedTextColor.YELLOW));
    }
  }

  private void stopFollowing(CommandSource source) {
    java.util.Optional<String> stopped = consoleOutput.unfollow(source);
    source.sendMessage(
        CommandMessages.message(
            stopped
                .map(instanceId -> "Stopped following live output from " + instanceId + ".")
                .orElse("You are not following managed live output."),
            NamedTextColor.GRAY));
  }

  private static Component followFeedback(CommandSource source, String message) {
    Component feedback = CommandMessages.message(message + " ", NamedTextColor.GRAY);
    if (!(source instanceof Player)) {
      return feedback;
    }
    return feedback.append(
        Component.text("[Stop following]", NamedTextColor.RED)
            .clickEvent(ClickEvent.runCommand("/sls logs " + VSLSCommandContract.CONSOLE_UNFOLLOW))
            .hoverEvent(Component.text("Stop live managed output", NamedTextColor.GRAY)));
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
    if (arguments.length > 2
        || !List.of("all", "blueprints", "software", VSLSCommandContract.RELOAD_CONFIG)
            .contains(mode)) {
      source.sendMessage(
          CommandMessages.usage("/sls reload", "all", "blueprints", "software", "config"));
      return;
    }
    if (VSLSCommandContract.RELOAD_CONFIG.equals(mode)) {
      source.sendMessage(
          CommandMessages.message(
              "Live config reload is unavailable in local mode; "
                  + "restart Velocity to rebuild host-wide services safely.",
              NamedTextColor.GRAY));
      return;
    }
    String correlationId = CorrelationIds.next("reload");
    try {
      net.slimelabs.slslite.config.DefinitionReloadReport report =
          DefinitionReloader.reload(
              activeConfig,
              blueprints,
              softwareProfiles,
              "all".equals(mode) || "blueprints".equals(mode),
              "all".equals(mode) || "software".equals(mode),
              correlationId,
              reloadObserver);
      DefinitionChangeImpact impact = DefinitionChangeImpact.assess(report, instances);
      source.sendMessage(
          CommandMessages.message(
              "Reloaded "
                  + mode
                  + " atomically: blueprints accepted="
                  + report.acceptedBlueprints()
                  + ", rejected="
                  + report.rejectedBlueprints().size()
                  + "; changes "
                  + report.blueprints().summary()
                  + "; software "
                  + report.software().summary()
                  + ".",
              report.rejectedBlueprints().isEmpty()
                  ? NamedTextColor.GREEN
                  : NamedTextColor.YELLOW));
      source.sendMessage(
          CommandMessages.message(
              "Application: " + impact.nextAction(),
              impact.runningInstances() == 0 && impact.persistentInstances() == 0
                  ? NamedTextColor.GRAY
                  : NamedTextColor.YELLOW));
      logger.info(
          "Definition reload [{}] {} committed: blueprint added={} updated={} removed={}; "
              + "software added={} updated={} removed={}",
          correlationId,
          mode,
          report.blueprints().added(),
          report.blueprints().updated(),
          report.blueprints().removed(),
          report.software().added(),
          report.software().updated(),
          report.software().removed());
      report
          .rejectedBlueprints()
          .forEach(
              rejection ->
                  detailLog.normal(
                      correlationId,
                      "blueprint-reload",
                      "Rejected {}: {}",
                      rejection.path(),
                      rejection.error()));
      if (!report.rejectedBlueprints().isEmpty()) {
        logger.warn(
            "Definition reload [{}] committed with {} rejected blueprint(s); "
                + "accepted siblings remain available and rejection details are in the "
                + "SLS-LITE detail log",
            correlationId,
            report.rejectedBlueprints().size());
      }
      if (readinessCatalog != null) {
        BlueprintReadinessSummary readiness =
            readinessCatalog.refresh(
                blueprints.getAll(), softwareProfiles.getAll(), blueprints.rejections());
        source.sendMessage(
            CommandMessages.message(
                "Blueprint readiness: ready="
                    + readiness.ready()
                    + ", action-needed="
                    + readiness.actionNeeded()
                    + ", temporarily-unavailable="
                    + readiness.temporarilyUnavailable()
                    + ".",
                readiness.actionNeeded() == 0 && readiness.temporarilyUnavailable() == 0
                    ? NamedTextColor.GREEN
                    : NamedTextColor.YELLOW));
        BlueprintReadinessDiagnostics.write(readinessCatalog, detailLog, correlationId);
      }
      if (backendRegistry != null) {
        BackendRegistry.ReconciliationReport registrations = backendRegistry.reconcile();
        NamedTextColor registrationColor =
            registrations.conflicts().isEmpty() ? NamedTextColor.GRAY : NamedTextColor.YELLOW;
        source.sendMessage(
            CommandMessages.message(
                "Dynamic registrations: healthy="
                    + registrations.healthy()
                    + ", restored="
                    + registrations.restored()
                    + ", conflicts="
                    + registrations.conflicts().size()
                    + ".",
                registrationColor));
        registrations
            .conflicts()
            .forEach(
                conflict ->
                    detailLog.normal(
                        correlationId, "registration-reload", "Conflict: {}", conflict));
        if (!registrations.conflicts().isEmpty()) {
          logger.warn(
              "Definition reload [{}] left {} conflicting dynamic registration(s) untouched; "
                  + "details are in the SLS-LITE detail log",
              correlationId,
              registrations.conflicts().size());
        }
      }
      source.sendMessage(
          CommandMessages.message(
              "Host config changes require a Velocity restart.", NamedTextColor.GRAY));
    } catch (Exception exception) {
      logger.error("Unable to reload SLS-LITE " + mode + " [" + correlationId + "]", exception);
      source.sendMessage(
          CommandMessages.message("Reload failed: " + rootMessage(exception), NamedTextColor.RED));
    }
  }

  private void maintenance(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "maintenance", "change maintenance mode")) {
      return;
    }
    if (arguments.length < 2) {
      source.sendMessage(CommandMessages.usage("/sls maintenance", "on [reason]", "off", "status"));
      return;
    }
    String action = arguments[1].toLowerCase(Locale.ROOT);
    if ("status".equals(action)) {
      if (arguments.length != 2) {
        source.sendMessage(CommandMessages.usage("/sls maintenance", "status"));
        return;
      }
      sendMaintenanceStatus(source, instances.maintenanceStatus());
      return;
    }
    if ("off".equals(action) && arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls maintenance", "off"));
      return;
    }
    if (!"on".equals(action) && !"off".equals(action)) {
      source.sendMessage(CommandMessages.usage("/sls maintenance", "on [reason]", "off", "status"));
      return;
    }
    String reason =
        "on".equals(action) && arguments.length > 2
            ? String.join(" ", java.util.Arrays.copyOfRange(arguments, 2, arguments.length))
            : "";
    try {
      MaintenanceStatus status = instances.setMaintenance("on".equals(action), reason);
      sendMaintenanceStatus(source, status);
    } catch (InstanceOperationException exception) {
      source.sendMessage(CommandMessages.message(exception.getMessage(), NamedTextColor.RED));
    }
  }

  private static void sendMaintenanceStatus(CommandSource source, MaintenanceStatus status) {
    String detail =
        status.enabled()
            ? "Maintenance mode is enabled; new instance creation is blocked"
                + (status.reason().isBlank() ? "." : ": " + status.reason())
            : "Maintenance mode is disabled; new instance creation is allowed.";
    source.sendMessage(
        CommandMessages.message(
            detail, status.enabled() ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
  }

  private void list(CommandSource source, String[] arguments) {
    if (arguments.length != 1) {
      source.sendMessage(CommandMessages.usage("/sls list"));
      return;
    }
    inspectionHandler.list(source);
  }

  private void registries(CommandSource source, String[] arguments) {
    if (arguments.length != 1) {
      source.sendMessage(CommandMessages.usage("/sls registries"));
      return;
    }
    inspectionHandler.registries(source);
  }

  private void sendVersion(CommandSource source, String[] arguments) {
    if (arguments.length != 1) {
      source.sendMessage(CommandMessages.usage("/sls version"));
      return;
    }
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
    String explanation;
    if (distributedOnly) {
      explanation =
          " is not available in local mode because SLS-LITE has no daemon/node control plane. "
              + "Use /sls system for this host and local lifecycle commands for its instances.";
    } else if ("pause".equalsIgnoreCase(command)) {
      explanation =
          " is not available in this SLS-LITE build because process suspension has no safe "
              + "portable implementation. Leave the instance running, or use /sls stop for "
              + "a persistent instance.";
    } else {
      explanation =
          " is not available in this SLS-LITE build because process suspension has no safe "
              + "portable implementation. Use /sls restart <server> to recover a stopped "
              + "persistent instance.";
    }
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
