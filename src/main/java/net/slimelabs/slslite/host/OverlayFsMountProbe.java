package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

final class OverlayFsMountProbe {

  private static final long COMMAND_TIMEOUT_SECONDS = 5;

  private final MountOperations mountOperations;

  OverlayFsMountProbe() {
    this(new CommandMountOperations());
  }

  OverlayFsMountProbe(MountOperations mountOperations) {
    this.mountOperations = java.util.Objects.requireNonNull(mountOperations, "mountOperations");
  }

  Result probe(Path instancesDirectory) {
    Path probe = null;
    Path lower = null;
    Path upper = null;
    Path merged = null;
    boolean mounted = false;
    boolean unmounted = false;
    Exception failure = null;
    try {
      Files.createDirectories(instancesDirectory);
      probe = Files.createTempDirectory(instancesDirectory, ".sls-overlay-probe-");
      lower = Files.createDirectory(probe.resolve("lower"));
      upper = Files.createDirectory(probe.resolve("upper"));
      Path work = Files.createDirectory(probe.resolve("work"));
      merged = Files.createDirectory(probe.resolve("merged"));
      Files.writeString(lower.resolve("marker"), "source");

      mountOperations.mount(lower, upper, work, merged);
      mounted = true;
      if (!"source".equals(Files.readString(merged.resolve("marker")))) {
        throw new IOException("the merged view did not expose the immutable lower marker");
      }
      Files.writeString(merged.resolve("marker"), "instance");
      Files.writeString(merged.resolve("instance-only"), "private");
      if (!"source".equals(Files.readString(lower.resolve("marker")))) {
        throw new IOException("a write through the merged view modified the lower source");
      }
    } catch (IOException | RuntimeException exception) {
      failure = exception;
    } finally {
      if (mounted && merged != null) {
        try {
          mountOperations.unmount(merged);
          unmounted = true;
        } catch (IOException | RuntimeException exception) {
          if (failure == null) {
            failure = exception;
          } else {
            failure.addSuppressed(exception);
          }
        }
      }
    }

    if (mounted && !unmounted) {
      return new Result(
          false,
          "contained OverlayFS probe could not be unmounted; the probe "
              + "directory was preserved for safe operator recovery: "
              + probe
              + detail(failure));
    }

    if (failure == null) {
      try {
        if (!"instance".equals(Files.readString(upper.resolve("marker")))
            || !"private".equals(Files.readString(upper.resolve("instance-only")))
            || !"source".equals(Files.readString(lower.resolve("marker")))) {
          failure = new IOException("the writable upper layer did not preserve isolated changes");
        }
      } catch (IOException | RuntimeException exception) {
        failure = exception;
      }
    }

    try {
      deleteTree(probe);
    } catch (IOException cleanupFailure) {
      if (failure == null) {
        failure = cleanupFailure;
      } else {
        failure.addSuppressed(cleanupFailure);
      }
    }

    if (failure != null) {
      return new Result(
          false, "contained mount/write-isolation/unmount probe failed" + detail(failure));
    }
    return new Result(
        true,
        "contained mount, lower-source isolation, upper-layer persistence, "
            + "unmount, and cleanup probe passed");
  }

  private static String detail(Throwable throwable) {
    if (throwable == null) {
      return "";
    }
    String message = throwable.getMessage();
    return ": " + (message == null ? throwable.getClass().getSimpleName() : message);
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  record Result(boolean supported, String detail) {

    Result {
      if (detail == null || detail.isBlank()) {
        throw new IllegalArgumentException("OverlayFS probe detail is required");
      }
    }
  }

  interface MountOperations {

    void mount(Path lower, Path upper, Path work, Path merged) throws IOException;

    void unmount(Path merged) throws IOException;
  }

  private static final class CommandMountOperations implements MountOperations {

    @Override
    public void mount(Path lower, Path upper, Path work, Path merged) throws IOException {
      String options =
          "lowerdir="
              + optionPath(lower)
              + ",upperdir="
              + optionPath(upper)
              + ",workdir="
              + optionPath(work);
      run("mount", "-t", "overlay", "overlay", "-o", options, merged.toString());
    }

    @Override
    public void unmount(Path merged) throws IOException {
      run("umount", merged.toString());
    }

    private static String optionPath(Path path) throws IOException {
      String value = path.toAbsolutePath().normalize().toString();
      if (value.indexOf(',') >= 0 || value.indexOf(':') >= 0 || value.indexOf('\\') >= 0) {
        throw new IOException("OverlayFS option paths may not contain ',', ':', or '\\': " + value);
      }
      return value;
    }

    private static void run(String... command) throws IOException {
      Process process = null;
      try {
        process =
            new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new IOException(command[0] + " timed out");
        }
        if (process.exitValue() != 0) {
          throw new IOException(command[0] + " exited with code " + process.exitValue());
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException(command[0] + " was interrupted", exception);
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
        }
      }
    }
  }
}
