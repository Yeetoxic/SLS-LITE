package net.slimelabs.slslite.instance;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalJoinServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsASecondQueueRequestForTheSamePlayer() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            service.join(fixture.player(), "test", "smoke");

            InstanceOperationException exception = assertThrows(
                    InstanceOperationException.class,
                    () -> service.join(fixture.player(), "test", "smoke")
            );

            assertTrue(exception.getMessage().contains("already queued"));
            assertEquals(1, service.queuedPlayers().size());
        }
    }

    @Test
    void dequeueCancelsAndRemovesTheRequest() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt attempt =
                    service.join(fixture.player(), "test", "smoke");

            assertTrue(service.dequeue(fixture.playerId()).isPresent());
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attempt.connection().get(1, TimeUnit.SECONDS)
            );

            assertInstanceOf(
                    LocalJoinService.QueueCancelledException.class,
                    failure.getCause()
            );
            assertTrue(service.queuedPlayers().isEmpty());
        }
    }

    @Test
    void disconnectCancelsAndRemovesTheRequest() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt attempt =
                    service.join(fixture.player(), "test", "smoke");

            service.disconnect(fixture.playerId());

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attempt.connection().get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(
                    LocalJoinService.QueueCancelledException.class,
                    failure.getCause()
            );
            assertTrue(service.queuedPlayers().isEmpty());
        }
    }

    @Test
    void queueTimesOutAndCleansItsEntry() throws Exception {
        Fixture fixture = fixture(Duration.ofMillis(25));
        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt attempt =
                    service.join(fixture.player(), "test", "smoke");

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attempt.connection().get(1, TimeUnit.SECONDS)
            );

            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(service.queuedPlayers().isEmpty());
        }
    }

    @Test
    void readinessFailureFailsAndCleansTheQueue() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt attempt =
                    service.join(fixture.player(), "test", "smoke");

            fixture.controller().instance().readyFuture()
                    .completeExceptionally(new IllegalStateException("startup failed"));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attempt.connection().get(1, TimeUnit.SECONDS)
            );
            assertEquals("startup failed", failure.getCause().getMessage());
            assertTrue(service.queuedPlayers().isEmpty());
        }
    }

    @Test
    void readyInstanceConnectsPlayerAndCompletesQueue() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt attempt =
                    service.join(fixture.player(), "test", "smoke");
            ManagedInstance instance = fixture.controller().instance();
            instance.lifecycle().transitionTo(InstanceState.STARTING);
            instance.lifecycle().transitionTo(InstanceState.READY);

            instance.readyFuture().complete(instance);

            ConnectionRequestBuilder.Result result =
                    attempt.connection().get(1, TimeUnit.SECONDS);
            assertEquals(ConnectionRequestBuilder.Status.SUCCESS, result.getStatus());
            assertTrue(service.queuedPlayers().isEmpty());
            assertEquals(0, fixture.controller().stopCount());
        }
    }

    @Test
    void lastCancellationStopsQueueOwnedInstanceAfterReadiness() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            service.join(fixture.player(), "test", "smoke");
            ManagedInstance instance = fixture.controller().instance();

            service.dequeue(fixture.playerId());
            instance.lifecycle().transitionTo(InstanceState.STARTING);
            instance.lifecycle().transitionTo(InstanceState.READY);
            instance.readyFuture().complete(instance);

            assertEquals(1, fixture.controller().stopCount());
        }
    }

    @Test
    void joinPlayerConnectsToTargetsExactManagedInstance() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt attempt =
                    service.join(fixture.player(), "test", "smoke");
            ManagedInstance instance = fixture.controller().instance();
            instance.lifecycle().transitionTo(InstanceState.STARTING);
            instance.lifecycle().transitionTo(InstanceState.READY);
            instance.readyFuture().complete(instance);
            attempt.connection().get(1, TimeUnit.SECONDS);

            RegisteredServer registeredServer = registeredServer();
            ServerConnection connection = serverConnection(instance.id(), registeredServer);
            Player target = player(
                    UUID.randomUUID(),
                    "TargetPlayer",
                    registeredServer,
                    connectionResult(registeredServer),
                    Optional.of(connection)
            );

            LocalJoinService.DirectJoin directJoin =
                    service.joinPlayer(fixture.player(), target);

            assertEquals(instance.id(), directJoin.instance().id());
            assertEquals(
                    ConnectionRequestBuilder.Status.SUCCESS,
                    directJoin.connection().get(1, TimeUnit.SECONDS).getStatus()
            );
        }
    }

    private Fixture fixture(Duration timeout) throws Exception {
        Path blueprintDirectory = temporaryDirectory.resolve("blueprints");
        BlueprintRepository blueprints = new BlueprintRepository(blueprintDirectory);
        Files.createDirectories(blueprintDirectory);
        Files.writeString(blueprintDirectory.resolve("smoke.yml"), """
                blueprint:
                  id: smoke
                  name: Smoke
                  type: test
                server:
                  software: paper
                  version: "26.1"
                  limits:
                    memory_limit: 512
                """);
        blueprints.reload();

        Blueprint blueprint = blueprints.get("test", "smoke").orElseThrow();
        FakeController controller = new FakeController(blueprint, temporaryDirectory);
        UUID playerId = UUID.randomUUID();
        RegisteredServer registeredServer = registeredServer();
        ConnectionRequestBuilder.Result result = connectionResult(registeredServer);
        Player player = player(
                playerId,
                "QueueTester",
                registeredServer,
                result,
                Optional.empty()
        );
        ProxyServer proxy = proxy(player, registeredServer);
        return new Fixture(
                new LocalJoinService(proxy, blueprints, controller, timeout),
                controller,
                player,
                playerId
        );
    }

    private static Player player(
            UUID playerId,
            String username,
            RegisteredServer registeredServer,
            ConnectionRequestBuilder.Result result,
            Optional<ServerConnection> currentServer
    ) {
        ConnectionRequestBuilder builder = (ConnectionRequestBuilder) Proxy.newProxyInstance(
                ConnectionRequestBuilder.class.getClassLoader(),
                new Class<?>[]{ConnectionRequestBuilder.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServer" -> registeredServer;
                    case "connect" -> CompletableFuture.completedFuture(result);
                    case "connectWithIndication" -> CompletableFuture.completedFuture(true);
                    default -> defaultValue(method.getReturnType());
                }
        );
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getUsername" -> username;
                    case "isActive" -> true;
                    case "getCurrentServer" -> currentServer;
                    case "createConnectionRequest" -> builder;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static ServerConnection serverConnection(
            String instanceId,
            RegisteredServer registeredServer
    ) {
        ServerInfo serverInfo = new ServerInfo(
                instanceId,
                new InetSocketAddress("127.0.0.1", 25600)
        );
        return (ServerConnection) Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(),
                new Class<?>[]{ServerConnection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServerInfo" -> serverInfo;
                    case "getServer" -> registeredServer;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static ProxyServer proxy(Player player, RegisteredServer registeredServer) {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayer" -> Optional.of(player);
                    case "getServer" -> Optional.of(registeredServer);
                    case "getAllPlayers" -> List.of(player);
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static RegisteredServer registeredServer() {
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayersConnected" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static ConnectionRequestBuilder.Result connectionResult(
            RegisteredServer registeredServer
    ) {
        return (ConnectionRequestBuilder.Result) Proxy.newProxyInstance(
                ConnectionRequestBuilder.Result.class.getClassLoader(),
                new Class<?>[]{ConnectionRequestBuilder.Result.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStatus" -> ConnectionRequestBuilder.Status.SUCCESS;
                    case "getReasonComponent" -> Optional.empty();
                    case "getAttemptedConnection" -> registeredServer;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private record Fixture(
            LocalJoinService service,
            FakeController controller,
            Player player,
            UUID playerId
    ) {
    }

    private static final class FakeController implements ServerController {
        private final Blueprint blueprint;
        private final Path directory;
        private final Map<String, ManagedInstance> instances = new LinkedHashMap<>();
        private int stopCount;

        private FakeController(Blueprint blueprint, Path directory) {
            this.blueprint = blueprint;
            this.directory = directory;
        }

        @Override
        public ManagedInstance start(String blueprintId) {
            InstanceLifecycle lifecycle = new InstanceLifecycle("smoke.test01");
            lifecycle.transitionTo(InstanceState.PREPARING);
            ManagedInstance instance = new ManagedInstance(
                    "smoke.test01",
                    blueprint,
                    25600,
                    directory.resolve("smoke.test01"),
                    lifecycle
            );
            instances.put(instance.id(), instance);
            return instance;
        }

        @Override
        public Collection<ManagedInstance> getAll() {
            return List.copyOf(instances.values());
        }

        @Override
        public ManagedInstance get(String instanceId) throws InstanceOperationException {
            ManagedInstance instance = instances.get(instanceId);
            if (instance == null) {
                throw new InstanceOperationException("Unknown instance");
            }
            return instance;
        }

        @Override
        public CompletableFuture<Integer> stop(String instanceId) {
            stopCount++;
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public void shutdown(Duration timeout) {
        }

        private ManagedInstance instance() {
            return instances.values().iterator().next();
        }

        private int stopCount() {
            return stopCount;
        }
    }
}
