package net.slimelabs.slslite.instance;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.LobbyConfig;
import net.slimelabs.slslite.config.LobbyMode;
import net.slimelabs.slslite.lobby.LocalLobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLobbyProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void provisionsAndPublishesManagedLobbyAfterReadiness() throws Exception {
        BlueprintRepository blueprints = blueprints();
        FakeController controller = new FakeController(
                blueprints.get("lobby", "lobby").orElseThrow(),
                temporaryDirectory
        );
        Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        ProxyServer proxy = proxy(servers);
        LocalLobbyProvider provider = new LocalLobbyProvider(
                proxy,
                blueprints,
                controller,
                new LobbyConfig(LobbyMode.MANAGED, "lobby", "lobby"),
                LoggerFactory.getLogger(LocalLobbyProviderTest.class)
        );

        provider.start();
        ManagedInstance instance = controller.instance();
        RegisteredServer registered = registeredServer(List.of());
        servers.put(instance.id(), registered);
        instance.lifecycle().transitionTo(InstanceState.STARTING);
        instance.lifecycle().transitionTo(InstanceState.READY);
        instance.readyFuture().complete(instance);

        assertSame(registered, provider.readyFuture().get(1, TimeUnit.SECONDS));
        assertSame(registered, provider.server().orElseThrow());
        assertTrue(provider.isLobby(instance.id()));
        assertEquals(LobbyStatus.READY, provider.status());
        provider.close();
    }

    @Test
    void evacuatesExternalBackendPlayersToConfiguredLobby() throws Exception {
        BlueprintRepository blueprints = blueprints();
        AtomicReference<RegisteredServer> requestedServer = new AtomicReference<>();
        RegisteredServer lobby = registeredServer(List.of());
        Player player = player(requestedServer);
        RegisteredServer game = registeredServer(List.of(player));
        Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        servers.put("lobby", lobby);
        servers.put("game.test01", game);
        LocalLobbyProvider provider = new LocalLobbyProvider(
                proxy(servers),
                blueprints,
                new FakeController(
                        blueprints.get("lobby", "lobby").orElseThrow(),
                        temporaryDirectory
                ),
                new LobbyConfig(LobbyMode.EXTERNAL, "lobby", "lobby"),
                LoggerFactory.getLogger(LocalLobbyProviderTest.class)
        );

        provider.start();
        provider.evacuate("game.test01").get(1, TimeUnit.SECONDS);

        assertSame(lobby, provider.readyFuture().get(1, TimeUnit.SECONDS));
        assertSame(lobby, requestedServer.get());
        provider.close();
    }

    @Test
    void evacuatesBackendPlayersToManagedLobby() throws Exception {
        BlueprintRepository blueprints = blueprints();
        FakeController controller = new FakeController(
                blueprints.get("lobby", "lobby").orElseThrow(),
                temporaryDirectory
        );
        AtomicReference<RegisteredServer> requestedServer = new AtomicReference<>();
        Player player = player(requestedServer);
        RegisteredServer game = registeredServer(List.of(player));
        Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        servers.put("game.test01", game);
        LocalLobbyProvider provider = new LocalLobbyProvider(
                proxy(servers),
                blueprints,
                controller,
                new LobbyConfig(LobbyMode.MANAGED, "lobby", "lobby"),
                LoggerFactory.getLogger(LocalLobbyProviderTest.class)
        );

        provider.start();
        ManagedInstance instance = controller.instance();
        RegisteredServer lobby = registeredServer(List.of());
        publishReady(instance, lobby, servers);
        provider.evacuate("game.test01").get(1, TimeUnit.SECONDS);

        assertSame(lobby, requestedServer.get());
        provider.close();
    }

    @Test
    void restartsManagedLobbyAfterUnexpectedExit() throws Exception {
        BlueprintRepository blueprints = blueprints();
        FakeController controller = new FakeController(
                blueprints.get("lobby", "lobby").orElseThrow(),
                temporaryDirectory
        );
        Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        LocalLobbyProvider provider = new LocalLobbyProvider(
                proxy(servers),
                blueprints,
                controller,
                recoveryConfig(2),
                LoggerFactory.getLogger(LocalLobbyProviderTest.class)
        );

        provider.start();
        ManagedInstance first = controller.instance();
        RegisteredServer firstServer = registeredServer(List.of());
        publishReady(first, firstServer, servers);
        assertEquals(LobbyStatus.READY, provider.status());

        first.stoppedFuture().complete(137);
        assertEquals(LobbyStatus.RECOVERING, provider.status());
        awaitStarts(controller, 2);

        ManagedInstance second = controller.instance();
        RegisteredServer secondServer = registeredServer(List.of());
        publishReady(second, secondServer, servers);

        assertEquals(LobbyStatus.READY, provider.status());
        assertSame(secondServer, provider.server().orElseThrow());
        assertTrue(provider.isLobby(second.id()));
        provider.close();
    }

    @Test
    void stopsRetryingAfterRecoveryBudgetIsExhausted() throws Exception {
        BlueprintRepository blueprints = blueprints();
        FakeController controller = new FakeController(
                blueprints.get("lobby", "lobby").orElseThrow(),
                temporaryDirectory
        );
        LocalLobbyProvider provider = new LocalLobbyProvider(
                proxy(new LinkedHashMap<>()),
                blueprints,
                controller,
                recoveryConfig(1),
                LoggerFactory.getLogger(LocalLobbyProviderTest.class)
        );

        provider.start();
        controller.instance().readyFuture().completeExceptionally(
                new IllegalStateException("first failure")
        );
        awaitStarts(controller, 2);
        controller.instance().readyFuture().completeExceptionally(
                new IllegalStateException("second failure")
        );

        assertEquals(LobbyStatus.OFFLINE, provider.status());
        assertTrue(provider.readyFuture().isCompletedExceptionally());
        provider.close();
    }

    @Test
    void intentionalCloseSuppressesRecovery() throws Exception {
        BlueprintRepository blueprints = blueprints();
        FakeController controller = new FakeController(
                blueprints.get("lobby", "lobby").orElseThrow(),
                temporaryDirectory
        );
        Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        LocalLobbyProvider provider = new LocalLobbyProvider(
                proxy(servers),
                blueprints,
                controller,
                recoveryConfig(2),
                LoggerFactory.getLogger(LocalLobbyProviderTest.class)
        );

        provider.start();
        ManagedInstance instance = controller.instance();
        publishReady(instance, registeredServer(List.of()), servers);
        provider.close();
        instance.stoppedFuture().complete(0);
        Thread.sleep(1200);

        assertEquals(1, controller.starts());
        assertEquals(LobbyStatus.SHUTTING_DOWN, provider.status());
    }

    @Test
    void stableRecoveryResetsTheRetryBudget() throws Exception {
        BlueprintRepository blueprints = blueprints();
        FakeController controller = new FakeController(
                blueprints.get("lobby", "lobby").orElseThrow(),
                temporaryDirectory
        );
        Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        LocalLobbyProvider provider = new LocalLobbyProvider(
                proxy(servers),
                blueprints,
                controller,
                recoveryConfig(1),
                LoggerFactory.getLogger(LocalLobbyProviderTest.class)
        );

        provider.start();
        ManagedInstance first = controller.instance();
        publishReady(first, registeredServer(List.of()), servers);
        first.stoppedFuture().complete(137);
        awaitStarts(controller, 2);

        ManagedInstance second = controller.instance();
        publishReady(second, registeredServer(List.of()), servers);
        Thread.sleep(1200);
        second.stoppedFuture().complete(137);
        assertEquals(LobbyStatus.RECOVERING, provider.status());
        awaitStarts(controller, 3);

        provider.close();
    }

    private BlueprintRepository blueprints() throws Exception {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("blueprints"));
        Files.writeString(directory.resolve("lobby.yml"), """
                blueprint:
                  id: lobby
                  name: Lobby
                  type: lobby
                server:
                  software: paper
                  version: "26.1"
                  limits:
                    memory_limit: 512
                """);
        BlueprintRepository repository = new BlueprintRepository(directory);
        repository.reload();
        return repository;
    }

    private static ProxyServer proxy(Map<String, RegisteredServer> servers) {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServer" -> Optional.ofNullable(servers.get(arguments[0]));
                    case "getAllServers" -> List.copyOf(servers.values());
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Player player(AtomicReference<RegisteredServer> requestedServer) {
        ConnectionRequestBuilder.Result result =
                (ConnectionRequestBuilder.Result) Proxy.newProxyInstance(
                        ConnectionRequestBuilder.Result.class.getClassLoader(),
                        new Class<?>[]{ConnectionRequestBuilder.Result.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "getStatus" -> ConnectionRequestBuilder.Status.SUCCESS;
                            case "getReasonComponent" -> Optional.empty();
                            default -> defaultValue(method.getReturnType());
                        }
                );
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if ("getUsername".equals(method.getName())) {
                        return "LobbyTester";
                    }
                    if ("createConnectionRequest".equals(method.getName())) {
                        requestedServer.set((RegisteredServer) arguments[0]);
                        return connectionRequest(result);
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static ConnectionRequestBuilder connectionRequest(
            ConnectionRequestBuilder.Result result
    ) {
        return (ConnectionRequestBuilder) Proxy.newProxyInstance(
                ConnectionRequestBuilder.class.getClassLoader(),
                new Class<?>[]{ConnectionRequestBuilder.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "connect" -> CompletableFuture.completedFuture(result);
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static RegisteredServer registeredServer(Collection<Player> players) {
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayersConnected" -> players;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static LobbyConfig recoveryConfig(int attempts) {
        return new LobbyConfig(
                LobbyMode.MANAGED,
                "lobby",
                "lobby",
                attempts,
                1,
                1,
                1
        );
    }

    private static void publishReady(
            ManagedInstance instance,
            RegisteredServer server,
            Map<String, RegisteredServer> servers
    ) {
        servers.put(instance.id(), server);
        instance.lifecycle().transitionTo(InstanceState.STARTING);
        instance.lifecycle().transitionTo(InstanceState.READY);
        instance.readyFuture().complete(instance);
    }

    private static void awaitStarts(FakeController controller, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (controller.starts() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, controller.starts());
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

    private static final class FakeController implements ServerController {
        private final Blueprint blueprint;
        private final Path directory;
        private ManagedInstance instance;
        private int starts;

        private FakeController(Blueprint blueprint, Path directory) {
            this.blueprint = blueprint;
            this.directory = directory;
        }

        @Override
        public ManagedInstance start(String blueprintId) {
            String id = "lobby.abc00" + (++starts);
            InstanceLifecycle lifecycle = new InstanceLifecycle(id);
            lifecycle.transitionTo(InstanceState.PREPARING);
            instance = new ManagedInstance(
                    id,
                    blueprint,
                    25600,
                    directory.resolve("lobby.test01"),
                    lifecycle
            );
            return instance;
        }

        @Override
        public Collection<ManagedInstance> getAll() {
            return instance == null ? List.of() : List.of(instance);
        }

        @Override
        public ManagedInstance get(String instanceId) throws InstanceOperationException {
            if (instance == null || !instance.id().equals(instanceId)) {
                throw new InstanceOperationException("Unknown instance");
            }
            return instance;
        }

        @Override
        public CompletableFuture<Integer> stop(String instanceId) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public void shutdown(Duration timeout) {
        }

        private ManagedInstance instance() {
            return instance;
        }

        private int starts() {
            return starts;
        }
    }
}
