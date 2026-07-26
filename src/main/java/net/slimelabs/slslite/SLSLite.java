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
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.SLSCommand;
import net.slimelabs.slslite.config.ConfigurationValidator;
import net.slimelabs.slslite.config.SLSConfigRepository;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityChecker;
import net.slimelabs.slslite.host.HostCapabilityReport;
import net.slimelabs.slslite.host.HostCapabilityStatus;
import net.slimelabs.slslite.instance.InstanceDirectoryPreparer;
import net.slimelabs.slslite.instance.IdleInstanceReaper;
import net.slimelabs.slslite.instance.InstanceManager;
import net.slimelabs.slslite.instance.InstanceReconciler;
import net.slimelabs.slslite.instance.InstanceReconciliationReport;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import net.slimelabs.slslite.lobby.LocalLobbyProvider;
import net.slimelabs.slslite.lobby.SLSLimboProvider;
import net.slimelabs.slslite.lobby.SLSLimboHandoffService;
import net.slimelabs.slslite.lobby.FallbackLobbyProvider;
import net.slimelabs.slslite.log.ConsoleBanner;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.PaperProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.security.AdminClaimService;
import net.slimelabs.slslite.security.AdministratorStore;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;
import net.slimelabs.slslite.velocity.BackendProtocolSynchronizer;
import net.slimelabs.slslite.velocity.ViaVersionProtocolSynchronizer;
import net.slimelabs.slslite.velocity.VelocityBackendRegistry;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;

@Plugin(
        id = "sls-lite",
        name = "SLS-LITE",
        version = BuildInfo.VERSION,
        description = "Standalone, single-host SLS implementation for Velocity",
        url = "https://github.com/Yeetoxic/SLS-LITE",
        authors = {"Protoxon", "Yeetoxic"},
        dependencies = {@Dependency(id = "viaversion", optional = true)}
)
public final class SLSLite {

    private final ProxyServer proxy;
    private final Logger logger;
    private final BlueprintRepository blueprints;
    private final SLSConfigRepository configuration;
    private final SoftwareProfileRepository softwareProfiles;
    private final Path dataDirectory;
    private ResourceBudget resourceBudget;
    private ProcessSupervisor processSupervisor;
    private InstanceManager instanceManager;
    private LocalJoinService joinService;
    private LobbyProvider lobbyProvider;
    private SLSLimboHandoffService limboHandoff;
    private IdleInstanceReaper idleReaper;
    private HostCapabilityReport hostCapabilities;
    private AdministratorStore administrators;
    private AdminClaimService adminClaims;

    @Inject
    public SLSLite(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.blueprints = new BlueprintRepository(this.dataDirectory.resolve("blueprints"));
        this.configuration = new SLSConfigRepository(
                this.dataDirectory,
                Path.of("").toAbsolutePath().normalize()
        );
        this.softwareProfiles = new SoftwareProfileRepository(
                this.dataDirectory.resolve("software-profiles")
        );
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        ConsoleBanner.logStartup(logger);

        try {
            configuration.initialize();
            administrators = new AdministratorStore(dataDirectory);
            administrators.initialize();
            adminClaims = new AdminClaimService(
                    administrators,
                    proxy.getConfiguration().isOnlineMode(),
                    configuration.get().security().allowInsecureOfflineAdministrators(),
                    Duration.ofSeconds(
                            configuration.get().security().claimCodeExpirySeconds()
                    )
            );
            softwareProfiles.initialize();
            blueprints.initialize();
            ConfigurationValidator.validate(
                    configuration.get(),
                    blueprints,
                    softwareProfiles
            );
            resourceBudget = new ResourceBudget(configuration.get().totalMemoryMiB());
            LoopbackPortAllocator portAllocator = new LoopbackPortAllocator(
                    configuration.get().portRangeStart(),
                    configuration.get().portRangeEnd()
            );
            InstanceDirectoryPreparer directoryPreparer = new InstanceDirectoryPreparer(
                    configuration.get().instancesDirectory()
            );
            PaperProcessSpecFactory processSpecFactory = new PaperProcessSpecFactory(dataDirectory);
            hostCapabilities = new HostCapabilityChecker().check(
                    configuration.get().instancesDirectory(),
                    portAllocator,
                    softwareProfiles.getAll(),
                    processSpecFactory,
                    configuration.get().totalMemoryMiB()
            );
            logHostCapabilities(hostCapabilities);
            if (hostCapabilities.hasFailures()) {
                throw new IllegalStateException(
                        "Required host capability checks failed: "
                                + hostCapabilities.failureSummary()
                );
            }
            InstanceReconciliationReport reconciliation = new InstanceReconciler(
                    directoryPreparer,
                    logger
            ).reconcile();
            logger.info(
                    "Instance reconciliation inspected {} directorie(s): "
                            + "{} stale ephemeral removed, {} persistent preserved, "
                            + "{} running preserved, {} unknown preserved, {} failure(s)",
                    reconciliation.inspected(),
                    reconciliation.removedEphemeral(),
                    reconciliation.preservedPersistent(),
                    reconciliation.preservedRunning(),
                    reconciliation.preservedUnknown(),
                    reconciliation.failures()
            );
            int portCount = configuration.get().portRangeEnd()
                    - configuration.get().portRangeStart() + 1;
            processSupervisor = new ProcessSupervisor(Math.min(portCount, 16));
            BackendProtocolSynchronizer protocolSynchronizer =
                    ViaVersionProtocolSynchronizer.create(proxy, logger);
            instanceManager = new InstanceManager(
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
                    logger
            );
            joinService = new LocalJoinService(
                    proxy,
                    blueprints,
                    instanceManager,
                    Duration.ofSeconds(configuration.get().queueTimeoutSeconds())
            );
            LobbyProvider primaryLobby = new LocalLobbyProvider(
                    proxy,
                    blueprints,
                    instanceManager,
                    configuration.get().lobby(),
                    logger
            );
            LobbyProvider slsLimbo = new SLSLimboProvider(
                    proxy,
                    configuration.get().limbo(),
                    configuration.get().forwarding(),
                    dataDirectory,
                    resourceBudget,
                    portAllocator,
                    processSupervisor,
                    new VelocityBackendRegistry(proxy, protocolSynchronizer),
                    logger
            );
            lobbyProvider = new FallbackLobbyProvider(
                    proxy,
                    primaryLobby,
                    slsLimbo,
                    logger
            );
            limboHandoff = new SLSLimboHandoffService(lobbyProvider, logger);
            idleReaper = new IdleInstanceReaper(
                    proxy,
                    instanceManager,
                    joinService,
                    lobbyProvider,
                    configuration.get().idleShutdownSeconds(),
                    logger
            );
        } catch (Exception exception) {
            logger.error(
                    "SLS-LITE initialization failed; managed server features are disabled",
                    exception
            );
            return;
        }

        CommandMeta commandMeta = proxy.getCommandManager()
                .metaBuilder("sls")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(
                commandMeta,
                new SLSCommand(
                        proxy,
                        blueprints,
                        softwareProfiles,
                        resourceBudget,
                        instanceManager,
                        joinService,
                        lobbyProvider,
                        configuration.get().managedOutput(),
                        configuration.get(),
                        hostCapabilities,
                        administrators,
                        adminClaims,
                        logger
                )
        );
        issueInitialAdministratorCode();
        lobbyProvider.start();
        idleReaper.start();

        logger.info(
                "SLS-LITE initialized with {} blueprint(s), {} software profile(s), "
                        + "and {} MiB managed memory",
                blueprints.getAll().size(),
                softwareProfiles.getAll().size(),
                resourceBudget.totalMemoryMiB()
        );
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
                    configuration.get().security().claimCodeExpirySeconds()
            );
        } catch (AdminClaimService.InsecureOfflineModeException exception) {
            logger.warn("No SLS-LITE administrator is configured");
            logger.warn(
                    "Administrator claims are disabled because Velocity is in "
                            + "offline mode. Enable online mode or explicitly set "
                            + "security.allow_insecure_offline_administrators=true"
            );
        }
    }

    private void logHostCapabilities(HostCapabilityReport report) {
        for (HostCapability capability : report.capabilities()) {
            String message = "Host capability [{}]: {} - {}";
            if (capability.status() == HostCapabilityStatus.FAILURE) {
                logger.error(
                        message,
                        capability.status(),
                        capability.name(),
                        capability.detail()
                );
            } else if (capability.status() == HostCapabilityStatus.WARNING) {
                logger.warn(
                        message,
                        capability.status(),
                        capability.name(),
                        capability.detail()
                );
            } else {
                logger.info(
                        message,
                        capability.status(),
                        capability.name(),
                        capability.detail()
                );
            }
        }
    }

    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (lobbyProvider != null) {
            var lobby = lobbyProvider.server();
            if (lobby.isPresent()) {
                var selected = lobby.orElseThrow();
                if (lobbyProvider.isHoldingLobby(
                        selected.getServerInfo().getName()
                )) {
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
            var fallback = lobbyProvider.fallbackServer(
                    event.getServer().getServerInfo().getName()
            );
            if (fallback.isPresent()) {
                var selected = fallback.orElseThrow();
                if (lobbyProvider.isHoldingLobby(
                        selected.getServerInfo().getName()
                )) {
                    limboHandoff.awaitPrimary(event.getPlayer());
                }
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                        selected,
                        Component.text("Moving you to SLS-Limbo.")
                ));
                return;
            }
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    lobbyUnavailableMessage()
            ));
            return;
        }
        var lobby = lobbyProvider.server();
        if (lobby.isPresent()) {
            var selected = lobby.orElseThrow();
            if (lobbyProvider.isHoldingLobby(
                    selected.getServerInfo().getName()
            )) {
                limboHandoff.awaitPrimary(event.getPlayer());
            }
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                    selected,
                    Component.text("Returning you to the lobby.")
            ));
        } else {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    lobbyUnavailableMessage()
            ));
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (limboHandoff != null) {
            limboHandoff.connected(event.getPlayer(), event.getServer());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (limboHandoff != null) {
            limboHandoff.disconnect(event.getPlayer().getUniqueId());
        }
        if (joinService != null) {
            joinService.disconnect(event.getPlayer().getUniqueId());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
            logger.info(
                    "Stopping {} managed instance(s)",
                    instanceManager.getAll().size()
            );
            instanceManager.shutdown(Duration.ofSeconds(35));
        }
        ConsoleBanner.logShutdown(logger);
    }

    private Component lobbyUnavailableMessage() {
        LobbyStatus current = lobbyProvider == null
                ? LobbyStatus.OFFLINE
                : lobbyProvider.status();
        if (current == LobbyStatus.STARTING || current == LobbyStatus.RECOVERING) {
            return Component.text(
                    "The lobby is restarting. Please reconnect shortly."
            );
        }
        return Component.text(
                "The lobby is currently unavailable. Please try again later."
        );
    }
}
