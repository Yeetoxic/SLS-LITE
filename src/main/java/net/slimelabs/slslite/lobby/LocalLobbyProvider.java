package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.LobbyConfig;
import net.slimelabs.slslite.config.LobbyMode;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class LocalLobbyProvider implements LobbyProvider {

    private final ProxyServer proxy;
    private final BlueprintRepository blueprints;
    private final ServerController instances;
    private final LobbyConfig config;
    private final Logger logger;
    private final CompletableFuture<RegisteredServer> ready = new CompletableFuture<>();

    private volatile ManagedInstance managedInstance;

    public LocalLobbyProvider(
            ProxyServer proxy,
            BlueprintRepository blueprints,
            ServerController instances,
            LobbyConfig config,
            Logger logger
    ) {
        this.proxy = proxy;
        this.blueprints = blueprints;
        this.instances = instances;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void start() {
        if (config.mode() == LobbyMode.EXTERNAL) {
            RegisteredServer external = proxy.getServer(config.server()).orElse(null);
            if (external == null) {
                logger.warn(
                        "External lobby '{}' is not registered with Velocity",
                        config.server()
                );
            } else {
                ready.complete(external);
                logger.info("Using external lobby {}", config.server());
            }
            return;
        }

        try {
            Blueprint blueprint = blueprints.get(config.registry(), config.server())
                    .orElseThrow(() -> new InstanceOperationException(
                            "Managed lobby blueprint not found: "
                                    + config.registry() + "/" + config.server()
                    ));
            ManagedInstance instance = instances.start(blueprint.id());
            managedInstance = instance;
            logger.info(
                    "Preparing managed lobby {} from {}/{}",
                    instance.id(),
                    config.registry(),
                    config.server()
            );
            instance.readyFuture().whenComplete((lobby, failure) -> {
                if (failure != null) {
                    ready.completeExceptionally(failure);
                    logger.error("Managed lobby failed to start", failure);
                    return;
                }
                RegisteredServer registered = proxy.getServer(lobby.id()).orElse(null);
                if (registered == null) {
                    IllegalStateException exception = new IllegalStateException(
                            "Managed lobby is ready but not registered: " + lobby.id()
                    );
                    ready.completeExceptionally(exception);
                    logger.error(exception.getMessage());
                    return;
                }
                ready.complete(registered);
                logger.info("Managed lobby {} is ready", lobby.id());
            });
        } catch (InstanceOperationException exception) {
            ready.completeExceptionally(exception);
            logger.error("Unable to provision managed lobby", exception);
        }
    }

    @Override
    public Optional<RegisteredServer> server() {
        if (config.mode() == LobbyMode.EXTERNAL) {
            return proxy.getServer(config.server());
        }
        ManagedInstance instance = managedInstance;
        return instance == null ? Optional.empty() : proxy.getServer(instance.id());
    }

    @Override
    public CompletableFuture<RegisteredServer> readyFuture() {
        return ready;
    }

    @Override
    public boolean isLobby(String serverName) {
        if (config.mode() == LobbyMode.EXTERNAL) {
            return config.server().equals(serverName);
        }
        ManagedInstance instance = managedInstance;
        return instance != null && instance.id().equals(serverName);
    }

    @Override
    public CompletableFuture<Void> evacuate(String serverName) {
        if (isLobby(serverName)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("The active lobby cannot be evacuated")
            );
        }
        RegisteredServer source = proxy.getServer(serverName).orElse(null);
        if (source == null || source.getPlayersConnected().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        RegisteredServer lobby = server().orElse(null);
        if (lobby == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Lobby is not ready")
            );
        }

        List<CompletableFuture<Void>> transfers = source.getPlayersConnected().stream()
                .map(player -> transfer(player, lobby))
                .toList();
        return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> transfer(Player player, RegisteredServer lobby) {
        return player.createConnectionRequest(lobby).connect().thenAccept(result -> {
            if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS
                    && result.getStatus()
                    != ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                throw new CompletionException(new IllegalStateException(
                        "Unable to move " + player.getUsername()
                                + " to lobby: " + result.getStatus()
                ));
            }
        });
    }
}
