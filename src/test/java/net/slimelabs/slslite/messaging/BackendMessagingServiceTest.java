package net.slimelabs.slslite.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.slimelabs.slslite.config.BackendMessageAction;
import net.slimelabs.slslite.config.BackendMessageSourceConfig;
import net.slimelabs.slslite.config.BackendMessagingConfig;
import net.slimelabs.slslite.log.SLSDetailLog;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class BackendMessagingServiceTest {

  @Test
  void acceptsExactAuthorizedMatchmakingAsCarrierPlayerAndHandlesPayload() {
    Fixture fixture = fixture(matchmakingConfig(exactSource()), "lobby", Optional.empty());
    UUID request = UUID.randomUUID();
    PluginMessageEvent event =
        fixture.event(BackendMessageProtocol.encodeMatchmake(request, "minigames", "arena"));

    fixture.service.handle(event);

    assertFalse(event.getResult().isAllowed());
    assertEquals(1, fixture.matchmakingCalls.get());
    assertSame(fixture.player, fixture.dispatchedPlayer.get());
    assertEquals("minigames/arena", fixture.dispatchedValue.get());
  }

  @Test
  void acceptsAuthorizedManagedBlueprintSource() {
    BackendMessageSourceConfig managed =
        new BackendMessageSourceConfig(
            "managed-lobby", "", "lobby/main", Set.of(BackendMessageAction.MATCHMAKE), List.of());
    Fixture fixture =
        fixture(matchmakingConfig(managed), "lobby.abc123", Optional.of("lobby/main"));

    fixture.service.handle(
        fixture.event(
            BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena")));

    assertEquals(1, fixture.matchmakingCalls.get());
  }

  @Test
  void executesAllowlistedCommandAsCarrierPlayer() {
    BackendMessageSourceConfig source =
        new BackendMessageSourceConfig(
            "lobby", "lobby", "", Set.of(BackendMessageAction.COMMAND), List.of("sls join"));
    BackendMessagingConfig config = new BackendMessagingConfig(true, true, 10, 10, List.of(source));
    Fixture fixture = fixture(config, "lobby", Optional.empty());

    fixture.service.handle(
        fixture.event(
            BackendMessageProtocol.encodeCommand(UUID.randomUUID(), "/sls join minigames arena")));

    assertEquals(1, fixture.commandCalls.get());
    assertSame(fixture.player, fixture.dispatchedPlayer.get());
    assertEquals("sls join minigames arena", fixture.dispatchedValue.get());
  }

  @Test
  void rejectsCommandOutsideAllowlistedRoot() {
    BackendMessageSourceConfig source =
        new BackendMessageSourceConfig(
            "lobby", "lobby", "", Set.of(BackendMessageAction.COMMAND), List.of("sls join"));
    Fixture fixture =
        fixture(
            new BackendMessagingConfig(true, true, 10, 10, List.of(source)),
            "lobby",
            Optional.empty());

    fixture.service.handle(
        fixture.event(BackendMessageProtocol.encodeCommand(UUID.randomUUID(), "sls stop all")));

    assertEquals(0, fixture.commandCalls.get());
  }

  @Test
  void deduplicatesRequestIdsBeforeDispatch() {
    Fixture fixture = fixture(matchmakingConfig(exactSource()), "lobby", Optional.empty());
    byte[] payload =
        BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena");

    fixture.service.handle(fixture.event(payload));
    fixture.service.handle(fixture.event(payload));

    assertEquals(1, fixture.matchmakingCalls.get());
  }

  @Test
  void rateLimitsAuthorizedSourceAndPlayer() {
    Fixture fixture =
        fixture(
            new BackendMessagingConfig(true, false, 1, 60, List.of(exactSource())),
            "lobby",
            Optional.empty());

    fixture.service.handle(
        fixture.event(
            BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena")));
    fixture.service.handle(
        fixture.event(
            BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena")));

    assertEquals(1, fixture.matchmakingCalls.get());
  }

  @Test
  void rejectsMalformedUnauthorizedAndStaleCarrierRequests() {
    Fixture malformed = fixture(matchmakingConfig(exactSource()), "lobby", Optional.empty());
    malformed.service.handle(malformed.event(new byte[] {1}));
    assertEquals(0, malformed.matchmakingCalls.get());

    Fixture unauthorized = fixture(matchmakingConfig(exactSource()), "survival", Optional.empty());
    unauthorized.service.handle(
        unauthorized.event(
            BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena")));
    assertEquals(0, unauthorized.matchmakingCalls.get());

    Fixture stale = fixture(matchmakingConfig(exactSource()), "lobby", Optional.empty());
    stale.currentConnection.set(Optional.empty());
    stale.service.handle(
        stale.event(
            BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena")));
    assertEquals(0, stale.matchmakingCalls.get());
  }

  @Test
  void handlesClientOriginatedMatchingChannelWithoutExecutingIt() {
    Fixture fixture = fixture(matchmakingConfig(exactSource()), "lobby", Optional.empty());
    PluginMessageEvent event =
        new PluginMessageEvent(
            fixture.player,
            fixture.player,
            BackendMessagingService.CHANNEL,
            BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena"));

    fixture.service.handle(event);

    assertFalse(event.getResult().isAllowed());
    assertEquals(0, fixture.matchmakingCalls.get());
  }

  @Test
  void handlesMatchingChannelWhileFeatureIsDisabled() {
    Fixture fixture = fixture(BackendMessagingConfig.defaults(), "lobby", Optional.empty());
    PluginMessageEvent event =
        fixture.event(
            BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena"));

    fixture.service.handle(event);

    assertFalse(event.getResult().isAllowed());
    assertEquals(0, fixture.matchmakingCalls.get());
  }

  @Test
  void ignoresOtherChannelsAndUnregistersDedicatedChannelOnClose() {
    Fixture fixture = fixture(matchmakingConfig(exactSource()), "lobby", Optional.empty());
    PluginMessageEvent event =
        new PluginMessageEvent(
            fixture.connection,
            fixture.player,
            MinecraftChannelIdentifier.from("example:other"),
            new byte[] {1});

    fixture.service.handle(event);
    fixture.service.close();

    assertTrue(event.getResult().isAllowed());
    assertEquals(1, fixture.registrations.get());
    assertEquals(1, fixture.unregistrations.get());
  }

  private static BackendMessageSourceConfig exactSource() {
    return new BackendMessageSourceConfig(
        "lobby", "lobby", "", Set.of(BackendMessageAction.MATCHMAKE), List.of());
  }

  private static BackendMessagingConfig matchmakingConfig(BackendMessageSourceConfig source) {
    return new BackendMessagingConfig(true, false, 10, 10, List.of(source));
  }

  private static Fixture fixture(
      BackendMessagingConfig config, String serverName, Optional<String> managedBlueprint) {
    AtomicInteger registrations = new AtomicInteger();
    AtomicInteger unregistrations = new AtomicInteger();
    ChannelRegistrar registrar =
        (ChannelRegistrar)
            Proxy.newProxyInstance(
                ChannelRegistrar.class.getClassLoader(),
                new Class<?>[] {ChannelRegistrar.class},
                (proxy, method, arguments) -> {
                  if ("register".equals(method.getName())) {
                    registrations.incrementAndGet();
                  } else if ("unregister".equals(method.getName())) {
                    unregistrations.incrementAndGet();
                  }
                  return null;
                });
    ProxyServer proxy =
        (ProxyServer)
            Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class},
                (ignored, method, arguments) ->
                    "getChannelRegistrar".equals(method.getName())
                        ? registrar
                        : defaultValue(method.getReturnType()));
    AtomicReference<Optional<ServerConnection>> current = new AtomicReference<>();
    UUID playerId = UUID.randomUUID();
    Player player =
        (Player)
            Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (ignored, method, arguments) ->
                    switch (method.getName()) {
                      case "getUniqueId" -> playerId;
                      case "getUsername" -> "Tester";
                      case "getCurrentServer" -> current.get();
                      case "isActive" -> true;
                      case "sendMessage" -> null;
                      default -> defaultValue(method.getReturnType());
                    });
    ServerInfo serverInfo =
        new ServerInfo(serverName, InetSocketAddress.createUnresolved("127.0.0.1", 25566));
    RegisteredServer registered =
        (RegisteredServer)
            Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[] {RegisteredServer.class},
                (ignored, method, arguments) ->
                    "getServerInfo".equals(method.getName())
                        ? serverInfo
                        : defaultValue(method.getReturnType()));
    ServerConnection connection =
        (ServerConnection)
            Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(),
                new Class<?>[] {ServerConnection.class},
                (ignored, method, arguments) ->
                    switch (method.getName()) {
                      case "getPlayer" -> player;
                      case "getServer", "getPreviousServer" -> registered;
                      case "getServerInfo" -> serverInfo;
                      default -> defaultValue(method.getReturnType());
                    });
    current.set(Optional.of(connection));
    AtomicInteger matchmakingCalls = new AtomicInteger();
    AtomicInteger commandCalls = new AtomicInteger();
    AtomicReference<Player> dispatchedPlayer = new AtomicReference<>();
    AtomicReference<String> dispatchedValue = new AtomicReference<>();
    BackendMessagingService service =
        new BackendMessagingService(
            proxy,
            ignored -> managedBlueprint,
            config,
            LoggerFactory.getLogger(BackendMessagingServiceTest.class),
            SLSDetailLog.disabled(),
            (carrier, registry, target) -> {
              matchmakingCalls.incrementAndGet();
              dispatchedPlayer.set(carrier);
              dispatchedValue.set(registry + "/" + target);
              return "arena.abc123";
            },
            (carrier, command) -> {
              commandCalls.incrementAndGet();
              dispatchedPlayer.set(carrier);
              dispatchedValue.set(command);
              return CompletableFuture.completedFuture(true);
            });
    service.start();
    return new Fixture(
        service,
        player,
        connection,
        current,
        matchmakingCalls,
        commandCalls,
        dispatchedPlayer,
        dispatchedValue,
        registrations,
        unregistrations);
  }

  private record Fixture(
      BackendMessagingService service,
      Player player,
      ServerConnection connection,
      AtomicReference<Optional<ServerConnection>> currentConnection,
      AtomicInteger matchmakingCalls,
      AtomicInteger commandCalls,
      AtomicReference<Player> dispatchedPlayer,
      AtomicReference<String> dispatchedValue,
      AtomicInteger registrations,
      AtomicInteger unregistrations) {

    private PluginMessageEvent event(byte[] payload) {
      return new PluginMessageEvent(connection, player, BackendMessagingService.CHANNEL, payload);
    }
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
    return '\0';
  }
}
