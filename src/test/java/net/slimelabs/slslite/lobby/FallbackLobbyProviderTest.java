package net.slimelabs.slslite.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FallbackLobbyProviderTest {

  @Test
  void startsSLSLimboFirstAndPrefersReadyPrimary() {
    List<String> starts = new ArrayList<>();
    RegisteredServer primaryServer = server("lobby");
    RegisteredServer limboServer = server("sls-limbo");
    LobbyProvider primary = provider("primary", "lobby", primaryServer, starts);
    LobbyProvider limbo = provider("limbo", "sls-limbo", limboServer, starts);
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(), primary, limbo, LoggerFactory.getLogger(FallbackLobbyProviderTest.class));

    fallback.start();

    assertEquals(List.of("limbo", "primary"), starts);
    assertSame(primaryServer, fallback.server().orElseThrow());
    assertSame(primaryServer, fallback.readyFuture().join());
    assertSame(limboServer, fallback.fallbackServer("lobby").orElseThrow());
    assertEquals(Optional.empty(), fallback.primaryServer());
    assertEquals(Optional.empty(), fallback.fallbackServer("sls-limbo"));
    fallback.close();
  }

  @Test
  void usesSLSLimboWhilePrimaryHasNoReadyServer() {
    RegisteredServer limboServer = server("sls-limbo");
    LobbyProvider primary = provider("primary", "lobby", null, new ArrayList<>());
    LobbyProvider limbo = provider("limbo", "sls-limbo", limboServer, new ArrayList<>());
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(), primary, limbo, LoggerFactory.getLogger(FallbackLobbyProviderTest.class));

    fallback.start();

    assertSame(limboServer, fallback.server().orElseThrow());
    assertSame(limboServer, fallback.readyFuture().join());
    fallback.close();
  }

  @Test
  void healthyExternalPrimaryCompletesReadinessWhenLimboIsUnavailable() {
    RegisteredServer primaryServer = server("lobby");
    LobbyProvider primary =
        provider("primary", "lobby", primaryServer, LobbyStatus.EXTERNAL, new ArrayList<>());
    LobbyProvider limbo =
        provider("limbo", "sls-limbo", null, LobbyStatus.OFFLINE, new ArrayList<>());
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(), primary, limbo, LoggerFactory.getLogger(FallbackLobbyProviderTest.class));

    fallback.start();

    assertSame(primaryServer, fallback.readyFuture().join());
    assertSame(primaryServer, fallback.server().orElseThrow());
    fallback.close();
  }

  @Test
  void reportsDegradedStateWhenUnhealthyExternalPrimaryAndLimboAreUnavailable() {
    RegisteredServer primaryServer = server("lobby");
    LobbyProvider primary =
        provider("primary", "lobby", primaryServer, LobbyStatus.EXTERNAL, new ArrayList<>());
    LobbyProvider limbo =
        provider("limbo", "sls-limbo", null, LobbyStatus.OFFLINE, new ArrayList<>());
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(), primary, limbo, LoggerFactory.getLogger(FallbackLobbyProviderTest.class));

    fallback.start();
    fallback.markPrimaryUnavailable("lobby");

    assertTrue(fallback.server().isEmpty());
    assertTrue(fallback.bothUnavailable());
    fallback.close();
  }

  @Test
  void rejectsLifecycleDrainAndUsesLimboForUnreachableExternalPrimary() {
    RegisteredServer primaryServer = unreachableServer("lobby");
    RegisteredServer limboServer = server("sls-limbo");
    LobbyProvider primary =
        provider("primary", "lobby", primaryServer, LobbyStatus.EXTERNAL, new ArrayList<>());
    LobbyProvider limbo = provider("limbo", "sls-limbo", limboServer, new ArrayList<>());
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(), primary, limbo, LoggerFactory.getLogger(FallbackLobbyProviderTest.class));
    fallback.start();

    assertTrue(fallback.isLobby("lobby"));
    assertFalse(fallback.ownsPrimaryLifecycle("lobby"));
    assertFalse(fallback.beginIntentionalStop("lobby"));
    assertSame(limboServer, fallback.server().orElseThrow());

    fallback.close();
  }

  @Test
  void forcedPrimaryStopEvacuatesPlayersToSLSLimbo() {
    AtomicReference<RegisteredServer> requestedServer = new AtomicReference<>();
    Player player = player(requestedServer);
    RegisteredServer primaryServer = server("lobby", List.of(player));
    RegisteredServer limboServer = server("sls-limbo");
    Map<String, RegisteredServer> servers = new LinkedHashMap<>();
    servers.put("lobby", primaryServer);
    servers.put("sls-limbo", limboServer);
    LobbyProvider primary = provider("primary", "lobby", primaryServer, new ArrayList<>());
    LobbyProvider limbo = provider("limbo", "sls-limbo", limboServer, new ArrayList<>());
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(servers),
            primary,
            limbo,
            LoggerFactory.getLogger(FallbackLobbyProviderTest.class));
    fallback.start();

    assertTrue(fallback.beginIntentionalStop("lobby"));
    assertSame(limboServer, fallback.server().orElseThrow());
    fallback.refreshPrimaryAvailability();
    assertSame(limboServer, fallback.server().orElseThrow());
    fallback.evacuateForIntentionalStop("lobby").join();

    assertSame(limboServer, requestedServer.get());
    fallback.close();
  }

  @Test
  void cancelledPrimaryStopRestoresPrimaryRouting() {
    RegisteredServer primaryServer = server("lobby");
    RegisteredServer limboServer = server("sls-limbo");
    LobbyProvider primary = provider("primary", "lobby", primaryServer, new ArrayList<>());
    LobbyProvider limbo = provider("limbo", "sls-limbo", limboServer, new ArrayList<>());
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(), primary, limbo, LoggerFactory.getLogger(FallbackLobbyProviderTest.class));
    fallback.start();

    assertTrue(fallback.beginIntentionalStop("lobby"));
    assertFalse(fallback.beginIntentionalStop("lobby"));
    assertSame(limboServer, fallback.server().orElseThrow());

    fallback.cancelIntentionalStop("lobby");

    assertSame(primaryServer, fallback.server().orElseThrow());
    fallback.close();
  }

  @Test
  void completedPrimaryCycleRestoresPrimaryRouting() {
    RegisteredServer primaryServer = server("lobby");
    RegisteredServer limboServer = server("sls-limbo");
    LobbyProvider primary = provider("primary", "lobby", primaryServer, new ArrayList<>());
    LobbyProvider limbo = provider("limbo", "sls-limbo", limboServer, new ArrayList<>());
    FallbackLobbyProvider fallback =
        new FallbackLobbyProvider(
            proxy(), primary, limbo, LoggerFactory.getLogger(FallbackLobbyProviderTest.class));
    fallback.start();

    assertTrue(fallback.beginIntentionalStop("lobby"));
    assertSame(limboServer, fallback.server().orElseThrow());
    assertSame(primaryServer, fallback.cyclePrimary("lobby", false).join());
    assertSame(primaryServer, fallback.server().orElseThrow());
    fallback.close();
  }

  private static LobbyProvider provider(
      String label, String serverName, RegisteredServer server, List<String> starts) {
    return provider(
        label,
        serverName,
        server,
        server == null ? LobbyStatus.STARTING : LobbyStatus.READY,
        starts);
  }

  private static LobbyProvider provider(
      String label,
      String serverName,
      RegisteredServer server,
      LobbyStatus status,
      List<String> starts) {
    CompletableFuture<RegisteredServer> ready =
        server == null ? new CompletableFuture<>() : CompletableFuture.completedFuture(server);
    return new LobbyProvider() {
      @Override
      public void start() {
        starts.add(label);
      }

      @Override
      public Optional<RegisteredServer> server() {
        return Optional.ofNullable(server);
      }

      @Override
      public CompletableFuture<RegisteredServer> readyFuture() {
        return ready;
      }

      @Override
      public LobbyStatus status() {
        return status;
      }

      @Override
      public boolean isLobby(String candidate) {
        return serverName.equals(candidate);
      }

      @Override
      public boolean ownsPrimaryLifecycle(String candidate) {
        return status != LobbyStatus.EXTERNAL && serverName.equals(candidate);
      }

      @Override
      public CompletableFuture<Void> evacuate(String candidate) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletableFuture<RegisteredServer> cyclePrimary(String candidate, boolean reset) {
        return serverName.equals(candidate) && server != null
            ? CompletableFuture.completedFuture(server)
            : CompletableFuture.failedFuture(new IllegalStateException("Primary is unavailable"));
      }

      @Override
      public void close() {}
    };
  }

  private static ProxyServer proxy() {
    return proxy(Map.of());
  }

  private static ProxyServer proxy(Map<String, RegisteredServer> servers) {
    return (ProxyServer)
        Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getServer" -> Optional.ofNullable(servers.get(arguments[0]));
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static RegisteredServer server(String name) {
    return server(name, List.of());
  }

  private static RegisteredServer server(String name, Collection<Player> players) {
    return server(name, players, CompletableFuture.completedFuture(null));
  }

  private static RegisteredServer unreachableServer(String name) {
    return server(
        name,
        List.of(),
        CompletableFuture.failedFuture(new IllegalStateException("Backend is offline")));
  }

  private static RegisteredServer server(
      String name, Collection<Player> players, CompletableFuture<?> pingResult) {
    ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", 25566));
    return (RegisteredServer)
        Proxy.newProxyInstance(
            RegisteredServer.class.getClassLoader(),
            new Class<?>[] {RegisteredServer.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getServerInfo" -> info;
                  case "getPlayersConnected" -> players;
                  case "ping" -> pingResult;
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static Player player(AtomicReference<RegisteredServer> requestedServer) {
    ConnectionRequestBuilder.Result result =
        (ConnectionRequestBuilder.Result)
            Proxy.newProxyInstance(
                ConnectionRequestBuilder.Result.class.getClassLoader(),
                new Class<?>[] {ConnectionRequestBuilder.Result.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getStatus" -> ConnectionRequestBuilder.Status.SUCCESS;
                      case "getReasonComponent" -> Optional.empty();
                      default -> defaultValue(method.getReturnType());
                    });
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> {
              if ("getUsername".equals(method.getName())) {
                return "FallbackTester";
              }
              if ("createConnectionRequest".equals(method.getName())) {
                requestedServer.set((RegisteredServer) arguments[0]);
                return connectionRequest(result);
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static ConnectionRequestBuilder connectionRequest(
      ConnectionRequestBuilder.Result result) {
    return (ConnectionRequestBuilder)
        Proxy.newProxyInstance(
            ConnectionRequestBuilder.class.getClassLoader(),
            new Class<?>[] {ConnectionRequestBuilder.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "connect" -> CompletableFuture.completedFuture(result);
                  default -> defaultValue(method.getReturnType());
                });
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
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    if (type == char.class) {
      return '\0';
    }
    return null;
  }
}
