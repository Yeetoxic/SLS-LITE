package net.slimelabs.slslite.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VelocityBackendRegistryTest {

  @Test
  void synchronizesAndRemovesDynamicRegistration() {
    Map<String, ServerInfo> registered = new HashMap<>();
    AtomicReference<String> synchronizedName = new AtomicReference<>();
    AtomicReference<OptionalInt> synchronizedProtocol = new AtomicReference<>();
    AtomicReference<String> removedName = new AtomicReference<>();
    BackendProtocolSynchronizer protocols =
        synchronizer(synchronizedName, synchronizedProtocol, removedName, false);
    VelocityBackendRegistry registry = new VelocityBackendRegistry(proxy(registered), protocols);

    registry.register("lobby.abc123", new InetSocketAddress("127.0.0.1", 25601), 775);
    registry.unregister("lobby.abc123");

    assertEquals("lobby.abc123", synchronizedName.get());
    assertEquals(OptionalInt.of(775), synchronizedProtocol.get());
    assertEquals("lobby.abc123", removedName.get());
    assertTrue(registered.isEmpty());
  }

  @Test
  void passesKnownMinecraftVersionToProtocolSynchronization() {
    Map<String, ServerInfo> registered = new HashMap<>();
    AtomicReference<Optional<String>> synchronizedVersion = new AtomicReference<>();
    BackendProtocolSynchronizer protocols =
        new BackendProtocolSynchronizer() {
          @Override
          public void synchronize(
              String name,
              RegisteredServer server,
              OptionalInt knownProtocol,
              Optional<String> knownMinecraftVersion) {
            synchronizedVersion.set(knownMinecraftVersion);
          }

          @Override
          public void remove(String name) {}
        };
    VelocityBackendRegistry registry = new VelocityBackendRegistry(proxy(registered), protocols);

    registry.register("missile_wars.abc123", new InetSocketAddress("127.0.0.1", 25602), "1.16.5");

    assertEquals(Optional.of("1.16.5"), synchronizedVersion.get());
  }

  @Test
  void rollsBackVelocityRegistrationWhenSynchronizationFails() {
    Map<String, ServerInfo> registered = new HashMap<>();
    VelocityBackendRegistry registry =
        new VelocityBackendRegistry(
            proxy(registered),
            synchronizer(
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(), true));

    assertThrows(
        IllegalStateException.class,
        () -> registry.register("lobby.failed", new InetSocketAddress("127.0.0.1", 25602)));
    assertTrue(registered.isEmpty());
  }

  @Test
  void restoresOnlyMissingOwnedRegistrationsDuringReconciliation() {
    Map<String, ServerInfo> registered = new HashMap<>();
    Map<String, RegisteredServer> servers = new HashMap<>();
    VelocityBackendRegistry registry =
        new VelocityBackendRegistry(
            proxy(registered, servers),
            synchronizer(
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(), false));
    registry.register("game.owned", new InetSocketAddress("127.0.0.1", 25603), 775);
    registered.clear();
    servers.clear();

    BackendRegistry.ReconciliationReport report = registry.reconcile();

    assertEquals(0, report.healthy());
    assertEquals(1, report.restored());
    assertTrue(report.conflicts().isEmpty());
    assertTrue(registered.containsKey("game.owned"));
  }

  @Test
  void leavesConflictingForeignRegistrationUntouched() {
    Map<String, ServerInfo> registered = new HashMap<>();
    Map<String, RegisteredServer> servers = new HashMap<>();
    VelocityBackendRegistry registry = new VelocityBackendRegistry(proxy(registered, servers));
    registry.register("game.shared", new InetSocketAddress("127.0.0.1", 25604));
    ServerInfo foreign = new ServerInfo("game.shared", new InetSocketAddress("127.0.0.1", 25999));
    RegisteredServer foreignServer = registeredServer(foreign);
    registered.put(foreign.getName(), foreign);
    servers.put(foreign.getName(), foreignServer);

    BackendRegistry.ReconciliationReport report = registry.reconcile();
    registry.close();

    assertEquals(1, report.conflicts().size());
    assertSame(foreignServer, servers.get("game.shared"));
    assertEquals(foreign, registered.get("game.shared"));
  }

  @Test
  void rollsBackMissingRegistrationWhenProtocolReconciliationFails() {
    Map<String, ServerInfo> registered = new HashMap<>();
    Map<String, RegisteredServer> servers = new HashMap<>();
    AtomicBoolean fail = new AtomicBoolean();
    BackendProtocolSynchronizer protocols =
        new BackendProtocolSynchronizer() {
          @Override
          public void synchronize(
              String name,
              RegisteredServer server,
              OptionalInt protocol,
              Optional<String> minecraftVersion) {
            if (fail.get()) {
              throw new IllegalStateException("protocol failure");
            }
          }

          @Override
          public void remove(String name) {}
        };
    VelocityBackendRegistry registry =
        new VelocityBackendRegistry(proxy(registered, servers), protocols);
    registry.register("game.protocol", new InetSocketAddress("127.0.0.1", 25607), 775);
    registered.clear();
    servers.clear();
    fail.set(true);

    BackendRegistry.ReconciliationReport report = registry.reconcile();

    assertEquals(0, report.restored());
    assertEquals(1, report.conflicts().size());
    assertTrue(registered.isEmpty());
    assertTrue(servers.isEmpty());
  }

  @Test
  void closeUnregistersEveryRemainingOwnedBackend() {
    Map<String, ServerInfo> registered = new HashMap<>();
    VelocityBackendRegistry registry = new VelocityBackendRegistry(proxy(registered));
    registry.register("game.one", new InetSocketAddress("127.0.0.1", 25605));
    registry.register("game.two", new InetSocketAddress("127.0.0.1", 25606));

    registry.close();

    assertTrue(registered.isEmpty());
  }

  private static BackendProtocolSynchronizer synchronizer(
      AtomicReference<String> synchronizedName,
      AtomicReference<OptionalInt> synchronizedProtocol,
      AtomicReference<String> removedName,
      boolean fail) {
    return new BackendProtocolSynchronizer() {
      @Override
      public void synchronize(
          String name,
          RegisteredServer server,
          OptionalInt protocol,
          Optional<String> minecraftVersion) {
        if (fail) {
          throw new IllegalStateException("test failure");
        }
        synchronizedName.set(name);
        synchronizedProtocol.set(protocol);
      }

      @Override
      public void remove(String name) {
        removedName.set(name);
      }
    };
  }

  private static ProxyServer proxy(Map<String, ServerInfo> registered) {
    return proxy(registered, new HashMap<>());
  }

  private static ProxyServer proxy(
      Map<String, ServerInfo> registered, Map<String, RegisteredServer> servers) {
    return (ProxyServer)
        Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getServer" -> Optional.ofNullable(servers.get((String) arguments[0]));
                  case "registerServer" -> {
                    ServerInfo info = (ServerInfo) arguments[0];
                    RegisteredServer server = registeredServer(info);
                    registered.put(info.getName(), info);
                    servers.put(info.getName(), server);
                    yield server;
                  }
                  case "unregisterServer" -> {
                    ServerInfo info = (ServerInfo) arguments[0];
                    registered.remove(info.getName());
                    servers.remove(info.getName());
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static RegisteredServer registeredServer(ServerInfo info) {
    return (RegisteredServer)
        Proxy.newProxyInstance(
            RegisteredServer.class.getClassLoader(),
            new Class<?>[] {RegisteredServer.class},
            (proxy, method, arguments) ->
                "getServerInfo".equals(method.getName())
                    ? info
                    : defaultValue(method.getReturnType()));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class || type == short.class || type == byte.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}
