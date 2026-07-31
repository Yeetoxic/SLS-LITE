package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BooleanSupplier;

/**
 * Executes bounded, cancellable directory copies with retry and entry-safety
 * guarantees. Transaction and storage-layer ownership remains with the caller.
 */
final class DirectoryCopyEngine {

    private static final long[] RETRY_DELAYS_MILLIS = {
        250,
        750,
        2_000,
        5_000
    };

    private final InstanceDirectoryPreparer.FileCopyOperation fileCopy;
    private final InstanceDirectoryPreparer.RetrySleeper retrySleeper;
    private final int parallelism;

    DirectoryCopyEngine(
            InstanceDirectoryPreparer.FileCopyOperation fileCopy,
            InstanceDirectoryPreparer.RetrySleeper retrySleeper,
            int parallelism
    ) {
        this.fileCopy = java.util.Objects.requireNonNull(fileCopy, "fileCopy");
        this.retrySleeper = java.util.Objects.requireNonNull(
                retrySleeper,
                "retrySleeper"
        );
        if (parallelism < 1) {
            throw new IllegalArgumentException(
                    "Copy parallelism must be positive"
            );
        }
        this.parallelism = parallelism;
    }

    void validateSource(
            Path source,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(directory, attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(file, attrs);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void copyDirectory(
            Path source,
            Path destination,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        if (parallelism == 1) {
            copyDirectorySequentially(
                    source,
                    destination,
                    cancellationRequested
            );
            return;
        }
        int maximumInFlight = parallelism * 2;
        try (BoundedCopyBatch batch =
                     new BoundedCopyBatch(parallelism, maximumInFlight)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attrs
                ) throws IOException {
                    checkCancelled(cancellationRequested);
                    rejectSymbolicLink(directory, attrs);
                    Path relative = source.relativize(directory);
                    Files.createDirectories(destination.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attrs
                ) throws IOException {
                    checkCancelled(cancellationRequested);
                    rejectSymbolicLink(file, attrs);
                    Path target = destination.resolve(
                            source.relativize(file)
                    );
                    batch.submit(() -> copyFileWithRetry(
                            file,
                            target,
                            cancellationRequested
                    ));
                    return FileVisitResult.CONTINUE;
                }
            });
            batch.complete();
        }
    }

    void copyDirectoryReplacing(
            Path source,
            Path destination,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(directory, attrs);
                Path target = destination.resolve(source.relativize(directory));
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                            "Copy directory target is not a directory: " + target
                    );
                }
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(file, attrs);
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                            "Copy file target is a directory: " + target
                    );
                }
                Files.deleteIfExists(target);
                copyFileWithRetry(file, target, cancellationRequested);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void mergeDirectoryFirstWins(
            Path source,
            Path destination,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(directory, attrs);
                Path target = destination.resolve(source.relativize(directory));
                if (Files.exists(target) && !Files.isDirectory(target)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(file, attrs);
                Path target = destination.resolve(source.relativize(file));
                if (!Files.exists(target)) {
                    copyFileWithRetry(file, target, cancellationRequested);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void copyFile(
            Path source,
            Path target,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        copyFileWithRetry(source, target, cancellationRequested);
    }

    private void copyDirectorySequentially(
            Path source,
            Path destination,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(directory, attrs);
                Path relative = source.relativize(directory);
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(file, attrs);
                Path target = destination.resolve(source.relativize(file));
                copyFileWithRetry(file, target, cancellationRequested);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyFileWithRetry(
            Path source,
            Path target,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= RETRY_DELAYS_MILLIS.length; attempt++) {
            checkCancelled(cancellationRequested);
            try {
                fileCopy.copy(source, target);
                checkCancelled(cancellationRequested);
                return;
            } catch (IOException exception) {
                if (exception instanceof CopyCancelledException) {
                    throw exception;
                }
                lastFailure = exception;
                if (attempt == RETRY_DELAYS_MILLIS.length
                        || !isRetryableCopyFailure(exception)) {
                    throw exception;
                }
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                    throw exception;
                }
                try {
                    sleepWithCancellation(
                            RETRY_DELAYS_MILLIS[attempt],
                            cancellationRequested
                    );
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException failure = new IOException(
                            "Interrupted while retrying file copy: " + source,
                            interrupted
                    );
                    failure.addSuppressed(exception);
                    throw failure;
                }
            }
        }
        throw lastFailure;
    }

    private void sleepWithCancellation(
            long milliseconds,
            BooleanSupplier cancellationRequested
    ) throws InterruptedException, CopyCancelledException {
        long remaining = milliseconds;
        while (remaining > 0) {
            checkCancelled(cancellationRequested);
            long slice = Math.min(remaining, 100);
            retrySleeper.sleep(slice);
            remaining -= slice;
        }
        checkCancelled(cancellationRequested);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested)
            throws CopyCancelledException {
        if (cancellationRequested.getAsBoolean()) {
            throw new CopyCancelledException();
        }
    }

    private static boolean isRetryableCopyFailure(IOException exception) {
        return exception instanceof FileSystemException
                && !(exception instanceof FileAlreadyExistsException)
                && !(exception instanceof NoSuchFileException);
    }

    private static void rejectSymbolicLink(
            Path path,
            BasicFileAttributes attrs
    ) throws IOException {
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException(
                    "Symbolic links and special filesystem entries are not allowed "
                            + "in copied directories: " + path
            );
        }
    }

    private static final class CopyCancelledException extends IOException {

        private CopyCancelledException() {
            super("Instance preparation was cancelled");
        }
    }
}
