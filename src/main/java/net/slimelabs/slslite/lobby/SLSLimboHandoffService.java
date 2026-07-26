package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.velocity.TransferActionBar;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SLSLimboHandoffService implements AutoCloseable {

    private final LobbyProvider lobbies;
    private final Logger logger;
    private final Map<UUID, WaitingPlayer> waiting = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final TransferActionBar actionBar;
    private volatile boolean closed;

    public SLSLimboHandoffService(LobbyProvider lobbies, Logger logger) {
        this(
                lobbies,
                logger,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "sls-lite-limbo-handoff"
                    );
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    SLSLimboHandoffService(
            LobbyProvider lobbies,
            Logger logger,
            ScheduledExecutorService scheduler
    ) {
        this.lobbies = lobbies;
        this.logger = logger;
        this.scheduler = scheduler;
        this.actionBar = new TransferActionBar(scheduler);
        lobbies.addPrimaryReadyListener(this::primaryReady);
    }

    public void awaitPrimary(Player player) {
        if (closed) {
            return;
        }
        waiting.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new WaitingPlayer(player)
        );
    }

    public void connected(Player player, RegisteredServer server) {
        UUID playerId = player.getUniqueId();
        String serverName = server.getServerInfo().getName();
        if (!lobbies.isHoldingLobby(serverName)) {
            if (lobbies.isLobby(serverName)) {
                remove(playerId);
            }
            return;
        }
        WaitingPlayer entry = waiting.get(playerId);
        if (entry == null) {
            return;
        }
        actionBar.start(player);
        lobbies.primaryServer().ifPresent(primary -> transfer(entry, primary));
    }

    public void disconnect(UUID playerId) {
        remove(playerId);
    }

    int waitingCount() {
        return waiting.size();
    }

    @Override
    public void close() {
        closed = true;
        waiting.clear();
        actionBar.close();
        scheduler.shutdownNow();
    }

    private void primaryReady(RegisteredServer primary) {
        if (closed) {
            return;
        }
        waiting.values().forEach(entry -> {
            boolean inLimbo = entry.player().getCurrentServer()
                    .map(connection -> connection.getServer())
                    .map(server -> server.getServerInfo().getName())
                    .map(lobbies::isHoldingLobby)
                    .orElse(false);
            if (inLimbo) {
                transfer(entry, primary);
            }
        });
    }

    private void transfer(WaitingPlayer entry, RegisteredServer primary) {
        if (!entry.transferring().compareAndSet(false, true)) {
            return;
        }
        Player player = entry.player();
        actionBar.joining(player, primary.getServerInfo().getName());
        player.createConnectionRequest(primary).connect()
                .whenComplete((result, failure) -> {
                    entry.transferring().set(false);
                    if (failure != null) {
                        transferFailed(player, primary, failure.getMessage());
                        return;
                    }
                    if (result.getStatus() == ConnectionRequestBuilder.Status.SUCCESS
                            || result.getStatus()
                            == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                        remove(player.getUniqueId());
                        return;
                    }
                    transferFailed(player, primary, result.getStatus().name());
                });
    }

    private void transferFailed(
            Player player,
            RegisteredServer primary,
            String detail
    ) {
        lobbies.markPrimaryUnavailable(primary.getServerInfo().getName());
        if (!closed && player.isActive()) {
            actionBar.start(player);
            player.sendMessage(Component.text(
                    "Your destination is not ready yet. Remaining in SLS-Limbo.",
                    NamedTextColor.YELLOW
            ));
        }
        logger.warn(
                "Unable to hand {} from SLS-Limbo to the primary lobby: {}",
                player.getUsername(),
                detail
        );
    }

    private void remove(UUID playerId) {
        waiting.remove(playerId);
        actionBar.stop(playerId);
    }

    private record WaitingPlayer(
            Player player,
            AtomicBoolean transferring
    ) {
        private WaitingPlayer(Player player) {
            this(player, new AtomicBoolean());
        }
    }
}
