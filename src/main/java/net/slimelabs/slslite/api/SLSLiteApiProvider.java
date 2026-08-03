package net.slimelabs.slslite.api;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;

/** Implemented by the SLS-LITE Velocity plugin instance for dependency discovery. */
public interface SLSLiteApiProvider {

  SLSLiteApi api();

  /** Finds the API through Velocity's plugin manager without depending on the main plugin class. */
  static Optional<SLSLiteApi> find(ProxyServer proxy) {
    java.util.Objects.requireNonNull(proxy, "proxy");
    return proxy
        .getPluginManager()
        .getPlugin("sls-lite")
        .flatMap(container -> container.getInstance().filter(SLSLiteApiProvider.class::isInstance))
        .map(SLSLiteApiProvider.class::cast)
        .map(SLSLiteApiProvider::api);
  }
}
