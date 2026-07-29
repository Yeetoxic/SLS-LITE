package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ManagedInstanceTestFactory;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.LobbyStatus;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SLSCommandForcedStopTest {

    private static final String INSTANCE_ID = "lobby.abc123";

    @TempDir
    Path temporaryDirectory;

    private AdministratorStore administrators;
    private ManagedInstance instance;

    @BeforeEach
    void setUp() throws Exception {
        administrators = new AdministratorStore(temporaryDirectory);
        administrators.initialize();
        Blueprint blueprint = new Blueprint(
                "lobby",
                "Lobby",
                "lobby",
                "paper",
                "26.1",
                512,
                false,
                Map.of()
        );
        instance = ManagedInstanceTestFactory.preparing(
                INSTANCE_ID,
                blueprint,
                25570,
                temporaryDirectory.resolve(INSTANCE_ID)
        );
    }

    @Test
    void protectedLobbyRequiresForceModifier() {
        TrackingController controller = new TrackingController(instance);
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        SLSCommand command = command(controller, lobby);

        command.execute(invocation(
                source(Set.of("sls.command.stop"), new ArrayList<>()),
                "stop",
                INSTANCE_ID
        ));

        assertEquals(0, lobby.evacuations);
        assertEquals(0, controller.stops);
    }

    @Test
    void forceModifierRequiresDistinctPermission() {
        TrackingController controller = new TrackingController(instance);
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        SLSCommand command = command(controller, lobby);

        command.execute(invocation(
                source(Set.of("sls.command.stop"), new ArrayList<>()),
                "stop",
                INSTANCE_ID,
                "--force"
        ));

        assertEquals(0, lobby.evacuations);
        assertEquals(0, controller.stops);
    }

    @Test
    void builtInAdministratorCanForceStopThisLobby() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        administrators.add(uniqueId, "Admin");
        TrackingController controller = new TrackingController(instance);
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        SLSCommand command = command(controller, lobby);

        command.execute(invocation(
                player(uniqueId, INSTANCE_ID),
                "stop",
                "this",
                "--force"
        ));

        assertEquals(1, lobby.evacuations);
        assertTrue(lobby.prepared);
        assertEquals(1, lobby.begins);
        assertEquals(0, lobby.cancellations);
        assertEquals(1, controller.stops);
    }

    @Test
    void evacuationFailureCancelsForcedStop() {
        TrackingController controller = new TrackingController(instance);
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        lobby.evacuation = CompletableFuture.failedFuture(
                new IllegalStateException("No alternate lobby is ready")
        );
        SLSCommand command = command(controller, lobby);

        command.execute(invocation(
                source(
                        Set.of("sls.command.stop", "sls.command.stop.force"),
                        new ArrayList<>()
                ),
                "stop",
                INSTANCE_ID,
                "--force"
        ));

        assertEquals(1, lobby.evacuations);
        assertFalse(lobby.prepared);
        assertEquals(1, lobby.begins);
        assertEquals(1, lobby.cancellations);
        assertEquals(0, controller.stops);
    }

    @Test
    void secondForcedStopIsRejectedWhileLobbyIsDraining() {
        TrackingController controller = new TrackingController(instance);
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        lobby.evacuation = new CompletableFuture<>();
        SLSCommand command = command(controller, lobby);
        CommandSource source = source(
                Set.of("sls.command.stop", "sls.command.stop.force"),
                new ArrayList<>()
        );

        command.execute(invocation(source, "stop", INSTANCE_ID, "--force"));
        command.execute(invocation(source, "stop", INSTANCE_ID, "--force"));

        assertEquals(1, lobby.evacuations);
        assertEquals(2, lobby.begins);
        assertEquals(0, controller.stops);
    }

    @Test
    void forceSuggestionIsHiddenWithoutForcePermission() {
        SLSCommand command = command(
                new TrackingController(instance),
                new TrackingLobby(INSTANCE_ID)
        );

        List<String> normalSuggestions = command.suggestAsync(invocation(
                source(Set.of("sls.command.stop"), new ArrayList<>()),
                "stop",
                INSTANCE_ID,
                ""
        )).join();
        List<String> forceSuggestions = command.suggestAsync(invocation(
                source(
                        Set.of("sls.command.stop", "sls.command.stop.force"),
                        new ArrayList<>()
                ),
                "stop",
                INSTANCE_ID,
                ""
        )).join();

        assertTrue(normalSuggestions.isEmpty());
        assertEquals(List.of("--force"), forceSuggestions);
    }

    @Test
    void protectedLobbyRestartRequiresForceModifierAndPermission() {
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        SLSCommand command = command(new TrackingController(instance), lobby);

        command.execute(invocation(
                source(Set.of("sls.command.restart"), new ArrayList<>()),
                "restart",
                INSTANCE_ID
        ));
        command.execute(invocation(
                source(Set.of("sls.command.restart"), new ArrayList<>()),
                "restart",
                INSTANCE_ID,
                "--force"
        ));

        assertEquals(0, lobby.evacuations);
        assertEquals(0, lobby.cycles);
    }

    @Test
    void forcedLobbyRestartDrainsAndCyclesPrimary() {
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        SLSCommand command = command(new TrackingController(instance), lobby);

        command.execute(invocation(
                source(
                        Set.of(
                                "sls.command.restart",
                                "sls.command.restart.force"
                        ),
                        new ArrayList<>()
                ),
                "restart",
                INSTANCE_ID,
                "--force"
        ));

        assertEquals(1, lobby.begins);
        assertEquals(1, lobby.evacuations);
        assertEquals(1, lobby.cycles);
        assertFalse(lobby.lastReset);
    }

    @Test
    void forcedLobbyResetUsesResetCycle() {
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        SLSCommand command = command(new TrackingController(instance), lobby);

        command.execute(invocation(
                source(
                        Set.of(
                                "sls.command.reset",
                                "sls.command.reset.force"
                        ),
                        new ArrayList<>()
                ),
                "reset",
                INSTANCE_ID,
                "--force"
        ));

        assertEquals(1, lobby.cycles);
        assertTrue(lobby.lastReset);
    }

    @Test
    void forcedResetRecoversProtectedLobbyWhenNoActiveInstanceExists() {
        TrackingLobby lobby = new TrackingLobby(INSTANCE_ID);
        SLSCommand command = command(new TrackingController(null), lobby);

        command.execute(invocation(
                source(
                        Set.of(
                                "sls.command.reset",
                                "sls.command.reset.force"
                        ),
                        new ArrayList<>()
                ),
                "reset",
                INSTANCE_ID,
                "--force"
        ));

        assertEquals(0, lobby.begins);
        assertEquals(0, lobby.evacuations);
        assertEquals(1, lobby.cycles);
        assertTrue(lobby.lastReset);
    }

    @Test
    void restartForceSuggestionIsHiddenWithoutForcePermission() {
        SLSCommand command = command(
                new TrackingController(instance),
                new TrackingLobby(INSTANCE_ID)
        );

        List<String> normalSuggestions = command.suggestAsync(invocation(
                source(Set.of("sls.command.restart"), new ArrayList<>()),
                "restart",
                INSTANCE_ID,
                ""
        )).join();
        List<String> forceSuggestions = command.suggestAsync(invocation(
                source(
                        Set.of(
                                "sls.command.restart",
                                "sls.command.restart.force"
                        ),
                        new ArrayList<>()
                ),
                "restart",
                INSTANCE_ID,
                ""
        )).join();

        assertTrue(normalSuggestions.isEmpty());
        assertEquals(List.of("--force"), forceSuggestions);
    }

    private SLSCommand command(
            TrackingController controller,
            TrackingLobby lobby
    ) {
        Logger logger = LoggerFactory.getLogger(SLSCommandForcedStopTest.class);
        return new SLSCommand(
                null,
                null,
                null,
                null,
                null,
                controller,
                null,
                lobby,
                null,
                null,
                null,
                administrators,
                null,
                logger
        );
    }

    private static SimpleCommand.Invocation invocation(
            CommandSource source,
            String... arguments
    ) {
        return (SimpleCommand.Invocation) Proxy.newProxyInstance(
                SimpleCommand.Invocation.class.getClassLoader(),
                new Class<?>[]{SimpleCommand.Invocation.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "source" -> source;
                    case "arguments" -> arguments;
                    case "alias" -> "sls";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static CommandSource source(
            Set<String> permissions,
            List<Component> messages
    ) {
        return (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasPermission" -> permissions.contains(arguments[0]);
                    case "sendMessage" -> {
                        messages.add((Component) arguments[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Player player(UUID uniqueId, String currentServer) {
        ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(),
                new Class<?>[]{ServerConnection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServerInfo" -> new com.velocitypowered.api.proxy.server.ServerInfo(
                            currentServer,
                            java.net.InetSocketAddress.createUnresolved(
                                    "127.0.0.1",
                                    25570
                            )
                    );
                    default -> defaultValue(method.getReturnType());
                }
        );
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uniqueId;
                    case "getUsername" -> "Admin";
                    case "getCurrentServer" -> Optional.of(connection);
                    case "hasPermission" -> false;
                    default -> defaultValue(method.getReturnType());
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

    private static final class TrackingController implements ServerController {
        private final ManagedInstance instance;
        private int stops;

        private TrackingController(ManagedInstance instance) {
            this.instance = instance;
        }

        @Override
        public ManagedInstance start(String blueprintId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<ManagedInstance> getAll() {
            return instance == null ? List.of() : List.of(instance);
        }

        @Override
        public ManagedInstance get(String instanceId)
                throws InstanceOperationException {
            if (instance == null || !instance.id().equals(instanceId)) {
                throw new InstanceOperationException("Unknown instance");
            }
            return instance;
        }

        @Override
        public CompletableFuture<Integer> stop(String instanceId) {
            stops++;
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public void shutdown(Duration timeout) {
        }
    }

    private static final class TrackingLobby implements LobbyProvider {
        private final String lobbyId;
        private CompletableFuture<Void> evacuation =
                CompletableFuture.completedFuture(null);
        private int evacuations;
        private int begins;
        private int cancellations;
        private int cycles;
        private boolean draining;
        private boolean prepared;
        private boolean lastReset;

        private TrackingLobby(String lobbyId) {
            this.lobbyId = lobbyId;
        }

        @Override
        public void start() {
        }

        @Override
        public Optional<com.velocitypowered.api.proxy.server.RegisteredServer> server() {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<com.velocitypowered.api.proxy.server.RegisteredServer>
        readyFuture() {
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
        public CompletableFuture<Void> evacuateForIntentionalStop(String serverName) {
            evacuations++;
            return evacuation;
        }

        @Override
        public boolean beginIntentionalStop(String serverName) {
            begins++;
            if (draining || !lobbyId.equals(serverName)) {
                return false;
            }
            draining = true;
            return true;
        }

        @Override
        public void cancelIntentionalStop(String serverName) {
            if (draining && lobbyId.equals(serverName)) {
                draining = false;
                cancellations++;
            }
        }

        @Override
        public boolean prepareIntentionalStop(String serverName) {
            prepared = draining && lobbyId.equals(serverName);
            draining = false;
            return prepared;
        }

        @Override
        public CompletableFuture<com.velocitypowered.api.proxy.server.RegisteredServer>
        cyclePrimary(String serverName, boolean reset) {
            cycles++;
            lastReset = reset;
            draining = false;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }
}
