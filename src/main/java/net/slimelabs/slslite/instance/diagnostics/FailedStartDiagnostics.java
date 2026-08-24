package net.slimelabs.slslite.instance.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.log.DiagnosticRedactor;

public final class FailedStartDiagnostics {

  static final int MAX_REPORTS = 20;
  static final int MAX_OUTPUT_LINES = 200;
  static final int MAX_REPORT_BYTES = 256 * 1024;
  private static final String OMITTED_OUTPUT = "[SLS-LITE] ... %d retained lines omitted ...";

  private final Path root;
  private final Path confinementRoot;
  private final DiagnosticRedactor redactor;

  public FailedStartDiagnostics(Path root) {
    this(
        root.getParent() == null ? root : root.getParent(),
        root,
        new DiagnosticRedactor(root, root, true));
  }

  public FailedStartDiagnostics(Path confinementRoot, Path root, DiagnosticRedactor redactor) {
    this.root = root.toAbsolutePath().normalize();
    this.confinementRoot = confinementRoot.toAbsolutePath().normalize();
    this.redactor = java.util.Objects.requireNonNull(redactor, "redactor");
    if (!this.root.startsWith(this.confinementRoot)) {
      throw new IllegalArgumentException("Failed-start diagnostics path escapes confinement root");
    }
  }

  public Path record(ManagedInstance instance, FailurePhase phase, Throwable failure)
      throws IOException {
    ensureConfinedDirectories();
    String fileName =
        instance.id()
            + "-"
            + System.currentTimeMillis()
            + "-"
            + Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36)
            + ".log";
    Path report = root.resolve(fileName);
    Path temporary = Files.createTempFile(root, "." + instance.id() + "-", ".tmp");
    List<String> output = diagnosticOutput(instance);

    StringBuilder content = new StringBuilder();
    append(content, "recorded_at", Instant.now().toString());
    append(content, "instance", instance.id());
    append(content, "blueprint", instance.blueprint().type() + "/" + instance.blueprint().id());
    append(
        content,
        "software",
        instance.blueprint().software() + " " + instance.blueprint().version());
    append(content, "state", instance.state().name());
    append(content, "phase", phase.id());
    append(content, "failure", redactor.redact(describe(failure)));
    content
        .append(System.lineSeparator())
        .append("--- last output ---")
        .append(System.lineSeparator());
    if (output.isEmpty()) {
      content.append("(no managed process output)").append(System.lineSeparator());
    } else {
      output.forEach(line -> content.append(redactor.redact(line)).append(System.lineSeparator()));
    }

    byte[] encoded = content.toString().getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_REPORT_BYTES) {
      encoded = java.util.Arrays.copyOf(encoded, MAX_REPORT_BYTES);
    }
    Files.write(
        temporary,
        encoded,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS);
    try {
      Files.move(
          temporary, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
      Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    prune();
    return report;
  }

  private static List<String> diagnosticOutput(ManagedInstance instance) {
    int retained = instance.retainedLogLines();
    if (retained == 0) {
      return List.of();
    }
    List<String> output = instance.logs(1, retained).lines();
    if (output.size() <= MAX_OUTPUT_LINES) {
      return output;
    }
    int leading = MAX_OUTPUT_LINES / 2;
    int trailing = MAX_OUTPUT_LINES - leading;
    java.util.ArrayList<String> selected = new java.util.ArrayList<>(MAX_OUTPUT_LINES + 1);
    selected.addAll(output.subList(0, leading));
    selected.add(OMITTED_OUTPUT.formatted(output.size() - leading - trailing));
    selected.addAll(output.subList(output.size() - trailing, output.size()));
    return List.copyOf(selected);
  }

  private void prune() throws IOException {
    try (var reports = Files.list(root)) {
      List<Path> ordered =
          reports
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .filter(path -> path.getFileName().toString().endsWith(".log"))
              .sorted(Comparator.comparingLong(FailedStartDiagnostics::lastModified).reversed())
              .toList();
      for (int index = MAX_REPORTS; index < ordered.size(); index++) {
        Files.deleteIfExists(ordered.get(index));
      }
    }
  }

  private void ensureConfinedDirectories() throws IOException {
    if (!Files.isDirectory(confinementRoot, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(confinementRoot)) {
      throw new IOException("Diagnostics confinement root is not a regular directory");
    }
    Path current = confinementRoot;
    for (Path segment : confinementRoot.relativize(root)) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(current)
            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Diagnostics path contains an unsafe entry: " + segment);
        }
      } else {
        Files.createDirectory(current);
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
