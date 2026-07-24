package net.slimelabs.slslite;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.SLSCommand;
import net.slimelabs.slslite.config.ConfigurationValidator;
import net.slimelabs.slslite.config.SLSConfigRepository;
import net.slimelabs.slslite.instance.InstanceDirectoryPreparer;
import net.slimelabs.slslite.log.ConsoleBanner;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.PaperProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;

@Plugin(
        id = "sls-lite",
        name = "SLS-LITE",
        version = BuildInfo.VERSION,
        description = "Standalone, single-host SLS implementation for Velocity",
        url = "https://github.com/Yeetoxic/SLS-LITE",
        authors = {"Protoxon", "Yeetoxic"}
)
public final class SLSLite {

    private final ProxyServer proxy;
    private final Logger logger;
    private final BlueprintRepository blueprints;
    private final SLSConfigRepository configuration;
    private final SoftwareProfileRepository softwareProfiles;
    private final Path dataDirectory;
    private ResourceBudget resourceBudget;
    private LoopbackPortAllocator portAllocator;
    private InstanceDirectoryPreparer directoryPreparer;
    private PaperProcessSpecFactory processSpecFactory;
    private ProcessSupervisor processSupervisor;

    @Inject
    public SLSLite(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.blueprints = new BlueprintRepository(this.dataDirectory.resolve("blueprints"));
        this.configuration = new SLSConfigRepository(this.dataDirectory);
        this.softwareProfiles = new SoftwareProfileRepository(
                this.dataDirectory.resolve("software-profiles")
        );
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        ConsoleBanner.logStartup(logger);

        try {
            configuration.initialize();
            softwareProfiles.initialize();
            blueprints.initialize();
            ConfigurationValidator.validate(
                    configuration.get(),
                    blueprints,
                    softwareProfiles
            );
            resourceBudget = new ResourceBudget(configuration.get().totalMemoryMiB());
            portAllocator = new LoopbackPortAllocator(
                    configuration.get().portRangeStart(),
                    configuration.get().portRangeEnd()
            );
            directoryPreparer = new InstanceDirectoryPreparer(
                    configuration.get().instancesDirectory()
            );
            processSpecFactory = new PaperProcessSpecFactory(dataDirectory);
            int portCount = configuration.get().portRangeEnd()
                    - configuration.get().portRangeStart() + 1;
            processSupervisor = new ProcessSupervisor(Math.min(portCount, 16));
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
                new SLSCommand(blueprints, softwareProfiles, resourceBudget, logger)
        );

        logger.info(
                "SLS-LITE initialized with {} blueprint(s), {} software profile(s), "
                        + "and {} MiB managed memory",
                blueprints.getAll().size(),
                softwareProfiles.getAll().size(),
                resourceBudget.totalMemoryMiB()
        );
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (processSupervisor != null) {
            logger.info(
                    "Stopping {} managed process(es)",
                    processSupervisor.activeProcesses().size()
            );
            processSupervisor.shutdown(Duration.ofSeconds(35));
        }
        ConsoleBanner.logShutdown(logger);
    }
}
