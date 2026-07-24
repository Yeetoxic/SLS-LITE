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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class LocalLobbyProvider implements LobbyProvider {

    private final ProxyServer proxy;
    private final BlueprintRepository blueprints;
    private final ServerController instances;
    private final LobbyConfig config;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;

    private volatile CompletableFuture<RegisteredServer> ready = new CompletableFuture<>();
    private volatile ManagedInstance managedInstance;
    private volatile LobbyStatus status = LobbyStatus.OFFLINE;

    private boolean started;
    private boolean closed;
    private int recoveryAttempts;
    private long generation;
    private long handledGeneration = -1;
    private ScheduledFuture<?> retryTask;
    private ScheduledFuture<?> stableTask;

    public LocalLobbyProvider(
            ProxyServer proxy,
            BlueprintRepository blueprints,
            ServerController instances,
            LobbyConfig config,
            Logger logger
    ) {
        this(
                proxy,
                blueprints,
                instances,
                config,
                logger,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "sls-lite-lobby-recovery");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    public LocalLobbyProvider(
            ProxyServer proxy,
            BlueprintRepository blueprints,
            ServerController instances,
            LobbyConfig config,
            Logger logger,
            ScheduledExecutorService scheduler
    ) {
        this.proxy = proxy;
        this.blueprints = blueprints;
        this.instances = instances;
        this.config = config;
        this.logger = logger;
        this.scheduler = scheduler;
    }

    @Override
    public void start() {
        synchronized (this) {
            if (started || closed) {
                return;
            }
            started = true;
        }
        if (config.mode() == LobbyMode.EXTERNAL) {
            startExternal();
            return;
        }
        provision(false);
    }

    @Override
    public Optional<RegisteredServer> server() {
        if (config.mode() == LobbyMode.EXTERNAL) {
            return proxy.getServer(config.server());
        }
        ManagedInstance instance = managedInstance;
        if (status != LobbyStatus.READY || instance == null) {
            return Optional.empty();
        }
        return proxy.getServer(instance.id());
    }

    @Override
    public CompletableFuture<RegisteredServer> readyFuture() {
        return ready;
    }

    @Override
    public LobbyStatus status() {
        return status;
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
                    new IllegalStateException("Lobby is " + status.name().toLowerCase())
            );
        }

        List<CompletableFuture<Void>> transfers = source.getPlayersConnected().stream()
                .map(player -> transfer(player, lobby))
                .toList();
        return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new));
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            status = LobbyStatus.SHUTTING_DOWN;
            cancel(retryTask);
            cancel(stableTask);
            if (!ready.isDone()) {
                ready.completeExceptionally(
                        new CancellationException("Lobby provider is shutting down")
                );
            }
        }
        scheduler.shutdownNow();
    }

    private void startExternal() {
        RegisteredServer external = proxy.getServer(config.server()).orElse(null);
        if (external == null) {
            IllegalStateException failure = new IllegalStateException(
                    "External lobby is not registered: " + config.server()
            );
            status = LobbyStatus.OFFLINE;
            ready.completeExceptionally(failure);
            logger.warn("External lobby '{}' is not registered with Velocity", config.server());
            return;
        }
        status = LobbyStatus.EXTERNAL;
        ready.complete(external);
        logger.info("Using external lobby {}", config.server());
    }

    private void provision(boolean recovery) {
        long attemptGeneration;
        synchronized (this) {
            if (closed) {
                return;
            }
            attemptGeneration = ++generation;
            status = recovery ? LobbyStatus.RECOVERING : LobbyStatus.STARTING;
            retryTask = null;
        }

        try {
            Blueprint blueprint = blueprints.get(config.registry(), config.server())
                    .orElseThrow(() -> new InstanceOperationException(
                            "Managed lobby blueprint not found: "
                                    + config.registry() + "/" + config.server()
                    ));
            ManagedInstance instance = instances.start(blueprint.id());
            synchronized (this) {
                if (closed || generation != attemptGeneration) {
                    stopSuperseded(instance);
                    return;
                }
                managedInstance = instance;
            }
            logger.info(
                    "{} managed lobby {} from {}/{}",
                    recovery ? "Recovering" : "Preparing",
                    instance.id(),
                    config.registry(),
                    config.server()
            );
            instance.readyFuture().whenComplete((lobby, failure) -> {
                if (failure == null) {
                    publishReady(instance, attemptGeneration);
                } else {
                    handleLoss(instance, attemptGeneration, failure);
                }
            });
            instance.stoppedFuture().whenComplete((exitCode, failure) -> {
                Throwable cause = failure != null
                        ? failure
                        : new IllegalStateException(
                                "Lobby process exited with code " + exitCode
                        );
                handleLoss(instance, attemptGeneration, cause);
            });
        } catch (InstanceOperationException exception) {
            handleLoss(null, attemptGeneration, exception);
        }
    }

    private void publishReady(ManagedInstance instance, long attemptGeneration) {
        RegisteredServer registered = proxy.getServer(instance.id()).orElse(null);
        if (registered == null) {
            handleLoss(
                    instance,
                    attemptGeneration,
                    new IllegalStateException(
                            "Managed lobby is ready but not registered: " + instance.id()
                    )
            );
            return;
        }

        synchronized (this) {
            if (closed
                    || generation != attemptGeneration
                    || handledGeneration == attemptGeneration
                    || managedInstance != instance) {
                return;
            }
            status = LobbyStatus.READY;
            ready.complete(registered);
            cancel(stableTask);
            if (recoveryAttempts > 0) {
                stableTask = scheduler.schedule(
                        () -> markStable(instance, attemptGeneration),
                        config.stableAfterSeconds(),
                        TimeUnit.SECONDS
                );
            }
        }
        logger.info("Managed lobby {} is ready", instance.id());
    }

    private void handleLoss(
            ManagedInstance instance,
            long attemptGeneration,
            Throwable failure
    ) {
        int nextAttempt;
        long delay;
        boolean exhausted;
        synchronized (this) {
            if (closed
                    || generation != attemptGeneration
                    || handledGeneration == attemptGeneration
                    || (instance != null && managedInstance != instance)) {
                return;
            }
            handledGeneration = attemptGeneration;
            cancel(stableTask);
            stableTask = null;

            if (status == LobbyStatus.READY || ready.isDone()) {
                ready = new CompletableFuture<>();
            }
            exhausted = recoveryAttempts >= config.maxRestartAttempts();
            if (exhausted) {
                status = LobbyStatus.OFFLINE;
                ready.completeExceptionally(failure);
                nextAttempt = recoveryAttempts;
                delay = 0;
            } else {
                nextAttempt = ++recoveryAttempts;
                delay = backoffSeconds(nextAttempt);
                status = LobbyStatus.RECOVERING;
                try {
                    retryTask = scheduler.schedule(
                            () -> provision(true),
                            delay,
                            TimeUnit.SECONDS
                    );
                } catch (RejectedExecutionException exception) {
                    status = LobbyStatus.OFFLINE;
                    ready.completeExceptionally(exception);
                    exhausted = true;
                }
            }
        }

        if (exhausted) {
            logger.error(
                    "Managed lobby is offline after {} recovery attempt(s): {}",
                    nextAttempt,
                    rootMessage(failure)
            );
        } else {
            logger.warn(
                    "Managed lobby unavailable; recovery attempt {}/{} starts in {} second(s): {}",
                    nextAttempt,
                    config.maxRestartAttempts(),
                    delay,
                    rootMessage(failure)
            );
        }
    }

    private void markStable(ManagedInstance instance, long attemptGeneration) {
        synchronized (this) {
            if (closed
                    || generation != attemptGeneration
                    || managedInstance != instance
                    || status != LobbyStatus.READY) {
                return;
            }
            recoveryAttempts = 0;
            stableTask = null;
        }
        logger.info(
                "Managed lobby {} has been stable for {} seconds; recovery budget reset",
                instance.id(),
                config.stableAfterSeconds()
        );
    }

    private long backoffSeconds(int attempt) {
        long delay = config.initialBackoffSeconds();
        for (int index = 1; index < attempt; index++) {
            delay = Math.min(config.maxBackoffSeconds(), delay * 2);
        }
        return delay;
    }

    private void stopSuperseded(ManagedInstance instance) {
        try {
            instances.stop(instance.id());
        } catch (InstanceOperationException exception) {
            logger.warn(
                    "Unable to stop superseded lobby instance {}: {}",
                    instance.id(),
                    exception.getMessage()
            );
        }
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

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
