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
import net.slimelabs.slslite.log.ConsoleBanner;
import org.slf4j.Logger;

import java.nio.file.Path;

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

    @Inject
    public SLSLite(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.blueprints = new BlueprintRepository(dataDirectory.resolve("blueprints"));
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        ConsoleBanner.logStartup(logger);

        try {
            blueprints.initialize();
        } catch (Exception exception) {
            logger.error("Unable to initialize SLS-LITE blueprints", exception);
        }

        CommandMeta commandMeta = proxy.getCommandManager()
                .metaBuilder("sls")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(commandMeta, new SLSCommand(blueprints, logger));

        logger.info("SLS-LITE initialized with {} blueprint(s)", blueprints.getAll().size());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ConsoleBanner.logShutdown(logger);
    }
}
