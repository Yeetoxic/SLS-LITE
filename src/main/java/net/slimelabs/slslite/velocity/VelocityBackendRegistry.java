package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class VelocityBackendRegistry implements BackendRegistry {

  private final ProxyServer proxy;
  private final BackendProtocolSynchronizer protocols;
  private final Map<String, OwnedRegistration> registrations = new LinkedHashMap<>();

  public VelocityBackendRegistry(ProxyServer proxy) {
    this(proxy, BackendProtocolSynchronizer.disabled());
  }

  public VelocityBackendRegistry(ProxyServer proxy, BackendProtocolSynchronizer protocols) {
    this.proxy = proxy;
    this.protocols = protocols;
  }

  @Override
  public synchronized void register(String name, InetSocketAddress address) {
    register(name, address, OptionalInt.empty(), Optional.empty());
  }

  @Override
  public synchronized void register(String name, InetSocketAddress address, int protocol) {
    register(
        name,
        address,
        protocol > 0 ? OptionalInt.of(protocol) : OptionalInt.empty(),
        Optional.empty());
  }

  @Override
  public synchronized void register(
      String name, InetSocketAddress address, String minecraftVersion) {
    register(
        name,
        address,
        OptionalInt.empty(),
        Optional.ofNullable(minecraftVersion).filter(version -> !version.isBlank()));
  }

  private void register(
      String name,
      InetSocketAddress address,
      OptionalInt protocol,
      Optional<String> minecraftVersion) {
    if (registrations.containsKey(name) || proxy.getServer(name).isPresent()) {
      throw new IllegalStateException("Velocity server name is already registered: " + name);
    }
    OwnedRegistration owned =
        new OwnedRegistration(new ServerInfo(name, address), protocol, minecraftVersion);
    RegisteredServer registeredServer = proxy.registerServer(owned.serverInfo());
    try {
      protocols.synchronize(name, registeredServer, protocol, minecraftVersion);
    } catch (RuntimeException exception) {
      proxy.unregisterServer(owned.serverInfo());
      throw exception;
    }
    registrations.put(name, owned);
  }

  @Override
  public synchronized void unregister(String name) {
    OwnedRegistration owned = registrations.remove(name);
    if (owned == null) {
      return;
    }
    Optional<RegisteredServer> current = proxy.getServer(name);
    if (current.isEmpty() || matches(current.orElseThrow(), owned.serverInfo())) {
      protocols.remove(name);
      current.ifPresent(ignored -> proxy.unregisterServer(owned.serverInfo()));
    }
  }

  @Override
  public synchronized ReconciliationReport reconcile() {
    int healthy = 0;
    int restored = 0;
    java.util.List<String> conflicts = new java.util.ArrayList<>();
    java.util.Iterator<Map.Entry<String, OwnedRegistration>> iterator =
        registrations.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, OwnedRegistration> entry = iterator.next();
      String name = entry.getKey();
      OwnedRegistration owned = entry.getValue();
      Optional<RegisteredServer> current = proxy.getServer(name);
      if (current.isPresent() && !matches(current.orElseThrow(), owned.serverInfo())) {
        conflicts.add(
            name
                + " is registered at "
                + current.orElseThrow().getServerInfo().getAddress()
                + " instead of SLS-LITE's owned address "
                + owned.serverInfo().getAddress());
        iterator.remove();
        continue;
      }
      boolean missing = current.isEmpty();
      RegisteredServer server = null;
      try {
        if (missing) {
          server = proxy.registerServer(owned.serverInfo());
        } else {
          server = current.orElseThrow();
        }
        protocols.synchronize(name, server, owned.protocol(), owned.minecraftVersion());
        if (missing) {
          restored++;
        } else {
          healthy++;
        }
      } catch (RuntimeException exception) {
        if (missing && server != null) {
          try {
            protocols.remove(name);
          } catch (RuntimeException ignored) {
            // The original synchronization failure remains the useful diagnostic.
          }
          proxy.unregisterServer(owned.serverInfo());
        }
        conflicts.add(name + " could not be reconciled: " + rootMessage(exception));
      }
    }
    return new ReconciliationReport(healthy, restored, conflicts);
  }

  @Override
  public synchronized void close() {
    for (Map.Entry<String, OwnedRegistration> entry : registrations.entrySet()) {
      String name = entry.getKey();
      OwnedRegistration owned = entry.getValue();
      Optional<RegisteredServer> current = proxy.getServer(name);
      if (current.isEmpty() || matches(current.orElseThrow(), owned.serverInfo())) {
        protocols.remove(name);
        current.ifPresent(ignored -> proxy.unregisterServer(owned.serverInfo()));
      }
    }
    registrations.clear();
  }

  private static boolean matches(RegisteredServer server, ServerInfo expected) {
    ServerInfo actual = server.getServerInfo();
    return actual.getName().equals(expected.getName())
        && actual.getAddress().equals(expected.getAddress());
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private record OwnedRegistration(
      ServerInfo serverInfo, OptionalInt protocol, Optional<String> minecraftVersion) {}
}
