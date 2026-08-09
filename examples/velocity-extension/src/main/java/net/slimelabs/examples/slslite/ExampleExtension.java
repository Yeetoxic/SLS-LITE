package net.slimelabs.examples.slslite;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import net.slimelabs.slslite.api.Capability;
import net.slimelabs.slslite.api.BlueprintReadinessFinding;
import net.slimelabs.slslite.api.BlueprintReadinessStatus;
import net.slimelabs.slslite.api.DiagnosticsSnapshot;
import net.slimelabs.slslite.api.ExtensionContext;
import net.slimelabs.slslite.api.ExtensionDiagnosticFinding;
import net.slimelabs.slslite.api.ExtensionDiagnosticSeverity;
import net.slimelabs.slslite.api.SLSLiteApi;
import net.slimelabs.slslite.api.SLSLiteApiProvider;
import net.slimelabs.slslite.api.event.InstanceLifecycleEvent;
import net.slimelabs.slslite.api.event.PlayerMatchmakingEvent;
import org.slf4j.Logger;

@Plugin(
    id = "sls-lite-example-extension",
    name = "SLS-LITE Example Extension",
    version = "1.0.0-SNAPSHOT")
public final class ExampleExtension {

  private final ProxyServer proxy;
  private final Logger logger;
  private volatile ExtensionContext context;
  private volatile CommandMeta commandMeta;

  @Inject
  public ExampleExtension(ProxyServer proxy, Logger logger) {
    this.proxy = proxy;
    this.logger = logger;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent ignored) {
    SLSLiteApi api =
        SLSLiteApiProvider.find(proxy)
            .orElseThrow(() -> new IllegalStateException("SLS-LITE API provider is unavailable"));
    ExtensionContext owned = api.extension("example-extension");
    context = owned;

    owned.subscribe(
        event -> {
          if (event instanceof InstanceLifecycleEvent lifecycle) {
            logger.info(
                "SLS-LITE instance {} changed {} -> {}",
                lifecycle.instanceId(),
                lifecycle.previousStatus(),
                lifecycle.currentStatus());
          } else if (event instanceof PlayerMatchmakingEvent matchmaking) {
            logger.info(
                "SLS-LITE player {} matchmaking state is {} for {}",
                matchmaking.ticket().playerName(),
                matchmaking.status(),
                matchmaking.ticket().instanceId());
          }
        });

    if (api.capabilities().contains(Capability.EXTENSION_ACTIONS)) {
      owned.onInstanceReady(
          action ->
              logger.info(
                  "SLS-LITE instance {} is usable; annotation keys={}",
                  action.instance().id(),
                  action.annotations().values().keySet()));
      owned.onPostTransfer(
          action ->
              logger.info(
                  "SLS-LITE moved {} to {}; annotation keys={}",
                  action.ticket().playerName(),
                  action.ticket().instanceId(),
                  action.annotations().values().keySet()));
    }

    if (api.capabilities().contains(Capability.EXTENSION_BLUEPRINT_READINESS)) {
      owned.onBlueprintReadiness(
          (blueprint, annotations) ->
              Boolean.FALSE.equals(annotations.values().get("enabled"))
                  ? java.util.List.of(
                      new BlueprintReadinessFinding(
                          "disabled",
                          BlueprintReadinessStatus.ACTION_NEEDED,
                          "enable this blueprint's example-extension integration"))
                  : java.util.List.of());
    }

    if (api.capabilities().contains(Capability.EXTENSION_DIAGNOSTICS)) {
      owned.onDiagnostics(
          () ->
              java.util.List.of(
                  new ExtensionDiagnosticFinding(
                      "ready", ExtensionDiagnosticSeverity.INFO, "example extension is ready")));
    }

    owned.onComplete(
        api.ready(),
        (unused, failure) -> {
          if (failure != null) {
            logger.warn("SLS-LITE API readiness failed: {}", failure.getClass().getSimpleName());
            return;
          }
          DiagnosticsSnapshot diagnostics = api.diagnostics();
          logger.info(
              "SLS-LITE API {} ready: capabilities={}, blueprints={}, instances={}, queued={}, "
                  + "extensionDiagnostics={}",
              api.version(),
              api.capabilities().size(),
              api.blueprints().size(),
              api.instances().size(),
              diagnostics.system().queuedPlayers(),
              diagnostics.extensionDiagnostics().size());
        });

    CommandMeta meta =
        proxy.getCommandManager().metaBuilder("sls-api-example").plugin(this).build();
    try {
      proxy.getCommandManager().register(meta, new ExampleCommand(api, owned, logger));
      commandMeta = meta;
    } catch (RuntimeException failure) {
      context = null;
      owned.close();
      throw failure;
    }
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent ignored) {
    CommandMeta meta = commandMeta;
    commandMeta = null;
    if (meta != null) {
      proxy.getCommandManager().unregister(meta);
    }
    ExtensionContext owned = context;
    context = null;
    if (owned != null) {
      owned.close();
    }
  }
}
