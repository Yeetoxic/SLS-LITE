package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounds submitted copy work and always drains started work before returning.
 */
final class BoundedCopyBatch implements AutoCloseable {

    private final ExecutorService executor;
    private final Semaphore capacity;
    private final Phaser tasks = new Phaser(1);
    private final AtomicReference<Throwable> firstFailure =
            new AtomicReference<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean drained = new AtomicBoolean();

    BoundedCopyBatch(int parallelism, int maximumInFlight) {
        if (parallelism < 1 || maximumInFlight < parallelism) {
            throw new IllegalArgumentException(
                    "Copy parallelism must be positive and no greater than "
                            + "the in-flight bound"
            );
        }
        executor = Executors.newFixedThreadPool(
                parallelism,
                Thread.ofPlatform()
                        .name("sls-file-copy-", 0)
                        .daemon(true)
                        .factory()
        );
        capacity = new Semaphore(maximumInFlight);
    }

    void submit(CopyTask task) throws IOException {
        java.util.Objects.requireNonNull(task, "task");
        ensureAccepting();
        acquireCapacity();
        boolean registered = false;
        try {
            throwFailure();
            ensureAccepting();
            tasks.register();
            registered = true;
            executor.execute(() -> {
                try {
                    if (firstFailure.get() == null) {
                        task.run();
                    }
                } catch (Throwable failure) {
                    firstFailure.compareAndSet(null, failure);
                } finally {
                    capacity.release();
                    tasks.arriveAndDeregister();
                }
            });
        } catch (IOException | RuntimeException | Error failure) {
            if (registered) {
                tasks.arriveAndDeregister();
            }
            capacity.release();
            firstFailure.compareAndSet(null, failure);
            throw failure;
        }
    }

    void complete() throws IOException {
        if (!accepting.compareAndSet(true, false)) {
            throw new IllegalStateException("Copy batch is already closed");
        }
        drain();
        throwFailure();
    }

    @Override
    public void close() throws IOException {
        if (drained.get()) {
            return;
        }
        if (accepting.compareAndSet(true, false)) {
            firstFailure.compareAndSet(
                    null,
                    new IOException("Copy batch was aborted before completion")
            );
        }
        drain();
        Throwable failure = firstFailure.get();
        if (failure != null) {
            throw new IOException(
                    "Copy batch cleanup observed a failure",
                    failure
            );
        }
    }

    private void acquireCapacity() throws IOException {
        try {
            capacity.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IOException failure = new IOException(
                    "Interrupted while waiting for bounded copy capacity",
                    exception
            );
            firstFailure.compareAndSet(null, failure);
            throw failure;
        }
    }

    private void drain() throws IOException {
        if (!drained.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        int phase = tasks.arriveAndDeregister();
        boolean interrupted = false;
        while (!tasks.isTerminated()) {
            try {
                tasks.awaitAdvanceInterruptibly(phase);
            } catch (InterruptedException exception) {
                interrupted = true;
                firstFailure.compareAndSet(
                        null,
                        new IOException(
                                "Interrupted while draining copy work",
                                exception
                        )
                );
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureAccepting() {
        if (!accepting.get()) {
            throw new IllegalStateException(
                    "Copy batch no longer accepts work"
            );
        }
    }

    private void throwFailure() throws IOException {
        Throwable failure = firstFailure.get();
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("Parallel copy failed", failure);
    }

    @FunctionalInterface
    interface CopyTask {

        void run() throws Exception;
    }
}
