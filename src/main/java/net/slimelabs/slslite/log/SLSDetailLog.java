package net.slimelabs.slslite.log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.slimelabs.slslite.config.DetailLogLevel;
import net.slimelabs.slslite.config.DetailedLoggingConfig;
import org.slf4j.Logger;

/** Bounded asynchronous rotating detail log, independent from Velocity's console appender. */
public final class SLSDetailLog implements AutoCloseable {

  public static final String RELATIVE_PATH = "logs/sls-lite-detail.log";
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
  private static final int MAX_RECORD_CHARACTERS = 16_384;
  private static final SLSDetailLog DISABLED = new SLSDetailLog();

  private final DetailedLoggingConfig config;
  private final Path dataDirectory;
  private final Path logPath;
  private final ArrayBlockingQueue<byte[]> queue;
  private final DiagnosticRedactor redactor;
  private final Logger console;
  private final AtomicBoolean accepting = new AtomicBoolean();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicReference<IOException> failure = new AtomicReference<>();
  private final Thread worker;

  private SLSDetailLog() {
    this.config = new DetailedLoggingConfig(DetailLogLevel.OFF, false, 64, 1, 128, true);
    this.dataDirectory = null;
    this.logPath = null;
    this.queue = new ArrayBlockingQueue<>(128);
    this.redactor = new DiagnosticRedactor(null, null, true);
    this.console = null;
    this.worker = null;
  }

  public SLSDetailLog(
      Path dataDirectory, Path proxyDirectory, DetailedLoggingConfig config, Logger console)
      throws IOException {
    this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    this.logPath = this.dataDirectory.resolve(RELATIVE_PATH).normalize();
    this.config = java.util.Objects.requireNonNull(config, "config");
    this.console = java.util.Objects.requireNonNull(console, "console");
    this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
    this.redactor =
        new DiagnosticRedactor(this.dataDirectory, proxyDirectory, config.redactPaths());
    if (config.level() == DetailLogLevel.OFF) {
      this.worker = null;
      return;
    }
    preparePath();
    accepting.set(true);
    this.worker = new Thread(this::writeLoop, "sls-lite-detail-log");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  public static SLSDetailLog disabled() {
    return DISABLED;
  }

  public boolean enabled() {
    return config.level() != DetailLogLevel.OFF && accepting.get();
  }

  public void normal(String correlationId, String category, String message, Object... arguments) {
    publish(DetailLogLevel.NORMAL, correlationId, category, message, arguments);
  }

  public void detailed(String correlationId, String category, String message, Object... arguments) {
    publish(DetailLogLevel.DETAILED, correlationId, category, message, arguments);
  }

  public long droppedRecords() {
    return dropped.get();
  }

  public java.util.Optional<IOException> failure() {
    return java.util.Optional.ofNullable(failure.get());
  }

  public Path path() {
    if (logPath == null) {
      throw new IllegalStateException("Detailed logging is disabled");
    }
    return logPath;
  }

  @Override
  public void close() {
    close(Duration.ofSeconds(5));
  }

  public void close(Duration timeout) {
    java.util.Objects.requireNonNull(timeout, "timeout");
    if (worker == null || !accepting.getAndSet(false)) {
      return;
    }
    worker.interrupt();
    try {
      worker.join(Math.max(0L, timeout.toMillis()));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      console.warn("Interrupted while waiting for the SLS-LITE detail log to close");
    }
    if (worker.isAlive()) {
      console.warn(
          "Timed out closing the SLS-LITE detail log; {} queued record(s) were abandoned",
          queue.size());
    }
  }

  private void publish(
      DetailLogLevel required,
      String correlationId,
      String category,
      String message,
      Object... arguments) {
    if (!accepting.get() || config.level().ordinal() < required.ordinal()) {
      return;
    }
    String safeCorrelation = safeToken(correlationId, "none");
    String safeCategory = safeToken(category, "general");
    String rendered = bounded(redactor.redact(format(message, arguments)));
    String line =
        TIMESTAMP.format(Instant.now())
            + " level="
            + required.name().toLowerCase(Locale.ROOT)
            + " correlation="
            + safeCorrelation
            + " category="
            + safeCategory
            + " message="
            + rendered.replace('\r', ' ').replace('\n', ' ')
            + System.lineSeparator();
    if (config.mirrorToProxyConsole()) {
      console.info("[detail {} {}] {}", safeCorrelation, safeCategory, rendered);
    }
    if (!queue.offer(line.getBytes(StandardCharsets.UTF_8))) {
      long count = dropped.incrementAndGet();
      if (count == 1 || (count & (count - 1)) == 0) {
        console.warn("SLS-LITE detail log queue is full; {} record(s) dropped", count);
      }
    }
  }

  private void writeLoop() {
    try (RotatingOutput output = new RotatingOutput()) {
      while (accepting.get() || !queue.isEmpty()) {
        byte[] record;
        try {
          record = queue.poll(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
          continue;
        }
        if (record != null) {
          output.write(record);
        }
      }
    } catch (IOException exception) {
      if (failure.compareAndSet(null, exception)) {
        console.warn("SLS-LITE detailed file logging stopped: {}", exception.getMessage());
      }
      accepting.set(false);
      queue.clear();
    }
  }

  private void preparePath() throws IOException {
    Path parent = logPath.getParent();
    if (!logPath.startsWith(dataDirectory) || parent == null) {
      throw new IOException("Detailed log path escapes the SLS-LITE data directory");
    }
    rejectSymlink(dataDirectory);
    Files.createDirectories(parent);
    rejectSymlink(parent);
    if (Files.exists(logPath, LinkOption.NOFOLLOW_LINKS)) {
      rejectSymlink(logPath);
      if (!Files.isRegularFile(logPath, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Detailed log target is not a regular file");
      }
    }
  }

  private static void rejectSymlink(Path path) throws IOException {
    if (Files.isSymbolicLink(path)) {
      throw new IOException("Detailed log path contains a symbolic link: " + path.getFileName());
    }
  }

  private static String safeToken(String value, String fallback) {
    if (value == null || !value.matches("[A-Za-z0-9._-]{1,96}")) {
      return fallback;
    }
    return value;
  }

  private static String bounded(String value) {
    if (value.length() <= MAX_RECORD_CHARACTERS) {
      return value;
    }
    return value.substring(0, MAX_RECORD_CHARACTERS) + "...[truncated]";
  }

  private static String format(String message, Object... arguments) {
    String template = message == null ? "" : message;
    if (arguments == null || arguments.length == 0) {
      return template;
    }
    StringBuilder result = new StringBuilder(template.length() + arguments.length * 16);
    int cursor = 0;
    int argument = 0;
    while (argument < arguments.length) {
      int placeholder = template.indexOf("{}", cursor);
      if (placeholder < 0) {
        break;
      }
      result.append(template, cursor, placeholder).append(String.valueOf(arguments[argument++]));
      cursor = placeholder + 2;
    }
    result.append(template, cursor, template.length());
    while (argument < arguments.length) {
      result.append(" [").append(String.valueOf(arguments[argument++])).append(']');
    }
    return result.toString();
  }

  private final class RotatingOutput implements AutoCloseable {
    private OutputStream output;
    private long size;

    private RotatingOutput() throws IOException {
      open();
    }

    private void write(byte[] record) throws IOException {
      if (size > 0 && size + record.length > (long) config.maxFileKiB() * 1024L) {
        rotate();
      }
      output.write(record);
      output.flush();
      size += record.length;
    }

    private void rotate() throws IOException {
      closeOutput();
      if (config.retainedFiles() == 1) {
        rejectSymlinkIfPresent(logPath);
        try (OutputStream ignored =
            Files.newOutputStream(
                logPath,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS)) {
          // Truncate the owned active file when retention is exactly one.
        }
        open();
        return;
      }
      for (int index = config.retainedFiles() - 1; index >= 1; index--) {
        Path source = rotated(index - 1);
        Path target = rotated(index);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        rejectSymlink(source);
        rejectSymlinkIfPresent(target);
        moveReplacing(source, target);
      }
      open();
    }

    private void open() throws IOException {
      preparePath();
      output =
          new BufferedOutputStream(
              Files.newOutputStream(
                  logPath,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.APPEND,
                  LinkOption.NOFOLLOW_LINKS));
      size = Files.size(logPath);
    }

    private Path rotated(int index) {
      return index == 0 ? logPath : logPath.resolveSibling(logPath.getFileName() + "." + index);
    }

    private void closeOutput() throws IOException {
      if (output != null) {
        output.close();
        output = null;
      }
    }

    @Override
    public void close() throws IOException {
      closeOutput();
    }
  }

  private static void rejectSymlinkIfPresent(Path path) throws IOException {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      rejectSymlink(path);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Rotated detail log is not a regular file: " + path.getFileName());
      }
    }
  }

  private static void moveReplacing(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
