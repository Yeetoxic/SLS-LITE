package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.config.SLSLimboConfig;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.instance.InstanceLifecycle;
import net.slimelabs.slslite.instance.InstanceState;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.ProcessSpec;
import net.slimelabs.slslite.process.ProcessStartException;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.process.SupervisedProcess;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.velocity.BackendRegistry;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    private final CompletableFuture<RegisteredServer> ready = new CompletableFuture<>();
    private final AtomicBoolean resourcesReleased = new AtomicBoolean();

    private volatile LobbyStatus status = LobbyStatus.OFFLINE;
    private volatile SupervisedProcess process;
    private volatile int port = -1;
    private volatile boolean registered;
    private volatile boolean closed;

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
        this.proxy = proxy;
        this.config = config;
        this.forwarding = forwarding;
        this.installer = new SLSLimboInstaller(dataDirectory);
        this.resourceBudget = resourceBudget;
        this.ports = ports;
        this.processes = processes;
        this.backends = backends;
        this.logger = logger;
    }

    @Override
    public synchronized void start() {
        if (closed || status != LobbyStatus.OFFLINE) {
            return;
        }
        if (!config.enabled()) {
            ready.completeExceptionally(
                    new IllegalStateException("SLS-Limbo is disabled")
            );
            return;
        }
        status = LobbyStatus.STARTING;
        try {
            if (!resourceBudget.tryReserve(SERVER_NAME, config.memoryMiB())) {
                throw new IllegalStateException(
                        "Insufficient managed memory for SLS-Limbo: "
                                + config.memoryMiB() + " MiB"
                );
            }
            port = ports.allocate();
            SLSLimboInstaller.SLSLimboInstallation installation =
                    installer.install(port, forwarding);
            InstanceLifecycle lifecycle = new InstanceLifecycle(SERVER_NAME);
            lifecycle.transitionTo(InstanceState.PREPARING);
            process = processes.start(
                    SERVER_NAME,
                    processSpec(installation),
                    lifecycle,
                    line -> logger.info("[sls-limbo] {}", line)
            );
            process.readyFuture().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    publishReady();
                } else {
                    fail(failure);
                }
            });
            process.exitFuture().whenComplete((exitCode, failure) -> {
                if (!closed && status == LobbyStatus.READY) {
                    fail(failure == null
                            ? new IllegalStateException(
                                    "SLS-Limbo exited with code " + exitCode
                            )
                            : failure);
                } else {
                    releaseResources();
                }
            });
            logger.info(
                    "Starting SLS-Limbo runtime NanoLimbo {} ({}) with {} MiB",
                    SLSLimboInstaller.NANOLIMBO_VERSION,
                    SLSLimboInstaller.NANOLIMBO_COMMIT.substring(0, 8),
                    config.memoryMiB()
            );
        } catch (Exception exception) {
            fail(exception);
        }
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
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        status = LobbyStatus.SHUTTING_DOWN;
        unregister();
        SupervisedProcess current = process;
        if (current == null) {
            releaseResources();
        } else {
            try {
                current.stop();
            } catch (IllegalStateException exception) {
                current.forceStop();
            }
        }
        if (!ready.isDone()) {
            ready.completeExceptionally(
                    new IllegalStateException("SLS-Limbo is shutting down")
            );
        }
    }

    private synchronized void publishReady() {
        if (closed) {
            return;
        }
        try {
            backends.register(
                    SERVER_NAME,
                    new InetSocketAddress("127.0.0.1", port)
            );
            registered = true;
            RegisteredServer registeredServer = proxy.getServer(SERVER_NAME)
                    .orElseThrow(() -> new IllegalStateException(
                            "SLS-Limbo was not registered with Velocity"
                    ));
            status = LobbyStatus.READY;
            ready.complete(registeredServer);
            logger.info("SLS-Limbo is ready on 127.0.0.1:{}", port);
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    private synchronized void fail(Throwable failure) {
        if (status == LobbyStatus.OFFLINE && ready.isCompletedExceptionally()) {
            return;
        }
        status = LobbyStatus.OFFLINE;
        unregister();
        SupervisedProcess current = process;
        boolean awaitingExit = false;
        if (current != null
                && current.state() != InstanceState.STOPPED
                && current.state() != InstanceState.STOPPING) {
            current.forceStop();
            awaitingExit = true;
        } else if (current != null && current.state() == InstanceState.STOPPING) {
            awaitingExit = true;
        }
        if (!awaitingExit) {
            releaseResources();
        }
        ready.completeExceptionally(failure);
        logger.error("SLS-Limbo failed: {}", rootMessage(failure));
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
        if (port >= 0) {
            ports.release(port);
        }
        resourceBudget.release(SERVER_NAME);
    }

    private ProcessSpec processSpec(
            SLSLimboInstaller.SLSLimboInstallation installation
    ) {
        return new ProcessSpec(
                java.util.List.of(
                        javaExecutable(),
                        "-Xms32M",
                        "-Xmx" + config.memoryMiB() + "M",
                        "-jar",
                        installation.runtimeJar().toString()
                ),
                installation.workingDirectory(),
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
}
