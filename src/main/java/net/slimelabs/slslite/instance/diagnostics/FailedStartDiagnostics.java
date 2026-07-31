package net.slimelabs.slslite.instance.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import net.slimelabs.slslite.instance.ManagedInstance;

public final class FailedStartDiagnostics {

  static final int MAX_REPORTS = 20;
  static final int MAX_OUTPUT_LINES = 200;
  static final int MAX_REPORT_BYTES = 256 * 1024;

  private final Path root;

  public FailedStartDiagnostics(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path record(ManagedInstance instance, String phase, Throwable failure) throws IOException {
    Files.createDirectories(root);
    String fileName = instance.id() + "-" + System.currentTimeMillis() + ".log";
    Path report = root.resolve(fileName);
    Path temporary = root.resolve("." + fileName + ".tmp");
    List<String> output = instance.logs(1, MAX_OUTPUT_LINES).lines();

    StringBuilder content = new StringBuilder();
    append(content, "recorded_at", Instant.now().toString());
    append(content, "instance", instance.id());
    append(content, "blueprint", instance.blueprint().type() + "/" + instance.blueprint().id());
    append(
        content,
        "software",
        instance.blueprint().software() + " " + instance.blueprint().version());
    append(content, "state", instance.state().name());
    append(content, "phase", phase);
    append(content, "failure", describe(failure));
    content
        .append(System.lineSeparator())
        .append("--- last output ---")
        .append(System.lineSeparator());
    if (output.isEmpty()) {
      content.append("(no managed process output)").append(System.lineSeparator());
    } else {
      output.forEach(line -> content.append(line).append(System.lineSeparator()));
    }

    byte[] encoded = content.toString().getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_REPORT_BYTES) {
      encoded = java.util.Arrays.copyOf(encoded, MAX_REPORT_BYTES);
    }
    Files.write(temporary, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    try {
      Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
      Files.move(temporary, report);
    }
    prune();
    return report;
  }

  private void prune() throws IOException {
    try (var reports = Files.list(root)) {
      List<Path> ordered =
          reports
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".log"))
              .sorted(Comparator.comparingLong(FailedStartDiagnostics::lastModified).reversed())
              .toList();
      for (int index = MAX_REPORTS; index < ordered.size(); index++) {
        Files.deleteIfExists(ordered.get(index));
      }
    }
  }

  private static long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException exception) {
      return Long.MIN_VALUE;
    }
  }

  private static String describe(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return current.getClass().getName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static void append(StringBuilder content, String key, String value) {
    content.append(key).append('=').append(value).append(System.lineSeparator());
  }
}
