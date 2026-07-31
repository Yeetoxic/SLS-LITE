package net.slimelabs.slslite.instance.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SnapshotHookClient {

  public static final String PROTOCOL = "sls-snapshot-helper-v1";
  private static final String SUCCESS = PROTOCOL + " ok";
  private static final int MAX_OUTPUT_BYTES = 8_192;

  private final Path executable;
  private final int timeoutSeconds;

  public SnapshotHookClient(Path executable, int timeoutSeconds) {
    this.executable =
        java.util.Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    if (timeoutSeconds < 1 || timeoutSeconds > 300) {
      throw new IllegalArgumentException(
          "Snapshot helper timeout must be between 1 and 300 seconds");
    }
    this.timeoutSeconds = timeoutSeconds;
  }

  public void probe(Path instancesRoot) throws IOException {
    invoke("probe", "--instances-root", normalized(instancesRoot).toString());
  }

  void prepare(Path source, Path target) throws IOException {
    invokeLayer("prepare", source, target);
  }

  void suspend(Path source, Path target) throws IOException {
    invokeLayer("suspend", source, target);
  }

  void resume(Path source, Path target) throws IOException {
    invokeLayer("resume", source, target);
  }

  void delete(Path source, Path target) throws IOException {
    invokeLayer("delete", source, target);
  }

  private void invokeLayer(String operation, Path source, Path target) throws IOException {
    invoke(
        operation,
        "--source",
        normalized(source).toString(),
        "--target",
        normalized(target).toString());
  }

  private void invoke(String operation, String... arguments) throws IOException {
    validateExecutable();
    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.add("--protocol");
    command.add(PROTOCOL);
    command.add(operation);
    command.addAll(List.of(arguments));

    Process process = null;
    Thread outputReader = null;
    Thread errorReader = null;
    AtomicReference<Capture> output = new AtomicReference<>(new Capture("", false));
    AtomicReference<Capture> error = new AtomicReference<>(new Capture("", false));
    try {
      process = new ProcessBuilder(command).start();
      Process started = process;
      outputReader = Thread.ofVirtual().start(() -> output.set(capture(started.getInputStream())));
      errorReader = Thread.ofVirtual().start(() -> error.set(capture(started.getErrorStream())));
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException(
            "Snapshot helper " + operation + " timed out after " + timeoutSeconds + " seconds");
      }
      outputReader.join(1_000);
      errorReader.join(1_000);
      Capture stdout = output.get();
      Capture stderr = error.get();
      if (stdout.truncated() || stderr.truncated()) {
        throw new IOException(
            "Snapshot helper " + operation + " exceeded the bounded output limit");
      }
      if (process.exitValue() != 0) {
        throw new IOException(
            "Snapshot helper "
                + operation
                + " exited with code "
                + process.exitValue()
                + detail(stderr.value()));
      }
      if (!SUCCESS.equals(stdout.value().strip())) {
        throw new IOException(
            "Snapshot helper " + operation + " returned a malformed protocol response");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Snapshot helper " + operation + " was interrupted", exception);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      if (outputReader != null && outputReader.isAlive()) {
        outputReader.interrupt();
      }
      if (errorReader != null && errorReader.isAlive()) {
        errorReader.interrupt();
      }
    }
  }

  private void validateExecutable() throws IOException {
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
        || !Files.isExecutable(executable)) {
      throw new IOException(
          "Snapshot helper is missing, symbolic, or not executable: " + executable);
    }
  }

  private static Capture capture(InputStream input) {
    ByteArrayOutputStream retained = new ByteArrayOutputStream();
    byte[] buffer = new byte[1_024];
    boolean truncated = false;
    try (input) {
      int read;
      while ((read = input.read(buffer)) >= 0) {
        int keep = Math.min(read, MAX_OUTPUT_BYTES - retained.size());
        if (keep > 0) {
          retained.write(buffer, 0, keep);
        }
        truncated |= keep < read;
      }
    } catch (IOException ignored) {
      // Exit status and retained diagnostics remain authoritative.
    }
    return new Capture(retained.toString(StandardCharsets.UTF_8), truncated);
  }

  private static String detail(String value) {
    String sanitized = value == null ? "" : value.replaceAll("\\s+", " ").strip();
    return sanitized.isBlank() ? "" : ": " + sanitized;
  }

  private static Path normalized(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private record Capture(String value, boolean truncated) {}
}
