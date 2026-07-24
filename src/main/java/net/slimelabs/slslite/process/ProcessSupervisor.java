package net.slimelabs.slslite.process;

import net.slimelabs.slslite.instance.InstanceLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ProcessSupervisor implements AutoCloseable {

    private final int maximumProcesses;
    private final ThreadPoolExecutor outputExecutor;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<String, SupervisedProcess> processes = new java.util.HashMap<>();
    private boolean closed;

    public ProcessSupervisor(int maximumProcesses) {
        if (maximumProcesses <= 0) {
            throw new IllegalArgumentException("maximumProcesses must be positive");
        }
        this.maximumProcesses = maximumProcesses;
        this.outputExecutor = new ThreadPoolExecutor(
                maximumProcesses,
                maximumProcesses,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maximumProcesses),
                threadFactory("sls-lite-output-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.scheduler = new ScheduledThreadPoolExecutor(
                1,
                threadFactory("sls-lite-deadline-")
        );
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public synchronized SupervisedProcess start(
            String instanceId,
            ProcessSpec spec,
            InstanceLifecycle lifecycle,
            Consumer<String> outputConsumer
    ) throws ProcessStartException {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(outputConsumer, "outputConsumer");
        if (closed) {
            throw new ProcessStartException("Process supervisor is shut down");
        }
        if (!instanceId.equals(lifecycle.instanceId())) {
            throw new ProcessStartException(
                    "Lifecycle ID does not match process ID: " + lifecycle.instanceId()
            );
        }
        if (processes.containsKey(instanceId)) {
            throw new ProcessStartException("Instance process already exists: " + instanceId);
        }
        if (processes.size() >= maximumProcesses) {
            throw new ProcessStartException(
                    "Maximum managed process count reached: " + maximumProcesses
            );
        }

        SupervisedProcess supervised = new SupervisedProcess(
                this,
                instanceId,
                spec,
                lifecycle,
                outputConsumer
        );
        processes.put(instanceId, supervised);
        try {
            supervised.start();
            return supervised;
        } catch (ProcessStartException exception) {
            processes.remove(instanceId);
            throw exception;
        }
    }

    public synchronized Collection<SupervisedProcess> activeProcesses() {
        return List.copyOf(processes.values());
    }

    public void shutdown(Duration timeout) {
        List<SupervisedProcess> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = new ArrayList<>(processes.values());
        }

        List<CompletableFuture<Integer>> exits = new ArrayList<>();
        for (SupervisedProcess process : snapshot) {
            try {
                exits.add(process.stop());
            } catch (IllegalStateException exception) {
                process.forceStop();
                exits.add(process.exitFuture());
            }
        }

        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            snapshot.forEach(SupervisedProcess::forceStop);
            try {
                CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The processes have already received forced termination.
            }
        } finally {
            outputExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(30));
    }

    void executeOutputReader(Runnable reader) {
        outputExecutor.execute(reader);
    }

    ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return scheduler.schedule(task, delay, unit);
    }

    synchronized void processExited(String instanceId, SupervisedProcess process) {
        processes.remove(instanceId, process);
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
