package net.slimelabs.slslite.instance;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleInstanceReaperTest {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private IdleInstanceReaper reaper;

    @AfterEach
    void closeReaper() {
        if (reaper != null) {
            reaper.close();
        } else {
            scheduler.shutdownNow();
        }
    }

    @Test
    void stopsAnEmptyEphemeralInstanceAfterItsDelay() {
        ManagedInstance instance = readyInstance("smoke.123456", Map.of(), false);
        FakeController controller = new FakeController(instance);
        FakeAdmissions admissions = new FakeAdmissions();
        MutableClock clock = new MutableClock();
        reaper = reaper(
                controller,
                admissions,
                neverLobby(),
                proxy(Map.of(instance.id(), new ArrayList<>())),
                clock,
                30
        );

        reaper.scanNow();
        clock.advance(Duration.ofSeconds(29));
        reaper.scanNow();
        assertTrue(controller.stopped.isEmpty());

        clock.advance(Duration.ofSeconds(1));
        reaper.scanNow();

        assertEquals(List.of(instance.id()), controller.stopped);
        assertTrue(admissions.draining.contains(instance.id()));
    }

    @Test
    void activityAndPendingJoinsResetOrBlockTheTimer() {
        ManagedInstance instance = readyInstance("smoke.123456", Map.of(), false);
        FakeController controller = new FakeController(instance);
        FakeAdmissions admissions = new FakeAdmissions();
        MutableClock clock = new MutableClock();
        List<Player> players = new ArrayList<>();
        reaper = reaper(
                controller,
                admissions,
                neverLobby(),
                proxy(Map.of(instance.id(), players)),
                clock,
                10
        );

        reaper.scanNow();
        clock.advance(Duration.ofSeconds(10));
        players.add(player());
        reaper.scanNow();
        players.clear();
        reaper.scanNow();
        clock.advance(Duration.ofSeconds(10));
        admissions.pending.add(instance.id());
        reaper.scanNow();

        assertTrue(controller.stopped.isEmpty());
        admissions.pending.clear();
        reaper.scanNow();
        clock.advance(Duration.ofSeconds(10));
        reaper.scanNow();
        assertEquals(List.of(instance.id()), controller.stopped);
    }

    @Test
    void excludesLobbyPersistentAndKeepAliveInstances() {
        ManagedInstance lobby = readyInstance("lobby.123456", Map.of(), false);
        ManagedInstance persistent = readyInstance("survival.123456", Map.of(), true);
        ManagedInstance priority = readyInstance(
                "event.123456",
                Map.of("sls-lite", Map.of("keep-alive", true)),
                false
        );
        FakeController controller = new FakeController(lobby, persistent, priority);
        MutableClock clock = new MutableClock();
        Map<String, List<Player>> players = Map.of(
                lobby.id(), List.of(),
                persistent.id(), List.of(),
                priority.id(), List.of()
        );
        reaper = reaper(
                controller,
                new FakeAdmissions(),
                lobbyNamed(lobby.id()),
                proxy(players),
                clock,
                1
        );

        reaper.scanNow();
        clock.advance(Duration.ofMinutes(1));
        reaper.scanNow();

        assertTrue(controller.stopped.isEmpty());
    }

    private IdleInstanceReaper reaper(
            ServerController controller,
            IdleAdmissionControl admissions,
            LobbyProvider lobby,
            ProxyServer proxy,
            Clock clock,
            int timeoutSeconds
    ) {
        return new IdleInstanceReaper(
                proxy,
                controller,
                admissions,
                lobby,
                timeoutSeconds,
                LoggerFactory.getLogger(IdleInstanceReaperTest.class),
                clock,
                scheduler
        );
    }

    private static ManagedInstance readyInstance(
            String id,
            Map<String, Object> annotations,
            boolean save
    ) {
        Blueprint blueprint = new Blueprint(
                id.substring(0, id.indexOf('.')),
                "Test",
                "test",
                "paper",
                "fixture",
                256,
                save,
                annotations
        );
        InstanceLifecycle lifecycle = new InstanceLifecycle(id);
        lifecycle.transitionTo(InstanceState.PREPARING);
        lifecycle.transitionTo(InstanceState.STARTING);
        lifecycle.transitionTo(InstanceState.READY);
        return new ManagedInstance(id, blueprint, 25570, Path.of("instances", id), lifecycle);
    }

    private static ProxyServer proxy(Map<String, ? extends Collection<Player>> players) {
        Map<String, RegisteredServer> servers = new HashMap<>();
        players.forEach((id, connected) -> servers.put(id, registeredServer(connected)));
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> {
                    if ("getServer".equals(method.getName())) {
                        return Optional.ofNullable(servers.get(arguments[0]));
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RegisteredServer registeredServer(Collection<Player> players) {
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, arguments) -> {
                    if ("getPlayersConnected".equals(method.getName())) {
                        return players;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static LobbyProvider neverLobby() {
        return lobbyNamed("");
    }

    private static LobbyProvider lobbyNamed(String lobbyId) {
        return new LobbyProvider() {
            @Override
            public void start() {
            }

            @Override
            public Optional<RegisteredServer> server() {
                return Optional.empty();
            }

            @Override
            public CompletableFuture<RegisteredServer> readyFuture() {
                return new CompletableFuture<>();
            }

            @Override
            public LobbyStatus status() {
                return LobbyStatus.READY;
            }

            @Override
            public boolean isLobby(String serverName) {
                return lobbyId.equals(serverName);
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

    private static final class FakeController implements ServerController {
        private final List<ManagedInstance> instances;
        private final List<String> stopped = new ArrayList<>();

        private FakeController(ManagedInstance... instances) {
            this.instances = List.of(instances);
        }

        @Override
        public ManagedInstance start(String blueprintId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<ManagedInstance> getAll() {
            return instances;
        }

        @Override
        public ManagedInstance get(String instanceId) throws InstanceOperationException {
            return instances.stream()
                    .filter(instance -> instance.id().equals(instanceId))
                    .findFirst()
                    .orElseThrow(() -> new InstanceOperationException("missing"));
        }

        @Override
        public CompletableFuture<Integer> stop(String instanceId) {
            stopped.add(instanceId);
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public void shutdown(Duration timeout) {
        }
    }

    private static final class FakeAdmissions implements IdleAdmissionControl {
        private final Set<String> pending = new java.util.HashSet<>();
        private final Set<String> draining = new java.util.HashSet<>();

        @Override
        public boolean hasPendingJoin(String instanceId) {
            return pending.contains(instanceId);
        }

        @Override
        public boolean tryDrain(String instanceId) {
            return !pending.contains(instanceId) && draining.add(instanceId);
        }

        @Override
        public void cancelDrain(String instanceId) {
            draining.remove(instanceId);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
