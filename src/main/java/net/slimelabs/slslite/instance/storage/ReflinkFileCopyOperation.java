package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ReflinkFileCopyOperation implements InstanceDirectoryPreparer.FileCopyOperation {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReflinkFileCopyOperation.class);
  private static final long COMMAND_TIMEOUT_SECONDS = 30;

  private final boolean portableFallbackAllowed;
  private final CloneOperation cloneOperation;
  private final AtomicBoolean fallbackReported = new AtomicBoolean();

  ReflinkFileCopyOperation(boolean portableFallbackAllowed) {
    this(portableFallbackAllowed, new CommandCloneOperation());
  }

  ReflinkFileCopyOperation(boolean portableFallbackAllowed, CloneOperation cloneOperation) {
    this.portableFallbackAllowed = portableFallbackAllowed;
    this.cloneOperation = java.util.Objects.requireNonNull(cloneOperation, "cloneOperation");
  }

  @Override
  public void copy(Path source, Path target) throws IOException {
    if (Files.exists(target)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    try {
      cloneOperation.clone(source, target);
      if (!Files.isRegularFile(target)) {
        throw new IOException("Reflink operation did not create a regular file: " + target);
      }
    } catch (IOException exception) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
        throw exception;
      }
      if (!portableFallbackAllowed) {
        throw new IOException(
            "Required reflink clone failed for " + source + ": " + message(exception), exception);
      }
      if (fallbackReported.compareAndSet(false, true)) {
        LOGGER.info(
            "A source rejected reflink cloning; storage.strategy=auto "
                + "is using transactional portable copy for that "
                + "source and suppressing further fallback notices");
      }
      new PortableFileCopyOperation().copy(source, target);
    }
  }

  private static String message(Throwable throwable) {
    return throwable.getMessage() == null
        ? throwable.getClass().getSimpleName()
        : throwable.getMessage();
  }

  @FunctionalInterface
  interface CloneOperation {

    void clone(Path source, Path target) throws IOException;
  }

  private static final class CommandCloneOperation implements CloneOperation {

    @Override
    public void clone(Path source, Path target) throws IOException {
      Process process = null;
      try {
        process =
            new ProcessBuilder("cp", "--reflink=always", "--", source.toString(), target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new IOException("cp --reflink=always timed out");
        }
        if (process.exitValue() != 0) {
          throw new IOException("cp --reflink=always exited with code " + process.exitValue());
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("cp --reflink=always was interrupted", exception);
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
        }
      }
    }
  }
}
