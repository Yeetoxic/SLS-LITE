package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.process.SupervisedProcess;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public final class ManagedInstance {

    private final String id;
    private final Blueprint blueprint;
    private final int port;
    private final Path directory;
    private final InstanceLifecycle lifecycle;
    private final Instant createdAt = Instant.now();
    private final CompletableFuture<ManagedInstance> ready = new CompletableFuture<>();
    private final CompletableFuture<Integer> stopped = new CompletableFuture<>();

    private volatile SupervisedProcess process;
    private volatile boolean registered;
    private volatile boolean stopRequested;

    ManagedInstance(
            String id,
            Blueprint blueprint,
            int port,
            Path directory,
            InstanceLifecycle lifecycle
    ) {
        this.id = id;
        this.blueprint = blueprint;
        this.port = port;
        this.directory = directory;
        this.lifecycle = lifecycle;
    }

    public String id() {
        return id;
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    public int port() {
        return port;
    }

    public Path directory() {
        return directory;
    }

    public InstanceState state() {
        return lifecycle.state();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CompletableFuture<ManagedInstance> readyFuture() {
        return ready;
    }

    CompletableFuture<Integer> stoppedFuture() {
        return stopped;
    }

    InstanceLifecycle lifecycle() {
        return lifecycle;
    }

    SupervisedProcess process() {
        return process;
    }

    void attachProcess(SupervisedProcess process) {
        this.process = process;
    }

    boolean registered() {
        return registered;
    }

    void registered(boolean registered) {
        this.registered = registered;
    }

    boolean stopRequested() {
        return stopRequested;
    }

    void requestStop() {
        stopRequested = true;
    }
}
