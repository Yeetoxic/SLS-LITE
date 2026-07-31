package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceState;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
            assertEquals(
                    Component.text("You have been dequeued.", NamedTextColor.RED),
                    fixture.actionBars().getLast()
            );
        }
    }

    @Test
    void queuedJoinDisplaysTheModernSlsLoadingAnimation() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            service.join(fixture.player(), "test", "smoke");
            awaitActionBars(fixture.actionBars(), 1);

            assertEquals(
                    Component.text("▇▆▅▃▂▂▂▂▂", NamedTextColor.GOLD),
                    fixture.actionBars().getFirst()
            );
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
            assertEquals(1, fixture.controller().stopCount());
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
            assertEquals(1, fixture.controller().stopCount());
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
    void readyInstanceIsPreferredOverAnInstanceStillStarting() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            ManagedInstance starting = fixture.controller().start("smoke");
            starting.lifecycle().transitionTo(InstanceState.STARTING);
            ManagedInstance ready = fixture.controller().start("smoke");
            ready.lifecycle().transitionTo(InstanceState.STARTING);
            ready.lifecycle().transitionTo(InstanceState.READY);
            ready.readyFuture().complete(ready);

            LocalJoinService.JoinAttempt attempt =
                    service.join(fixture.player(), "test", "smoke");

            assertEquals(ready.id(), attempt.instance().id());
            assertFalse(attempt.created());
            assertEquals(
                    ConnectionRequestBuilder.Status.SUCCESS,
                    attempt.connection().get(1, TimeUnit.SECONDS).getStatus()
            );
            assertEquals(
                    Component.text("Joining Smoke", NamedTextColor.GREEN),
                    fixture.actionBars().getLast()
            );
        }
    }

    @Test
    void lastCancellationImmediatelyStopsQueueOwnedInstance() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            service.join(fixture.player(), "test", "smoke");

            service.dequeue(fixture.playerId());

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

    @Test
    void forcedJoinPlayerBypassesBlueprintPlayerCapacity() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5), 1);
        try (LocalJoinService service = fixture.service()) {
            ManagedInstance instance = fixture.controller().start("smoke");
            instance.lifecycle().transitionTo(InstanceState.STARTING);
            instance.lifecycle().transitionTo(InstanceState.READY);
            instance.readyFuture().complete(instance);

            RegisteredServer registeredServer = registeredServer();
            ServerConnection connection = serverConnection(instance.id(), registeredServer);
            Player target = player(
                    UUID.randomUUID(),
                    "TargetPlayer",
                    registeredServer,
                    connectionResult(registeredServer),
                    Optional.of(connection)
            );

            InstanceOperationException full = assertThrows(
                    InstanceOperationException.class,
                    () -> service.joinPlayer(fixture.player(), target)
            );
            assertTrue(full.getMessage().contains("Instance is full"));

            LocalJoinService.DirectJoin forced =
                    service.joinPlayer(fixture.player(), target, true);

            assertEquals(instance.id(), forced.instance().id());
            assertEquals(
                    ConnectionRequestBuilder.Status.SUCCESS,
                    forced.connection().get(1, TimeUnit.SECONDS).getStatus()
            );
        }
    }

    @Test
    void drainingInstanceIsNotSelectedForANewJoin() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt first =
                    service.join(fixture.player(), "test", "smoke");
            ManagedInstance firstInstance = first.instance();
            firstInstance.lifecycle().transitionTo(InstanceState.STARTING);
            firstInstance.lifecycle().transitionTo(InstanceState.READY);
            firstInstance.readyFuture().complete(firstInstance);
            first.connection().get(5, TimeUnit.SECONDS);

            assertTrue(service.tryDrain(first.instance().id()));
            LocalJoinService.JoinAttempt second =
                    service.join(fixture.player(), "test", "smoke");

            assertTrue(second.created());
            assertNotEquals(first.instance().id(), second.instance().id());
        }
    }

    @Test
    void queuedPlayersScaleOnlyUpToBlueprintCapacity() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        Player secondPlayer = player(
                UUID.randomUUID(),
                "SecondPlayer",
                registeredServer(),
                connectionResult(registeredServer()),
                Optional.empty()
        );
        Player thirdPlayer = player(
                UUID.randomUUID(),
                "ThirdPlayer",
                registeredServer(),
                connectionResult(registeredServer()),
                Optional.empty()
        );

        try (LocalJoinService service = fixture.service()) {
            LocalJoinService.JoinAttempt first =
                    service.join(fixture.player(), "test", "smoke");
            LocalJoinService.JoinAttempt second =
                    service.join(secondPlayer, "test", "smoke");

            assertTrue(first.created());
            assertTrue(second.created());
            assertNotEquals(first.instance().id(), second.instance().id());
            InstanceOperationException exception = assertThrows(
                    InstanceOperationException.class,
                    () -> service.join(thirdPlayer, "test", "smoke")
            );
            assertTrue(exception.getMessage().contains(
                    "every blueprint has reached its instance limit"
            ));
            assertEquals(2, fixture.controller().getAll().size());
        }
    }

    @Test
    void gameTypePoolReusesAReadyInstanceFromAnotherBlueprint() throws Exception {
        PoolFixture fixture = poolFixture();
        try (LocalJoinService service = fixture.service()) {
            ManagedInstance variant = fixture.controller().start("variant");
            variant.lifecycle().transitionTo(InstanceState.STARTING);
            variant.lifecycle().transitionTo(InstanceState.READY);
            variant.readyFuture().complete(variant);

            LocalJoinService.JoinAttempt attempt = service.join(
                    fixture.player(),
                    "test",
                    "smoke"
            );

            assertFalse(attempt.created());
            assertEquals("variant", attempt.instance().blueprint().id());
            assertEquals(
                    ConnectionRequestBuilder.Status.SUCCESS,
                    attempt.connection().get(1, TimeUnit.SECONDS).getStatus()
            );
        }
    }

    @Test
    void gameTypePoolProvisionsAnotherBlueprintWhenRequestedCapIsReached()
            throws Exception {
        PoolFixture fixture = poolFixture();
        try (LocalJoinService service = fixture.service()) {
            ManagedInstance smoke = fixture.controller().start("smoke");
            assertTrue(service.tryDrain(smoke.id()));

            LocalJoinService.JoinAttempt attempt = service.join(
                    fixture.player(),
                    "test",
                    "smoke"
            );

            assertTrue(attempt.created());
            assertEquals("variant", attempt.instance().blueprint().id());
        }
    }

    private Fixture fixture(Duration timeout) throws Exception {
        return fixture(timeout, 0);
    }

    private Fixture fixture(Duration timeout, int connectedPlayers) throws Exception {
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
                    max_players: 1
                    max_instances: 2
                """);
        blueprints.reload();

        Blueprint blueprint = blueprints.get("test", "smoke").orElseThrow();
        FakeController controller = new FakeController(blueprint, temporaryDirectory);
        UUID playerId = UUID.randomUUID();
        RegisteredServer registeredServer = registeredServer(connectedPlayers);
        ConnectionRequestBuilder.Result result = connectionResult(registeredServer);
        List<Component> actionBars = new CopyOnWriteArrayList<>();
        Player player = player(
                playerId,
                "QueueTester",
                registeredServer,
                result,
                Optional.empty(),
                actionBars::add
        );
        ProxyServer proxy = proxy(player, registeredServer);
        return new Fixture(
                new LocalJoinService(proxy, blueprints, controller, timeout),
                controller,
                player,
                playerId,
                actionBars
        );
    }

    private PoolFixture poolFixture() throws Exception {
        Path blueprintDirectory = temporaryDirectory.resolve("pool-blueprints");
        Files.createDirectories(blueprintDirectory);
        for (String id : List.of("smoke", "variant")) {
            Files.writeString(blueprintDirectory.resolve(id + ".yml"), """
                    blueprint:
                      id: %s
                      name: %s
                      type: test
                    server:
                      software: paper
                      version: "1.21.11"
                      limits:
                        memory_limit: 512
                        max_players: 1
                        max_instances: 1
                    annotations:
                      vsls:
                        matchmaking:
                          gameType: party
                          maxPlayers: 1
                    """.formatted(id, id));
        }
        BlueprintRepository blueprints = new BlueprintRepository(blueprintDirectory);
        blueprints.reload();
        Map<String, Blueprint> definitions = blueprints.getAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Blueprint::id,
                        blueprint -> blueprint
                ));
        FakeController controller = new FakeController(
                definitions,
                temporaryDirectory
        );
        UUID playerId = UUID.randomUUID();
        RegisteredServer registered = registeredServer();
        Player player = player(
                playerId,
                "PoolTester",
                registered,
                connectionResult(registered),
                Optional.empty()
        );
        ProxyServer proxy = proxy(player, registered);
        return new PoolFixture(
                new LocalJoinService(
                        proxy,
                        blueprints,
                        controller,
                        Duration.ofSeconds(5)
                ),
                controller,
                player
        );
    }

    private static Player player(
            UUID playerId,
            String username,
            RegisteredServer registeredServer,
            ConnectionRequestBuilder.Result result,
            Optional<ServerConnection> currentServer
    ) {
        return player(
                playerId,
                username,
                registeredServer,
                result,
                currentServer,
                ignored -> {
                }
        );
    }

    private static Player player(
            UUID playerId,
            String username,
            RegisteredServer registeredServer,
            ConnectionRequestBuilder.Result result,
            Optional<ServerConnection> currentServer,
            Consumer<Component> actionBar
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
                    case "sendActionBar" -> {
                        actionBar.accept((Component) arguments[0]);
                        yield null;
                    }
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
        return registeredServer(0);
    }

    private static RegisteredServer registeredServer(int connectedPlayers) {
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayersConnected" ->
                            java.util.Collections.nCopies(connectedPlayers, null);
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

    private static void awaitActionBars(List<Component> messages, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (messages.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(messages.size() >= expected);
    }

    private record Fixture(
            LocalJoinService service,
            FakeController controller,
            Player player,
            UUID playerId,
            List<Component> actionBars
    ) {
    }

    private record PoolFixture(
            LocalJoinService service,
            FakeController controller,
            Player player
    ) {
    }

    private static final class FakeController implements ServerController {
        private final Map<String, Blueprint> blueprints;
        private final Path directory;
        private final Map<String, ManagedInstance> instances = new LinkedHashMap<>();
        private int stopCount;
        private int sequence;

        private FakeController(Blueprint blueprint, Path directory) {
            this(Map.of(blueprint.id(), blueprint), directory);
        }

        private FakeController(Map<String, Blueprint> blueprints, Path directory) {
            this.blueprints = Map.copyOf(blueprints);
            this.directory = directory;
        }

        @Override
        public ManagedInstance start(String blueprintId) {
            Blueprint blueprint = blueprints.get(blueprintId);
            if (blueprint == null) {
                throw new IllegalArgumentException("Unknown blueprint " + blueprintId);
            }
            String id = blueprintId + ".test" + String.format("%02d", ++sequence);
            InstanceLifecycle lifecycle = new InstanceLifecycle(id);
            lifecycle.transitionTo(InstanceState.PREPARING);
            ManagedInstance instance = new ManagedInstance(
                    id,
                    blueprint,
                    25600,
                    directory.resolve(id),
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
