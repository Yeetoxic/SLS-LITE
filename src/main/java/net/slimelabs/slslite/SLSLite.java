package net.slimelabs.slslite;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.slimelabs.slslite.api.SLSLiteApi;
import net.slimelabs.slslite.api.SLSLiteApiProvider;
import net.slimelabs.slslite.api.internal.DefaultSLSLiteApi;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.SLSCommand;
import net.slimelabs.slslite.config.ConfigurationValidator;
import net.slimelabs.slslite.config.DefinitionCatalog;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.SLSConfigRepository;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityChecker;
import net.slimelabs.slslite.host.HostCapabilityReport;
import net.slimelabs.slslite.install.PaperInstallationProvider;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.install.VanillaInstallationProvider;
import net.slimelabs.slslite.instance.InstanceManager;
import net.slimelabs.slslite.instance.lifecycle.IdleInstanceReaper;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciler;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciliationReport;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.io.SLSDataLayout;
import net.slimelabs.slslite.lobby.FallbackLobbyProvider;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import net.slimelabs.slslite.lobby.LocalLobbyProvider;
import net.slimelabs.slslite.lobby.SLSLimboHandoffService;
import net.slimelabs.slslite.lobby.SLSLimboProvider;
import net.slimelabs.slslite.log.ConsoleBanner;
import net.slimelabs.slslite.log.CorrelationIds;
import net.slimelabs.slslite.log.SLSDetailLog;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.security.AdminClaimService;
import net.slimelabs.slslite.security.AdministratorStore;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.BackendProtocolSynchronizer;
import net.slimelabs.slslite.velocity.BlueprintJoinActionService;
import net.slimelabs.slslite.velocity.LocalJoinService;
import net.slimelabs.slslite.velocity.VelocityBackendRegistry;
import net.slimelabs.slslite.velocity.ViaVersionProtocolSynchronizer;
import org.slf4j.Logger;

@Plugin(
    id = "sls-lite",
    name = "SLS-LITE",
    version = BuildInfo.VERSION,
    description = "Standalone, single-host SLS implementation for Velocity",
    url = "https://github.com/Yeetoxic/SLS-LITE",
    authors = {"Protoxon", "Yeetoxic"},
    dependencies = {@Dependency(id = "viaversion", optional = true)})
public final class SLSLite implements SLSLiteApiProvider {

  private final ProxyServer proxy;
  private final Logger logger;
  private final BlueprintRepository blueprints;
  private final SLSConfigRepository configuration;
  private final SoftwareProfileRepository softwareProfiles;
  private final Path dataDirectory;
  private final DefaultSLSLiteApi publicApi;
  private ResourceBudget resourceBudget;
  private ProcessSupervisor processSupervisor;
  private InstanceManager instanceManager;
  private LocalJoinService joinService;
  private BlueprintJoinActionService joinActions;
  private LobbyProvider lobbyProvider;
  private SLSLimboHandoffService limboHandoff;
  private IdleInstanceReaper idleReaper;
  private HostCapabilityReport hostCapabilities;
  private AdministratorStore administrators;
  private AdminClaimService adminClaims;
  private SoftwareInstallationService installationService;
  private SLSCommand slsCommand;
  private SLSDetailLog detailLog;

  @Inject
  public SLSLite(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    this.publicApi = new DefaultSLSLiteApi(proxy, logger);
    DefinitionCatalog definitions = new DefinitionCatalog();
    this.blueprints =
        new BlueprintRepository(this.dataDirectory.resolve("blueprints"), definitions);
    this.configuration =
        new SLSConfigRepository(this.dataDirectory, Path.of("").toAbsolutePath().normalize());
    this.softwareProfiles =
        new SoftwareProfileRepository(this.dataDirectory.resolve("software-profiles"), definitions);
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    ProxyRecoveryTiming recoveryTiming = new ProxyRecoveryTiming();
    String startupCorrelation = CorrelationIds.next("startup");
    ConsoleBanner.logStartup(logger);

    try {
      SLSDataLayout.initialize(dataDirectory);
      configuration.initialize();
      detailLog =
          new SLSDetailLog(
              dataDirectory,
              Path.of("").toAbsolutePath().normalize(),
              configuration.get().detailedLogging(),
              logger);
      detailLog.normal(
          startupCorrelation,
          "startup",
          "SLS-LITE startup began; detailed level={} path={}",
          configuration.get().detailedLogging().level(),
          detailLog.path());
      administrators = new AdministratorStore(dataDirectory);
      administrators.initialize();
      adminClaims =
          new AdminClaimService(
              administrators,
              proxy.getConfiguration().isOnlineMode(),
              configuration.get().security().allowInsecureOfflineAdministrators(),
              Duration.ofSeconds(configuration.get().security().claimCodeExpirySeconds()));
      softwareProfiles.initialize();
      blueprints.initialize();
      ConfigurationValidator.validate(
          configuration.get(),
          blueprints,
          softwareProfiles,
          proxy.getConfiguration().isOnlineMode());
      if (configuration.get().forwarding().mode() == ForwardingMode.NONE) {
        logger.warn(
            "Managed player forwarding is disabled; forwarding.mode=none "
                + "is intended only for isolated development");
      }
      resourceBudget = new ResourceBudget(configuration.get().totalMemoryMiB());
      LoopbackPortAllocator portAllocator =
          new LoopbackPortAllocator(
              configuration.get().portRangeStart(), configuration.get().portRangeEnd());
      JavaJarProcessSpecFactory processSpecFactory = new JavaJarProcessSpecFactory(dataDirectory);
      hostCapabilities =
          new HostCapabilityChecker()
              .check(
                  configuration.get().instancesDirectory(),
                  portAllocator,
                  blueprints.getAll(),
                  softwareProfiles.getAll(),
                  processSpecFactory,
                  configuration.get().totalMemoryMiB(),
                  configuration.get().storage());
      logHostCapabilities(hostCapabilities, startupCorrelation);
      if (hostCapabilities.hasFailures()) {
        throw new IllegalStateException(
            "Required host capability checks failed: " + hostCapabilities.failureSummary());
      }
      InstanceDirectoryPreparer directoryPreparer =
          new InstanceDirectoryPreparer(
              configuration.get().instancesDirectory(),
              dataDirectory,
              configuration.get().storage(),
              hostCapabilities
                  .selectedStorageStrategy()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Host storage selection did not produce " + "an active strategy")));
      InstanceReconciliationReport reconciliation =
          new InstanceReconciler(directoryPreparer, logger, detailLog, startupCorrelation)
              .reconcile();
      logger.info(
          "Instance reconciliation recovered {} storage transaction(s) "
              + "and inspected {} directorie(s): "
              + "{} stale ephemeral removed, {} persistent preserved, "
              + "{} running preserved, {} unknown preserved, {} failure(s)",
          reconciliation.recoveredStorageTransactions(),
          reconciliation.inspected(),
          reconciliation.removedEphemeral(),
          reconciliation.preservedPersistent(),
          reconciliation.preservedRunning(),
          reconciliation.preservedUnknown(),
          reconciliation.failures());
      processSupervisor = new ProcessSupervisor(configuration.get().maxManagedProcesses());
      installationService =
          new SoftwareInstallationService(
              processSpecFactory,
              List.of(new PaperInstallationProvider(), new VanillaInstallationProvider()),
              logger);
      BackendProtocolSynchronizer protocolSynchronizer =
          ViaVersionProtocolSynchronizer.create(proxy, logger);
      instanceManager =
          new InstanceManager(
              blueprints,
              softwareProfiles,
              resourceBudget,
              configuration.get().managedOutput(),
              configuration.get().forwarding(),
              portAllocator,
              directoryPreparer,
              processSpecFactory,
              processSupervisor,
              new VelocityBackendRegistry(proxy, protocolSynchronizer),
              installationService,
              detailLog,
              logger);
      joinService =
          new LocalJoinService(
              proxy,
              blueprints,
              instanceManager,
              Duration.ofSeconds(configuration.get().queueTimeoutSeconds()),
              logger,
              detailLog,
              net.slimelabs.slslite.velocity.BlueprintSelectionStrategy.forMode(
                  configuration.get().blueprintSelectionMode()));
      joinActions = new BlueprintJoinActionService(instanceManager, logger);
      LobbyProvider primaryLobby =
          new LocalLobbyProvider(
              proxy, blueprints, instanceManager, configuration.get().lobby(), logger);
      LobbyProvider slsLimbo =
          new SLSLimboProvider(
              proxy,
              configuration.get().limbo(),
              configuration.get().forwarding(),
              dataDirectory,
              resourceBudget,
              portAllocator,
              processSupervisor,
              new VelocityBackendRegistry(proxy, protocolSynchronizer),
              logger,
              detailLog);
      lobbyProvider = new FallbackLobbyProvider(proxy, primaryLobby, slsLimbo, logger);
      limboHandoff = new SLSLimboHandoffService(lobbyProvider, logger);
      idleReaper =
          new IdleInstanceReaper(
              proxy,
              instanceManager,
              joinService,
              lobbyProvider,
              configuration.get().idleShutdownSeconds(),
              logger);
      publicApi.activate(blueprints, instanceManager, joinService, lobbyProvider);
    } catch (Exception exception) {
      logger.error(
          "SLS-LITE initialization failed; managed server features are disabled", exception);
      if (detailLog != null) {
        detailLog.normal(
            startupCorrelation, "startup", "Initialization failed: {}", exception.getMessage());
        detailLog.close();
        detailLog = null;
      }
      publicApi.fail();
      return;
    }

    CommandMeta commandMeta = proxy.getCommandManager().metaBuilder("sls").plugin(this).build();
    slsCommand =
        new SLSCommand(
            proxy,
            blueprints,
            softwareProfiles,
            resourceBudget,
            processSupervisor,
            instanceManager,
            joinService,
            lobbyProvider,
            configuration.get().managedOutput(),
            configuration.get(),
            hostCapabilities,
            administrators,
            adminClaims,
            installationService,
            publicApi::publishCatalogReload,
            logger);
    proxy.getCommandManager().register(commandMeta, slsCommand);
    issueInitialAdministratorCode();
    lobbyProvider.addPrimaryReadyListener(
        server ->
            recoveryTiming
                .complete("ready", server.getServerInfo().getName())
                .ifPresent(
                    summary ->
                        detailLog.detailed(
                            startupCorrelation, "timing", "Proxy restart recovery: {}", summary)));
    lobbyProvider.start();
    idleReaper.start();

    if (configuration.get().detailedLogging().level()
        == net.slimelabs.slslite.config.DetailLogLevel.OFF) {
      logger.info(
          "SLS-LITE initialized [{}]: {} blueprint(s), {} software profile(s), "
              + "{} MiB managed memory; detailed logging is off",
          startupCorrelation,
          blueprints.getAll().size(),
          softwareProfiles.getAll().size(),
          resourceBudget.totalMemoryMiB());
    } else {
      logger.info(
          "SLS-LITE initialized [{}]: {} blueprint(s), {} software profile(s), "
              + "{} MiB managed memory; details in {}",
          startupCorrelation,
          blueprints.getAll().size(),
          softwareProfiles.getAll().size(),
          resourceBudget.totalMemoryMiB(),
          detailLog.path());
    }
    detailLog.normal(startupCorrelation, "startup", "Initialization completed successfully");
  }

  private void issueInitialAdministratorCode() {
    if (!administrators.isEmpty()) {
      return;
    }
    try {
      String code = adminClaims.issueCode();
      logger.warn("No SLS-LITE administrator is configured");
      logger.warn(
          "Join the proxy and run /sls admin claim {} within {} seconds",
          code,
          configuration.get().security().claimCodeExpirySeconds());
    } catch (AdminClaimService.InsecureOfflineModeException exception) {
      logger.warn("No SLS-LITE administrator is configured");
      logger.warn(
          "Administrator claims are disabled because Velocity is in "
              + "offline mode. Enable online mode or explicitly set "
              + "security.allow_insecure_offline_administrators=true");
    }
  }

  private void logHostCapabilities(HostCapabilityReport report, String correlationId) {
    int passed = 0;
    int informational = 0;
    int warnings = 0;
    int failures = 0;
    for (HostCapability capability : report.capabilities()) {
      String message = "Host capability [{}]: {} - {}";
      switch (capability.status()) {
        case FAILURE -> {
          failures++;
          logger.error(message, capability.status(), capability.name(), capability.detail());
        }
        case WARNING -> {
          warnings++;
          logger.warn(message, capability.status(), capability.name(), capability.detail());
        }
        case PASS -> passed++;
        case INFO -> informational++;
      }
      detailLog.detailed(
          correlationId,
          "capability",
          "status={} name={} detail={}",
          capability.status(),
          capability.name(),
          capability.detail());
    }
    logger.info(
        "Host capability summary [{}]: {} passed, {} informational, {} warning(s), {} failure(s)",
        correlationId,
        passed,
        informational,
        warnings,
        failures);
  }

  @Subscribe
  public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
    if (lobbyProvider != null) {
      var lobby = lobbyProvider.server();
      if (lobby.isPresent()) {
        var selected = lobby.orElseThrow();
        if (lobbyProvider.isHoldingLobby(selected.getServerInfo().getName())) {
          limboHandoff.awaitPrimary(event.getPlayer());
        }
        event.setInitialServer(selected);
      } else {
        event.getPlayer().disconnect(lobbyUnavailableMessage());
      }
      return;
    }
    if (joinService != null && event.getInitialServer().isEmpty()) {
      joinService.initialServer().ifPresent(event::setInitialServer);
    }
  }

  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    if (lobbyProvider == null) {
      return;
    }
    if (lobbyProvider.isLobby(event.getServer().getServerInfo().getName())) {
      var fallback = lobbyProvider.fallbackServer(event.getServer().getServerInfo().getName());
      if (fallback.isPresent()) {
        var selected = fallback.orElseThrow();
        if (lobbyProvider.isHoldingLobby(selected.getServerInfo().getName())) {
          limboHandoff.awaitPrimary(event.getPlayer());
        }
        event.setResult(
            KickedFromServerEvent.RedirectPlayer.create(
                selected, Component.text("Moving you to SLS-Limbo.")));
        return;
      }
      event.setResult(KickedFromServerEvent.DisconnectPlayer.create(lobbyUnavailableMessage()));
      return;
    }
    var lobby = lobbyProvider.server();
    if (lobby.isPresent()) {
      var selected = lobby.orElseThrow();
      if (lobbyProvider.isHoldingLobby(selected.getServerInfo().getName())) {
        limboHandoff.awaitPrimary(event.getPlayer());
      }
      event.setResult(
          KickedFromServerEvent.RedirectPlayer.create(
              selected, Component.text("Returning you to the lobby.")));
    } else {
      event.setResult(KickedFromServerEvent.DisconnectPlayer.create(lobbyUnavailableMessage()));
    }
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    if (joinService != null) {
      joinService.connected(event.getPlayer(), event.getServer());
    }
    if (limboHandoff != null) {
      limboHandoff.connected(event.getPlayer(), event.getServer());
    }
    if (joinActions != null) {
      joinActions.connected(event.getPlayer(), event.getServer());
    }
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    if (slsCommand != null) {
      slsCommand.disconnectPlayer(event.getPlayer().getUniqueId());
    }
    if (limboHandoff != null) {
      limboHandoff.disconnect(event.getPlayer().getUniqueId());
    }
    if (joinService != null) {
      joinService.disconnect(event.getPlayer().getUniqueId());
    }
    if (joinActions != null) {
      joinActions.disconnect(event.getPlayer().getUniqueId());
    }
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    if (slsCommand != null) {
      slsCommand.close();
    }
    if (idleReaper != null) {
      idleReaper.close();
    }
    if (limboHandoff != null) {
      limboHandoff.close();
    }
    if (lobbyProvider != null) {
      lobbyProvider.close();
    }
    if (joinService != null) {
      joinService.close();
    }
    if (instanceManager != null) {
      logger.info("Stopping {} managed instance(s)", instanceManager.getAll().size());
      instanceManager.shutdown(Duration.ofSeconds(35));
    }
    if (installationService != null) {
      installationService.close();
    }
    if (detailLog != null) {
      detailLog.normal("shutdown", "shutdown", "SLS-LITE shutdown completed");
      detailLog.close();
    }
    publicApi.close();
    ConsoleBanner.logShutdown(logger);
  }

  @Override
  public SLSLiteApi api() {
    return publicApi;
  }

  private Component lobbyUnavailableMessage() {
    LobbyStatus current = lobbyProvider == null ? LobbyStatus.OFFLINE : lobbyProvider.status();
    if (current == LobbyStatus.STARTING || current == LobbyStatus.RECOVERING) {
      return Component.text("The lobby is restarting. Please reconnect shortly.");
    }
    return Component.text("The lobby is currently unavailable. Please try again later.");
  }
}
