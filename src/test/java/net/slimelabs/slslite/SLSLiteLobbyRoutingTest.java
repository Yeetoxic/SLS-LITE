package net.slimelabs.slslite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import net.slimelabs.slslite.lobby.SLSLimboHandoffService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SLSLiteLobbyRoutingTest {

  @TempDir Path temporaryDirectory;

  @Test
  void routesInitialJoinToExternalLobby() throws Exception {
    RegisteredServer lobby = server("lobby");
    PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(player(), null);
    SLSLite plugin = plugin(provider(lobby, LobbyStatus.EXTERNAL, "lobby"));

    plugin.onPlayerChooseInitialServer(event);

    assertSame(lobby, event.getInitialServer().orElseThrow());
  }

  @Test
  void routesInitialJoinToManagedLobby() throws Exception {
    RegisteredServer lobby = server("lobby.abc001");
    PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(player(), null);
    SLSLite plugin = plugin(provider(lobby, LobbyStatus.READY, "lobby.abc001"));

    plugin.onPlayerChooseInitialServer(event);

    assertSame(lobby, event.getInitialServer().orElseThrow());
  }

  @Test
  void preservesVelocitySelectedInitialServer() throws Exception {
    RegisteredServer forcedHost = server("forced-host");
    RegisteredServer configuredLobby = server("lobby");
    PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(player(), forcedHost);
    SLSLite plugin = plugin(provider(configuredLobby, LobbyStatus.EXTERNAL, "lobby"));

    plugin.onPlayerChooseInitialServer(event);

    assertSame(forcedHost, event.getInitialServer().orElseThrow());
  }

  @Test
  void velocityModeUsesLimboOnlyWhenVelocityHasNoInitialRoute() throws Exception {
    RegisteredServer limbo = server("sls-limbo");
    PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(player(), null);
    SLSLite plugin = plugin(provider(limbo, LobbyStatus.READY, "sls-limbo", true));

    plugin.onPlayerChooseInitialServer(event);

    assertSame(limbo, event.getInitialServer().orElseThrow());
  }

  @Test
  void velocityModeRescuesFinalDisconnectToSLSLimbo() throws Exception {
    RegisteredServer limbo = server("sls-limbo");
    RegisteredServer game = server("game");
    KickedFromServerEvent event = connectionKickEvent(game);
    SLSLite plugin = plugin(provider(limbo, LobbyStatus.READY, "sls-limbo", true));

    plugin.onKickedFromServer(event);

    KickedFromServerEvent.RedirectPlayer redirect =
        assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    assertSame(limbo, redirect.getServer());
  }

  @Test
  void velocityModeRescuesConnectedBackendOutageToSLSLimbo() throws Exception {
    RegisteredServer limbo = server("sls-limbo");
    KickedFromServerEvent event = kickEvent(server("lobby"));
    SLSLite plugin = plugin(provider(limbo, LobbyStatus.READY, "sls-limbo", true));

    plugin.onKickedFromServer(event);

    KickedFromServerEvent.RedirectPlayer redirect =
        assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    assertSame(limbo, redirect.getServer());
  }

  @Test
  void velocityModePreservesExistingFallbackRedirect() throws Exception {
    RegisteredServer limbo = server("sls-limbo");
    RegisteredServer velocityFallback = server("velocity-fallback");
    KickedFromServerEvent.RedirectPlayer original =
        KickedFromServerEvent.RedirectPlayer.create(
            velocityFallback, Component.text("Velocity fallback"));
    KickedFromServerEvent event = kickEvent(server("game"), original);
    SLSLite plugin = plugin(provider(limbo, LobbyStatus.READY, "sls-limbo", true));

    plugin.onKickedFromServer(event);

    assertSame(original, event.getResult());
  }

  @Test
  void velocityModeDoesNotRedirectFailedSLSLimboToItself() throws Exception {
    RegisteredServer limbo = server("sls-limbo");
    KickedFromServerEvent event = kickEvent(limbo);
    KickedFromServerEvent.ServerKickResult original = event.getResult();
    SLSLite plugin = plugin(provider(limbo, LobbyStatus.READY, "sls-limbo", true));

    plugin.onKickedFromServer(event);

    assertSame(original, event.getResult());
  }

  @Test
  void redirectsBackendKickToActiveLobby() throws Exception {
    RegisteredServer lobby = server("lobby");
    RegisteredServer game = server("game.abc001");
    SLSLite plugin = plugin(provider(lobby, LobbyStatus.EXTERNAL, "lobby"));
    KickedFromServerEvent event = kickEvent(game);

    plugin.onKickedFromServer(event);

    KickedFromServerEvent.RedirectPlayer redirect =
        assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    assertSame(lobby, redirect.getServer());
  }

  @Test
  void disconnectsWhenActiveLobbyKicksPlayer() throws Exception {
    RegisteredServer lobby = server("lobby.abc001");
    SLSLite plugin = plugin(provider(lobby, LobbyStatus.READY, "lobby.abc001"));
    KickedFromServerEvent event = kickEvent(lobby);

    plugin.onKickedFromServer(event);

    assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, event.getResult());
  }

  @Test
  void redirectsPrimaryLobbyKickToSLSLimbo() throws Exception {
    RegisteredServer primary = server("lobby");
    RegisteredServer limbo = server("sls-limbo");
    SLSLite plugin = plugin(providerWithFallback(primary, limbo, LobbyStatus.EXTERNAL));
    KickedFromServerEvent event = kickEvent(primary);

    plugin.onKickedFromServer(event);

    KickedFromServerEvent.RedirectPlayer redirect =
        assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    assertSame(limbo, redirect.getServer());
  }

  @Test
  void disconnectsInitialJoinWhileManagedLobbyIsRecovering() throws Exception {
    AtomicReference<Component> disconnectReason = new AtomicReference<>();
    SLSLite plugin = plugin(provider(null, LobbyStatus.RECOVERING, "lobby.abc001"));
    PlayerChooseInitialServerEvent event =
        new PlayerChooseInitialServerEvent(player(disconnectReason), null);

    plugin.onPlayerChooseInitialServer(event);

    assertTrue(event.getInitialServer().isEmpty());
    assertEquals(
        Component.text("The lobby is restarting. Please reconnect shortly."),
        disconnectReason.get());
  }

  @Test
  void disconnectsInitialJoinWithoutStoppingWhenAllLobbiesAreOffline() throws Exception {
    AtomicReference<Component> disconnectReason = new AtomicReference<>();
    SLSLite plugin = plugin(provider(null, LobbyStatus.OFFLINE, "lobby"));
    PlayerChooseInitialServerEvent event =
        new PlayerChooseInitialServerEvent(player(disconnectReason), null);

    plugin.onPlayerChooseInitialServer(event);

    assertTrue(event.getInitialServer().isEmpty());
    assertEquals(
        Component.text("The lobby is currently unavailable. Please try again later."),
        disconnectReason.get());
  }

  private SLSLite plugin(LobbyProvider lobbyProvider) throws Exception {
    SLSLite plugin =
        new SLSLite(
            proxy(), LoggerFactory.getLogger(SLSLiteLobbyRoutingTest.class), temporaryDirectory);
    Field field = SLSLite.class.getDeclaredField("lobbyProvider");
    field.setAccessible(true);
    field.set(plugin, lobbyProvider);
    Field handoffField = SLSLite.class.getDeclaredField("limboHandoff");
    handoffField.setAccessible(true);
    handoffField.set(
        plugin,
        new SLSLimboHandoffService(
            lobbyProvider, LoggerFactory.getLogger(SLSLiteLobbyRoutingTest.class)));
    return plugin;
  }

  private static KickedFromServerEvent kickEvent(RegisteredServer source) {
    return kickEvent(
        source,
        false,
        KickedFromServerEvent.DisconnectPlayer.create(Component.text("Original result")));
  }

  private static KickedFromServerEvent connectionKickEvent(RegisteredServer source) {
    return kickEvent(
        source,
        true,
        KickedFromServerEvent.DisconnectPlayer.create(Component.text("Original result")));
  }

  private static KickedFromServerEvent kickEvent(
      RegisteredServer source, KickedFromServerEvent.ServerKickResult result) {
    return kickEvent(source, false, result);
  }

  private static KickedFromServerEvent kickEvent(
      RegisteredServer source,
      boolean kickedDuringServerConnect,
      KickedFromServerEvent.ServerKickResult result) {
    return new KickedFromServerEvent(
        player(), source, Component.text("Test kick"), kickedDuringServerConnect, result);
  }

  private static LobbyProvider provider(
      RegisteredServer lobby, LobbyStatus status, String lobbyName) {
    return provider(lobby, status, lobbyName, false);
  }

  private static LobbyProvider provider(
      RegisteredServer lobby, LobbyStatus status, String lobbyName, boolean preservesVelocity) {
    return new LobbyProvider() {
      @Override
      public void start() {}

      @Override
      public Optional<RegisteredServer> server() {
        return Optional.ofNullable(lobby);
      }

      @Override
      public CompletableFuture<RegisteredServer> readyFuture() {
        return lobby == null
            ? CompletableFuture.failedFuture(new IllegalStateException("Lobby unavailable"))
            : CompletableFuture.completedFuture(lobby);
      }

      @Override
      public LobbyStatus status() {
        return status;
      }

      @Override
      public boolean isLobby(String serverName) {
        return lobbyName.equals(serverName);
      }

      @Override
      public boolean preservesVelocityRouting() {
        return preservesVelocity;
      }

      @Override
      public boolean isHoldingLobby(String serverName) {
        return "sls-limbo".equals(serverName);
      }

      @Override
      public CompletableFuture<Void> evacuate(String serverName) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void close() {}
    };
  }

  private static LobbyProvider providerWithFallback(
      RegisteredServer primary, RegisteredServer limbo, LobbyStatus status) {
    return new LobbyProvider() {
      @Override
      public void start() {}

      @Override
      public Optional<RegisteredServer> server() {
        return Optional.of(primary);
      }

      @Override
      public CompletableFuture<RegisteredServer> readyFuture() {
        return CompletableFuture.completedFuture(primary);
      }

      @Override
      public LobbyStatus status() {
        return status;
      }

      @Override
      public boolean isLobby(String serverName) {
        return "lobby".equals(serverName) || "sls-limbo".equals(serverName);
      }

      @Override
      public Optional<RegisteredServer> fallbackServer(String failedLobbyName) {
        return "lobby".equals(failedLobbyName) ? Optional.of(limbo) : Optional.empty();
      }

      @Override
      public CompletableFuture<Void> evacuate(String serverName) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void close() {}
    };
  }

  private static ProxyServer proxy() {
    return (ProxyServer)
        Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static Player player() {
    return player(new AtomicReference<>());
  }

  private static Player player(AtomicReference<Component> disconnectReason) {
    UUID playerId = UUID.randomUUID();
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> {
              if ("getUniqueId".equals(method.getName())) {
                return playerId;
              }
              if ("getUsername".equals(method.getName())) {
                return "Tester";
              }
              if ("disconnect".equals(method.getName())) {
                disconnectReason.set((Component) arguments[0]);
                return null;
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static RegisteredServer server(String name) {
    ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", 25566));
    return (RegisteredServer)
        Proxy.newProxyInstance(
            RegisteredServer.class.getClassLoader(),
            new Class<?>[] {RegisteredServer.class},
            (proxy, method, arguments) -> {
              if ("getServerInfo".equals(method.getName())) {
                return info;
              }
              return defaultValue(method.getReturnType());
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
