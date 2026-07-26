package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FallbackLobbyProviderTest {

    @Test
    void startsSLSLimboFirstAndPrefersReadyPrimary() {
        List<String> starts = new ArrayList<>();
        RegisteredServer primaryServer = server("lobby");
        RegisteredServer limboServer = server("sls-limbo");
        LobbyProvider primary = provider("primary", "lobby", primaryServer, starts);
        LobbyProvider limbo = provider(
                "limbo",
                "sls-limbo",
                limboServer,
                starts
        );
        FallbackLobbyProvider fallback = new FallbackLobbyProvider(
                proxy(),
                primary,
                limbo
        );

        fallback.start();

        assertEquals(List.of("limbo", "primary"), starts);
        assertSame(primaryServer, fallback.server().orElseThrow());
        assertSame(primaryServer, fallback.readyFuture().join());
        assertSame(
                limboServer,
                fallback.fallbackServer("lobby").orElseThrow()
        );
        assertEquals(Optional.empty(), fallback.primaryServer());
        assertEquals(Optional.empty(), fallback.fallbackServer("sls-limbo"));
        fallback.close();
    }

    @Test
    void usesSLSLimboWhilePrimaryHasNoReadyServer() {
        RegisteredServer limboServer = server("sls-limbo");
        LobbyProvider primary = provider(
                "primary",
                "lobby",
                null,
                new ArrayList<>()
        );
        LobbyProvider limbo = provider(
                "limbo",
                "sls-limbo",
                limboServer,
                new ArrayList<>()
        );
        FallbackLobbyProvider fallback = new FallbackLobbyProvider(
                proxy(),
                primary,
                limbo
        );

        fallback.start();

        assertSame(limboServer, fallback.server().orElseThrow());
        assertSame(limboServer, fallback.readyFuture().join());
        fallback.close();
    }

    private static LobbyProvider provider(
            String label,
            String serverName,
            RegisteredServer server,
            List<String> starts
    ) {
        CompletableFuture<RegisteredServer> ready = server == null
                ? new CompletableFuture<>()
                : CompletableFuture.completedFuture(server);
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
                return server == null ? LobbyStatus.STARTING : LobbyStatus.READY;
            }

            @Override
            public boolean isLobby(String candidate) {
                return serverName.equals(candidate);
            }

            @Override
            public CompletableFuture<Void> evacuate(String candidate) {
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

    private static RegisteredServer server(String name) {
        ServerInfo info = new ServerInfo(
                name,
                InetSocketAddress.createUnresolved("127.0.0.1", 25566)
        );
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, arguments) -> "getServerInfo".equals(method.getName())
                        ? info
                        : defaultValue(method.getReturnType())
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
