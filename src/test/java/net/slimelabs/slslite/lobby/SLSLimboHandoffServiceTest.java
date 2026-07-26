package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SLSLimboHandoffServiceTest {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void transfersOnlyTrackedPlayerConnectedToSLSLimbo() {
        RegisteredServer limbo = server("sls-limbo");
        RegisteredServer primary = server("lobby");
        AtomicReference<Optional<ServerConnection>> current =
                new AtomicReference<>(Optional.of(connection(limbo)));
        AtomicInteger transfers = new AtomicInteger();
        Player player = player(current, transfers, primary);
        TestLobbyProvider lobbies = new TestLobbyProvider(limbo);
        SLSLimboHandoffService handoff = service(lobbies);

        handoff.awaitPrimary(player);
        handoff.connected(player, limbo);
        lobbies.publishPrimary(primary);

        assertEquals(1, transfers.get());
        assertEquals(0, handoff.waitingCount());
        handoff.close();
    }

    @Test
    void doesNotTransferUntrackedPlayerFromSLSLimbo() {
        RegisteredServer limbo = server("sls-limbo");
        RegisteredServer primary = server("lobby");
        AtomicReference<Optional<ServerConnection>> current =
                new AtomicReference<>(Optional.of(connection(limbo)));
        AtomicInteger transfers = new AtomicInteger();
        Player player = player(current, transfers, primary);
        TestLobbyProvider lobbies = new TestLobbyProvider(limbo);
        SLSLimboHandoffService handoff = service(lobbies);

        handoff.connected(player, limbo);
        lobbies.publishPrimary(primary);

        assertEquals(0, transfers.get());
        assertEquals(0, handoff.waitingCount());
        handoff.close();
    }

    @Test
    void doesNotMoveTrackedPlayerStillOnHealthyBackend() {
        RegisteredServer limbo = server("sls-limbo");
        RegisteredServer game = server("survival.abc123");
        RegisteredServer primary = server("lobby");
        AtomicReference<Optional<ServerConnection>> current =
                new AtomicReference<>(Optional.of(connection(game)));
        AtomicInteger transfers = new AtomicInteger();
        Player player = player(current, transfers, primary);
        TestLobbyProvider lobbies = new TestLobbyProvider(limbo);
        SLSLimboHandoffService handoff = service(lobbies);

        handoff.awaitPrimary(player);
        lobbies.publishPrimary(primary);

        assertEquals(0, transfers.get());
        assertEquals(1, handoff.waitingCount());
        handoff.disconnect(player.getUniqueId());
        assertEquals(0, handoff.waitingCount());
        handoff.close();
    }

    @Test
    void rateLimitsFailedHandoffsAndNotifiesPlayerOnce() {
        RegisteredServer limbo = server("sls-limbo");
        RegisteredServer primary = server("lobby");
        AtomicReference<Optional<ServerConnection>> current =
                new AtomicReference<>(Optional.of(connection(limbo)));
        AtomicInteger transfers = new AtomicInteger();
        AtomicInteger messages = new AtomicInteger();
        Player player = player(
                current,
                transfers,
                primary,
                ConnectionRequestBuilder.Status.SERVER_DISCONNECTED,
                messages
        );
        TestLobbyProvider lobbies = new TestLobbyProvider(limbo);
        SLSLimboHandoffService handoff = service(lobbies);

        handoff.awaitPrimary(player);
        handoff.connected(player, limbo);
        lobbies.publishPrimary(primary);
        lobbies.publishPrimary(primary);

        assertEquals(1, transfers.get());
        assertEquals(1, messages.get());
        assertEquals(1, handoff.waitingCount());
        handoff.close();
    }

    private SLSLimboHandoffService service(TestLobbyProvider lobbies) {
        return new SLSLimboHandoffService(
                lobbies,
                LoggerFactory.getLogger(SLSLimboHandoffServiceTest.class),
                scheduler
        );
    }

    private static Player player(
            AtomicReference<Optional<ServerConnection>> current,
            AtomicInteger transfers,
            RegisteredServer primary
    ) {
        return player(
                current,
                transfers,
                primary,
                ConnectionRequestBuilder.Status.SUCCESS,
                new AtomicInteger()
        );
    }

    private static Player player(
            AtomicReference<Optional<ServerConnection>> current,
            AtomicInteger transfers,
            RegisteredServer primary,
            ConnectionRequestBuilder.Status status,
            AtomicInteger messages
    ) {
        UUID playerId = UUID.randomUUID();
        ConnectionRequestBuilder.Result result =
                (ConnectionRequestBuilder.Result) Proxy.newProxyInstance(
                        ConnectionRequestBuilder.Result.class.getClassLoader(),
                        new Class<?>[]{ConnectionRequestBuilder.Result.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "getStatus" -> status;
                            case "getAttemptedConnection" -> primary;
                            case "getReasonComponent" -> Optional.empty();
                            default -> defaultValue(method.getReturnType());
                        }
                );
        ConnectionRequestBuilder builder =
                (ConnectionRequestBuilder) Proxy.newProxyInstance(
                        ConnectionRequestBuilder.class.getClassLoader(),
                        new Class<?>[]{ConnectionRequestBuilder.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "getServer" -> primary;
                            case "connect" -> {
                                transfers.incrementAndGet();
                                yield CompletableFuture.completedFuture(result);
                            }
                            default -> defaultValue(method.getReturnType());
                        }
                );
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getUsername" -> "Tester";
                    case "getCurrentServer" -> current.get();
                    case "createConnectionRequest" -> builder;
                    case "isActive" -> true;
                    case "sendActionBar" -> null;
                    case "sendMessage" -> {
                        messages.incrementAndGet();
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static ServerConnection connection(RegisteredServer server) {
        return (ServerConnection) Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(),
                new Class<?>[]{ServerConnection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getServerInfo" -> server.getServerInfo();
                    default -> defaultValue(method.getReturnType());
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

    private static final class TestLobbyProvider implements LobbyProvider {

        private final RegisteredServer limbo;
        private final List<Consumer<RegisteredServer>> listeners = new ArrayList<>();
        private RegisteredServer primary;

        private TestLobbyProvider(RegisteredServer limbo) {
            this.limbo = limbo;
        }

        void publishPrimary(RegisteredServer server) {
            primary = server;
            listeners.forEach(listener -> listener.accept(server));
        }

        @Override
        public void start() {
        }

        @Override
        public Optional<RegisteredServer> server() {
            return primary == null ? Optional.of(limbo) : Optional.of(primary);
        }

        @Override
        public CompletableFuture<RegisteredServer> readyFuture() {
            return CompletableFuture.completedFuture(limbo);
        }

        @Override
        public LobbyStatus status() {
            return primary == null ? LobbyStatus.STARTING : LobbyStatus.READY;
        }

        @Override
        public boolean isLobby(String serverName) {
            return "lobby".equals(serverName) || "sls-limbo".equals(serverName);
        }

        @Override
        public boolean isHoldingLobby(String serverName) {
            return "sls-limbo".equals(serverName);
        }

        @Override
        public Optional<RegisteredServer> primaryServer() {
            return Optional.ofNullable(primary);
        }

        @Override
        public void addPrimaryReadyListener(Consumer<RegisteredServer> listener) {
            listeners.add(listener);
        }

        @Override
        public CompletableFuture<Void> evacuate(String serverName) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }
}
