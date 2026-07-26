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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        player.sendActionBar(Component.text(
                "Waiting for a safe destination...",
                NamedTextColor.GOLD
        ));
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
        if (System.nanoTime() < entry.nextAttemptNanos().get()) {
            return;
        }
        if (!entry.transferring().compareAndSet(false, true)) {
            return;
        }
        Player player = entry.player();
        actionBar.joining(player, primary.getServerInfo().getName());
        player.createConnectionRequest(primary).connect()
                .whenComplete((result, failure) -> {
                    entry.transferring().set(false);
                    if (failure != null) {
                        transferFailed(
                                entry,
                                primary,
                                failure.getMessage()
                        );
                        return;
                    }
                    if (result.getStatus() == ConnectionRequestBuilder.Status.SUCCESS
                            || result.getStatus()
                            == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                        remove(player.getUniqueId());
                        return;
                    }
                    transferFailed(entry, primary, result.getStatus().name());
                });
    }

    private void transferFailed(
            WaitingPlayer entry,
            RegisteredServer primary,
            String detail
    ) {
        Player player = entry.player();
        int failures = entry.failures().incrementAndGet();
        long retrySeconds = Math.min(60L, 10L << Math.min(failures - 1, 3));
        entry.nextAttemptNanos().set(
                System.nanoTime() + TimeUnit.SECONDS.toNanos(retrySeconds)
        );
        lobbies.markPrimaryUnavailable(primary.getServerInfo().getName());
        actionBar.stop(player.getUniqueId());
        if (!closed && player.isActive()) {
            player.sendActionBar(Component.text(
                    "Destination unavailable; staying in SLS-Limbo.",
                    NamedTextColor.YELLOW
            ));
            if (entry.playerNotified().compareAndSet(false, true)) {
                player.sendMessage(Component.text(
                        "Your destination is not ready yet. You will remain "
                                + "in SLS-Limbo while SLS-LITE checks it.",
                        NamedTextColor.YELLOW
                ));
            }
        }
        scheduleRetry(entry, retrySeconds);
        if (failures == 1) {
            logger.warn(
                    "Unable to hand {} from SLS-Limbo to the primary lobby: {}. "
                            + "Retrying in {} seconds; repeated failures are "
                            + "logged at debug level.",
                    player.getUsername(),
                    detail,
                    retrySeconds
            );
        } else {
            logger.debug(
                    "SLS-Limbo handoff retry {} for {} failed: {}; next retry "
                            + "in {} seconds",
                    failures,
                    player.getUsername(),
                    detail,
                    retrySeconds
            );
        }
    }

    private void scheduleRetry(WaitingPlayer entry, long delaySeconds) {
        ScheduledFuture<?> previous = entry.retryTask();
        if (previous != null) {
            previous.cancel(false);
        }
        entry.retryTask(scheduler.schedule(
                () -> {
                    if (!closed
                            && waiting.get(entry.player().getUniqueId()) == entry) {
                        lobbies.primaryServer()
                                .ifPresent(primary -> transfer(entry, primary));
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS
        ));
    }

    private void remove(UUID playerId) {
        WaitingPlayer removed = waiting.remove(playerId);
        if (removed != null && removed.retryTask() != null) {
            removed.retryTask().cancel(false);
        }
        actionBar.stop(playerId);
    }

    private static final class WaitingPlayer {

        private final Player player;
        private final AtomicBoolean transferring = new AtomicBoolean();
        private final AtomicBoolean playerNotified = new AtomicBoolean();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong nextAttemptNanos = new AtomicLong();
        private volatile ScheduledFuture<?> retryTask;

        private WaitingPlayer(Player player) {
            this.player = player;
        }

        private Player player() {
            return player;
        }

        private AtomicBoolean transferring() {
            return transferring;
        }

        private AtomicBoolean playerNotified() {
            return playerNotified;
        }

        private AtomicInteger failures() {
            return failures;
        }

        private AtomicLong nextAttemptNanos() {
            return nextAttemptNanos;
        }

        private ScheduledFuture<?> retryTask() {
            return retryTask;
        }

        private void retryTask(ScheduledFuture<?> task) {
            retryTask = task;
        }
    }
}
