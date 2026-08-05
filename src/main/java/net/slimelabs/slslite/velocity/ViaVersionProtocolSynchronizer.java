package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.platform.ProtocolDetectorService;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.slimelabs.slslite.config.ViaVersionSyncPolicy;
import org.slf4j.Logger;

public final class ViaVersionProtocolSynchronizer implements BackendProtocolSynchronizer {

  private static final long PING_TIMEOUT_SECONDS = 2;
  private static final long API_TIMEOUT_SECONDS = 10;

  private final ProtocolDetectorService configuredProtocols;
  private final Logger logger;

  ViaVersionProtocolSynchronizer(ProtocolDetectorService protocols, Logger logger) {
    this.configuredProtocols = protocols;
    this.logger = logger;
  }

  private ViaVersionProtocolSynchronizer(Logger logger) {
    this.configuredProtocols = null;
    this.logger = logger;
  }

  public static BackendProtocolSynchronizer create(ProxyServer proxy, Logger logger) {
    return create(proxy, logger, ViaVersionSyncPolicy.AUTO);
  }

  public static BackendProtocolSynchronizer create(
      ProxyServer proxy, Logger logger, ViaVersionSyncPolicy policy) {
    java.util.Objects.requireNonNull(proxy, "proxy");
    java.util.Objects.requireNonNull(logger, "logger");
    java.util.Objects.requireNonNull(policy, "policy");
    if (policy == ViaVersionSyncPolicy.OFF) {
      logger.info("ViaVersion backend protocol synchronization is disabled by host policy");
      return BackendProtocolSynchronizer.disabled();
    }
    if (proxy.getPluginManager().getPlugin("viaversion").isEmpty()) {
      if (policy == ViaVersionSyncPolicy.ON) {
        throw new IllegalStateException(
            "compatibility.viaversion_backend_sync=on requires ViaVersion to be installed and enabled");
      }
      return BackendProtocolSynchronizer.disabled();
    }
    try {
      ViaVersionProtocolSynchronizer synchronizer = new ViaVersionProtocolSynchronizer(logger);
      if (policy == ViaVersionSyncPolicy.ON) {
        synchronizer = new ViaVersionProtocolSynchronizer(synchronizer.protocols(), logger);
      }
      logger.info("ViaVersion integration enabled for dynamic backend protocol synchronization");
      return synchronizer;
    } catch (LinkageError | RuntimeException exception) {
      if (policy == ViaVersionSyncPolicy.ON) {
        throw new IllegalStateException(
            "compatibility.viaversion_backend_sync=on requires a compatible ViaVersion protocol API",
            exception);
      }
      logger.warn(
          "ViaVersion is installed but its protocol API is "
              + "incompatible; dynamic synchronization is disabled: {}",
          exception.getMessage());
      return BackendProtocolSynchronizer.disabled();
    }
  }

  @Override
  public void synchronize(
      String name,
      RegisteredServer server,
      OptionalInt knownProtocol,
      Optional<String> knownMinecraftVersion) {
    int protocol =
        knownProtocol.isPresent()
            ? knownProtocol.getAsInt()
            : knownMinecraftVersion
                .flatMap(ViaVersionProtocolSynchronizer::resolveProtocol)
                .orElseGet(() -> detectProtocol(name, server));
    protocols().setProtocolVersion(name, protocol);
    logger.info("Synchronized ViaVersion backend {} to protocol {}", name, protocol);
  }

  private static Optional<Integer> resolveProtocol(String minecraftVersion) {
    ProtocolVersion version = ProtocolVersion.getClosest(minecraftVersion);
    if (version == null || !version.isKnown()) {
      return Optional.empty();
    }
    return Optional.of(version.getVersion());
  }

  @Override
  public void remove(String name) {
    if (configuredProtocols != null || Via.isLoaded()) {
      protocols().uncacheProtocolVersion(name);
    }
  }

  private ProtocolDetectorService protocols() {
    if (configuredProtocols != null) {
      return configuredProtocols;
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(API_TIMEOUT_SECONDS);
    RuntimeException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        if (Via.isLoaded()) {
          return Via.proxyPlatform().protocolDetectorService();
        }
      } catch (RuntimeException exception) {
        lastFailure = exception;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(50);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while waiting for ViaVersion protocol API", exception);
      }
    }
    throw new IllegalStateException(
        "ViaVersion protocol API did not become ready within " + API_TIMEOUT_SECONDS + " seconds",
        lastFailure);
  }

  private static int detectProtocol(String name, RegisteredServer server) {
    try {
      return server
          .ping()
          .orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .join()
          .getVersion()
          .getProtocol();
    } catch (CompletionException exception) {
      throw new IllegalStateException(
          "Unable to detect protocol for dynamic backend " + name, exception.getCause());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Unable to detect protocol for dynamic backend " + name, exception);
    }
  }
}
