package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.SLSLimboConfig;
import net.slimelabs.slslite.instance.InstanceLifecycle;
import net.slimelabs.slslite.instance.InstanceState;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.ProcessSpec;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.process.SupervisedProcess;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.velocity.BackendRegistry;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class SLSLimboProvider implements LobbyProvider {

    public static final String SERVER_NAME = "sls-limbo";
    private static final Pattern READY_PATTERN =
            Pattern.compile("Server started on ");

    private final ProxyServer proxy;
    private final SLSLimboConfig config;
    private final ForwardingConfig forwarding;
    private final SLSLimboInstaller installer;
    private final ResourceBudget resourceBudget;
    private final LoopbackPortAllocator ports;
    private final ProcessSupervisor processes;
    private final BackendRegistry backends;
    private final Logger logger;
    private final ScheduledExecutorService recoveryScheduler;
    private final CompletableFuture<RegisteredServer> ready = new CompletableFuture<>();
    private final AtomicBoolean resourcesReleased = new AtomicBoolean();

    private volatile LobbyStatus status = LobbyStatus.OFFLINE;
    private volatile SupervisedProcess process;
    private volatile int port = -1;
    private volatile boolean registered;
    private volatile boolean closed;
    private volatile String lastFailure;

    private boolean started;
    private boolean memoryReserved;
    private volatile int recoveryAttempts;
    private long generation;
    private long handledGeneration = -1;
    private ScheduledFuture<?> retryTask;
    private ScheduledFuture<?> stableTask;
    private SLSLimboInstaller.SLSLimboInstallation installation;

    public SLSLimboProvider(
            ProxyServer proxy,
            SLSLimboConfig config,
            ForwardingConfig forwarding,
            Path dataDirectory,
            ResourceBudget resourceBudget,
            LoopbackPortAllocator ports,
            ProcessSupervisor processes,
            BackendRegistry backends,
            Logger logger
    ) {
        this(
                proxy,
                config,
                forwarding,
                dataDirectory,
                resourceBudget,
                ports,
                processes,
                backends,
                logger,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "sls-lite-limbo-recovery"
                    );
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    SLSLimboProvider(
            ProxyServer proxy,
            SLSLimboConfig config,
            ForwardingConfig forwarding,
            Path dataDirectory,
            ResourceBudget resourceBudget,
            LoopbackPortAllocator ports,
            ProcessSupervisor processes,
            BackendRegistry backends,
            Logger logger,
            ScheduledExecutorService recoveryScheduler
    ) {
        this.proxy = proxy;
        this.config = config;
        this.forwarding = forwarding;
        this.installer = new SLSLimboInstaller(dataDirectory);
        this.resourceBudget = resourceBudget;
        this.ports = ports;
        this.processes = processes;
        this.backends = backends;
        this.logger = logger;
        this.recoveryScheduler = recoveryScheduler;
    }

    @Override
    public void start() {
        synchronized (this) {
            if (started || closed) {
                return;
            }
            started = true;
            if (!config.enabled()) {
                ready.completeExceptionally(
                        new IllegalStateException("SLS-Limbo is disabled")
                );
                return;
            }
            status = LobbyStatus.STARTING;
        }

        prepareAndLaunch(false);
    }

    @Override
    public Optional<RegisteredServer> server() {
        if (status != LobbyStatus.READY) {
            return Optional.empty();
        }
        return proxy.getServer(SERVER_NAME);
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
        return SERVER_NAME.equals(serverName);
    }

    @Override
    public boolean isHoldingLobby(String serverName) {
        return isLobby(serverName);
    }

    @Override
    public Optional<SLSLimboDiagnostics> limboDiagnostics() {
        return Optional.of(new SLSLimboDiagnostics(
                config.enabled(),
                status,
                config.memoryMiB(),
                config.advertisedProtocol(),
                port < 0 ? OptionalInt.empty() : OptionalInt.of(port),
                recoveryAttempts,
                config.maxRestartAttempts(),
                Optional.ofNullable(lastFailure)
        ));
    }

    @Override
    public CompletableFuture<Void> evacuate(String serverName) {
        if (isLobby(serverName)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("SLS-Limbo cannot be evacuated")
            );
        }
        return CompletableFuture.failedFuture(
                new IllegalStateException(
                        "SLS-Limbo evacuation must use the fallback coordinator"
                )
        );
    }

    @Override
    public void close() {
        SupervisedProcess current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            status = LobbyStatus.SHUTTING_DOWN;
            cancel(retryTask);
            cancel(stableTask);
            unregister();
            current = process;
            if (!ready.isDone()) {
                ready.completeExceptionally(
                        new CancellationException("SLS-Limbo is shutting down")
                );
            }
        }
        recoveryScheduler.shutdownNow();
        if (current == null) {
            releaseResources();
            return;
        }
        try {
            current.stop();
        } catch (IllegalStateException exception) {
            current.forceStop();
        }
        current.exitFuture().whenComplete((exitCode, failure) -> releaseResources());
    }

    private synchronized void reserveResources() throws Exception {
        if (closed) {
            throw new CancellationException("SLS-Limbo is shutting down");
        }
        if (!memoryReserved) {
            if (!resourceBudget.tryReserve(SERVER_NAME, config.memoryMiB())) {
                throw new IllegalStateException(
                        "Insufficient managed memory for SLS-Limbo: "
                                + config.memoryMiB() + " MiB"
                );
            }
            memoryReserved = true;
        }
        if (port < 0) {
            port = ports.allocate();
        }
        if (installation == null) {
            installation = installer.install(
                    port,
                    forwarding,
                    config.advertisedProtocol()
            );
        }
    }

    private void prepareAndLaunch(boolean recovery) {
        long attemptGeneration;
        synchronized (this) {
            if (closed) {
                return;
            }
            attemptGeneration = ++generation;
            handledGeneration = -1;
            status = recovery ? LobbyStatus.RECOVERING : LobbyStatus.STARTING;
            retryTask = null;
        }

        try {
            reserveResources();
            launch(recovery, attemptGeneration, installation);
        } catch (Exception exception) {
            handleFailure(null, attemptGeneration, exception);
        }
    }

    private void launch(
            boolean recovery,
            long attemptGeneration,
            SLSLimboInstaller.SLSLimboInstallation currentInstallation
    ) {
        synchronized (this) {
            if (closed || generation != attemptGeneration) {
                return;
            }
        }
        try {
            InstanceLifecycle lifecycle = new InstanceLifecycle(SERVER_NAME);
            lifecycle.transitionTo(InstanceState.PREPARING);
            SupervisedProcess launched = processes.start(
                    SERVER_NAME,
                    processSpec(currentInstallation),
                    lifecycle,
                    line -> logger.info("[sls-limbo] {}", line)
            );
            synchronized (this) {
                if (closed || generation != attemptGeneration) {
                    launched.forceStop();
                    return;
                }
                process = launched;
            }
            launched.readyFuture().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    publishReady(launched, attemptGeneration);
                } else {
                    handleFailure(launched, attemptGeneration, failure);
                }
            });
            launched.exitFuture().whenComplete((exitCode, failure) -> {
                if (closed) {
                    releaseResources();
                    return;
                }
                Throwable cause = failure == null
                        ? new IllegalStateException(
                                "SLS-Limbo exited with code " + exitCode
                        )
                        : failure;
                handleFailure(launched, attemptGeneration, cause);
            });
            logger.info(
                    "{} SLS-Limbo runtime NanoLimbo {} ({}) with {} MiB",
                    recovery ? "Recovering" : "Starting",
                    SLSLimboInstaller.NANOLIMBO_VERSION,
                    SLSLimboInstaller.NANOLIMBO_COMMIT.substring(0, 8),
                    config.memoryMiB()
            );
        } catch (Exception exception) {
            handleFailure(null, attemptGeneration, exception);
        }
    }

    private void publishReady(
            SupervisedProcess launched,
            long attemptGeneration
    ) {
        RegisteredServer registeredServer;
        try {
            synchronized (this) {
                if (closed
                        || generation != attemptGeneration
                        || handledGeneration == attemptGeneration
                        || process != launched) {
                    return;
                }
                backends.register(
                        SERVER_NAME,
                        new InetSocketAddress("127.0.0.1", port),
                        config.advertisedProtocol()
                );
                registered = true;
                registeredServer = proxy.getServer(SERVER_NAME)
                        .orElseThrow(() -> new IllegalStateException(
                                "SLS-Limbo was not registered with Velocity"
                        ));
                status = LobbyStatus.READY;
                lastFailure = null;
                ready.complete(registeredServer);
                cancel(stableTask);
                if (recoveryAttempts > 0) {
                    stableTask = recoveryScheduler.schedule(
                            () -> markStable(launched, attemptGeneration),
                            config.stableAfterSeconds(),
                            TimeUnit.SECONDS
                    );
                }
            }
            logger.info("SLS-Limbo is ready on 127.0.0.1:{}", port);
        } catch (RuntimeException exception) {
            handleFailure(launched, attemptGeneration, exception);
        }
    }

    private void handleFailure(
            SupervisedProcess failedProcess,
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
                    || (failedProcess != null && process != failedProcess)) {
                return;
            }
            handledGeneration = attemptGeneration;
            cancel(stableTask);
            stableTask = null;
            unregister();
            lastFailure = rootMessage(failure);
            if (failedProcess != null
                    && failedProcess.state() != InstanceState.STOPPED
                    && failedProcess.state() != InstanceState.STOPPING) {
                failedProcess.forceStop();
            }

            exhausted = recoveryAttempts >= config.maxRestartAttempts();
            if (exhausted) {
                status = LobbyStatus.OFFLINE;
                nextAttempt = recoveryAttempts;
                delay = 0;
                if (!ready.isDone()) {
                    ready.completeExceptionally(failure);
                }
            } else {
                nextAttempt = ++recoveryAttempts;
                delay = backoffSeconds(nextAttempt);
                status = LobbyStatus.RECOVERING;
                try {
                    retryTask = recoveryScheduler.schedule(
                            () -> prepareAndLaunch(true),
                            delay,
                            TimeUnit.SECONDS
                    );
                } catch (RejectedExecutionException exception) {
                    status = LobbyStatus.OFFLINE;
                    lastFailure = rootMessage(exception);
                    exhausted = true;
                    if (!ready.isDone()) {
                        ready.completeExceptionally(exception);
                    }
                }
            }
        }

        if (exhausted) {
            logger.error(
                    "SLS-Limbo is offline after {} recovery attempt(s): {}",
                    nextAttempt,
                    rootMessage(failure)
            );
            releaseAfterTerminalFailure(failedProcess);
        } else {
            logger.warn(
                    "SLS-Limbo unavailable; recovery attempt {}/{} starts "
                            + "in {} second(s): {}",
                    nextAttempt,
                    config.maxRestartAttempts(),
                    delay,
                    rootMessage(failure)
            );
        }
    }

    private void markStable(
            SupervisedProcess stableProcess,
            long attemptGeneration
    ) {
        synchronized (this) {
            if (closed
                    || generation != attemptGeneration
                    || process != stableProcess
                    || status != LobbyStatus.READY) {
                return;
            }
            recoveryAttempts = 0;
            stableTask = null;
        }
        logger.info(
                "SLS-Limbo has been stable for {} seconds; recovery budget reset",
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

    private void unregister() {
        if (registered) {
            backends.unregister(SERVER_NAME);
            registered = false;
        }
    }

    private void releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) {
            return;
        }
        int reservedPort = port;
        port = -1;
        if (reservedPort >= 0) {
            ports.release(reservedPort);
        }
        if (memoryReserved) {
            resourceBudget.release(SERVER_NAME);
            memoryReserved = false;
        }
    }

    private void releaseAfterTerminalFailure(SupervisedProcess failedProcess) {
        if (failedProcess == null) {
            releaseResources();
            return;
        }
        failedProcess.exitFuture().whenComplete(
                (exitCode, exitFailure) -> releaseResources()
        );
    }

    private ProcessSpec processSpec(
            SLSLimboInstaller.SLSLimboInstallation currentInstallation
    ) {
        return new ProcessSpec(
                java.util.List.of(
                        javaExecutable(),
                        "-Xms32M",
                        "-Xmx" + config.memoryMiB() + "M",
                        "-jar",
                        currentInstallation.runtimeJar().toString()
                ),
                currentInstallation.workingDirectory(),
                READY_PATTERN,
                Duration.ofSeconds(config.startupTimeoutSeconds()),
                "stop",
                Duration.ofSeconds(10)
        );
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
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

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }
}
