package net.slimelabs.slslite;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SLSLiteLobbyRoutingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void routesInitialJoinToExternalLobby() throws Exception {
        RegisteredServer lobby = server("lobby");
        PlayerChooseInitialServerEvent event =
                new PlayerChooseInitialServerEvent(player(), null);
        SLSLite plugin = plugin(provider(lobby, LobbyStatus.EXTERNAL, "lobby"));

        plugin.onPlayerChooseInitialServer(event);

        assertSame(lobby, event.getInitialServer().orElseThrow());
    }

    @Test
    void routesInitialJoinToManagedLobby() throws Exception {
        RegisteredServer lobby = server("lobby.abc001");
        PlayerChooseInitialServerEvent event =
                new PlayerChooseInitialServerEvent(player(), null);
        SLSLite plugin = plugin(provider(lobby, LobbyStatus.READY, "lobby.abc001"));

        plugin.onPlayerChooseInitialServer(event);

        assertSame(lobby, event.getInitialServer().orElseThrow());
    }

    @Test
    void redirectsBackendKickToActiveLobby() throws Exception {
        RegisteredServer lobby = server("lobby");
        RegisteredServer game = server("game.abc001");
        SLSLite plugin = plugin(provider(lobby, LobbyStatus.EXTERNAL, "lobby"));
        KickedFromServerEvent event = kickEvent(game);

        plugin.onKickedFromServer(event);

        KickedFromServerEvent.RedirectPlayer redirect = assertInstanceOf(
                KickedFromServerEvent.RedirectPlayer.class,
                event.getResult()
        );
        assertSame(lobby, redirect.getServer());
    }

    @Test
    void disconnectsWhenActiveLobbyKicksPlayer() throws Exception {
        RegisteredServer lobby = server("lobby.abc001");
        SLSLite plugin = plugin(provider(lobby, LobbyStatus.READY, "lobby.abc001"));
        KickedFromServerEvent event = kickEvent(lobby);

        plugin.onKickedFromServer(event);

        assertInstanceOf(
                KickedFromServerEvent.DisconnectPlayer.class,
                event.getResult()
        );
    }

    @Test
    void disconnectsInitialJoinWhileManagedLobbyIsRecovering() throws Exception {
        AtomicReference<Component> disconnectReason = new AtomicReference<>();
        SLSLite plugin = plugin(
                provider(null, LobbyStatus.RECOVERING, "lobby.abc001")
        );
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(
                player(disconnectReason),
                null
        );

        plugin.onPlayerChooseInitialServer(event);

        assertTrue(event.getInitialServer().isEmpty());
        assertEquals(
                Component.text("The lobby is restarting. Please reconnect shortly."),
                disconnectReason.get()
        );
    }

    private SLSLite plugin(LobbyProvider lobbyProvider) throws Exception {
        SLSLite plugin = new SLSLite(
                proxy(),
                LoggerFactory.getLogger(SLSLiteLobbyRoutingTest.class),
                temporaryDirectory
        );
        Field field = SLSLite.class.getDeclaredField("lobbyProvider");
        field.setAccessible(true);
        field.set(plugin, lobbyProvider);
        return plugin;
    }

    private static KickedFromServerEvent kickEvent(RegisteredServer source) {
        return new KickedFromServerEvent(
                player(),
                source,
                Component.text("Test kick"),
                false,
                KickedFromServerEvent.DisconnectPlayer.create(
                        Component.text("Original result")
                )
        );
    }

    private static LobbyProvider provider(
            RegisteredServer lobby,
            LobbyStatus status,
            String lobbyName
    ) {
        return new LobbyProvider() {
            @Override
            public void start() {
            }

            @Override
            public Optional<RegisteredServer> server() {
                return Optional.ofNullable(lobby);
            }

            @Override
            public CompletableFuture<RegisteredServer> readyFuture() {
                return lobby == null
                        ? CompletableFuture.failedFuture(
                                new IllegalStateException("Lobby unavailable")
                        )
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
            public CompletableFuture<Void> evacuate(String serverName) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
            }
        };
    }

    private static ProxyServer proxy() {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static Player player() {
        return player(new AtomicReference<>());
    }

    private static Player player(AtomicReference<Component> disconnectReason) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if ("disconnect".equals(method.getName())) {
                        disconnectReason.set((Component) arguments[0]);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RegisteredServer server(String name) {
        ServerInfo info = new ServerInfo(
                name,
                InetSocketAddress.createUnresolved("127.0.0.1", 25566)
        );
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, arguments) -> {
                    if ("getServerInfo".equals(method.getName())) {
                        return info;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
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
