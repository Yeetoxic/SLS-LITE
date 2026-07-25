package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.process.SupervisedProcess;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
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
    private final InstanceLogBuffer logs = new InstanceLogBuffer();

    private volatile SupervisedProcess process;
    private volatile ManagedOutputConfig outputConfig =
            new ManagedOutputConfig(false, false, 4096);
    private volatile TemporaryInstanceLog temporaryLog;
    private volatile IOException outputFailure;
    private volatile boolean outputDisabled;
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

    public CompletableFuture<Integer> stoppedFuture() {
        return stopped;
    }

    public InstanceLogPage logs(int page, int linesPerPage) {
        return logs.page(page, linesPerPage);
    }

    public int retainedLogLines() {
        return logs.size();
    }

    public int logRetentionCapacity() {
        return InstanceLogBuffer.CAPACITY;
    }

    public boolean mirrorsOutputToProxyConsole() {
        return outputConfig.mirrorToProxyConsole();
    }

    public boolean writesTemporaryLog() {
        return outputConfig.writeTemporaryFile();
    }

    public Optional<Path> temporaryLogPath() {
        return writesTemporaryLog()
                ? Optional.of(directory.resolve(TemporaryInstanceLog.RELATIVE_PATH))
                : Optional.empty();
    }

    public OptionalLong processId() {
        SupervisedProcess current = process;
        if (current == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(current.processId());
        } catch (IllegalStateException exception) {
            return OptionalLong.empty();
        }
    }

    public Optional<Instant> processStartedAt() {
        SupervisedProcess current = process;
        return current == null ? Optional.empty() : current.processStartedAt();
    }

    public Optional<Duration> processCpuTime() {
        OptionalLong id = processId();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return ProcessHandle.of(id.getAsLong())
                .flatMap(handle -> handle.info().totalCpuDuration());
    }

    void configureOutput(ManagedOutputConfig config) throws IOException {
        outputConfig = config;
        if (config.writeTemporaryFile()) {
            temporaryLog = new TemporaryInstanceLog(
                    directory,
                    config.temporaryFileMaxKiB()
            );
        }
    }

    void appendLog(String line) {
        logs.append(line);
        TemporaryInstanceLog current = temporaryLog;
        if (current != null && !outputDisabled) {
            try {
                current.append(line);
            } catch (IOException exception) {
                outputDisabled = true;
                outputFailure = exception;
            }
        }
    }

    Optional<IOException> takeOutputFailure() {
        IOException failure = outputFailure;
        outputFailure = null;
        return Optional.ofNullable(failure);
    }

    void closeOutput() {
        TemporaryInstanceLog current = temporaryLog;
        temporaryLog = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException exception) {
                outputFailure = exception;
            }
        }
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
