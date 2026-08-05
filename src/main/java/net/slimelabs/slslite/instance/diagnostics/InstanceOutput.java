package net.slimelabs.slslite.instance.diagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.slimelabs.slslite.config.ManagedOutputConfig;

public final class InstanceOutput {

  public static final String TEMPORARY_RELATIVE_PATH = TemporaryInstanceLog.RELATIVE_PATH;

  private final Path instanceDirectory;
  private final InstanceLogBuffer logs;

  private volatile ManagedOutputConfig config = new ManagedOutputConfig(false, false, 4096);
  private volatile TemporaryInstanceLog temporaryLog;
  private final AtomicReference<IOException> failure = new AtomicReference<>();
  private volatile boolean temporaryOutputDisabled;

  public InstanceOutput(Path instanceDirectory) {
    this(instanceDirectory, InstanceLogBuffer.DEFAULT_CAPACITY);
  }

  public InstanceOutput(Path instanceDirectory, int consoleTailLines) {
    this.instanceDirectory = instanceDirectory.toAbsolutePath().normalize();
    this.logs = new InstanceLogBuffer(consoleTailLines);
  }

  public void configure(ManagedOutputConfig nextConfig) throws IOException {
    config = java.util.Objects.requireNonNull(nextConfig, "nextConfig");
    if (nextConfig.writeTemporaryFile()) {
      temporaryLog = new TemporaryInstanceLog(instanceDirectory, nextConfig.temporaryFileMaxKiB());
    }
  }

  public void append(String line) {
    logs.append(line);
    TemporaryInstanceLog current = temporaryLog;
    if (current != null && !temporaryOutputDisabled) {
      try {
        current.append(line);
      } catch (IOException exception) {
        temporaryOutputDisabled = true;
        recordFailure(exception);
      }
    }
  }

  public InstanceLogPage page(int page, int linesPerPage) {
    return logs.page(page, linesPerPage);
  }

  public int retainedLines() {
    return logs.size();
  }

  public long cursor() {
    return logs.cursor();
  }

  public InstanceOutputBatch awaitAfter(
      long cursor, int maximumLines, Duration quietPeriod, Duration timeout) {
    return logs.awaitAfter(cursor, maximumLines, quietPeriod, timeout);
  }

  public int retentionCapacity() {
    return logs.capacity();
  }

  public boolean mirrorsToProxyConsole() {
    return config.mirrorToProxyConsole();
  }

  public boolean writesTemporaryFile() {
    return config.writeTemporaryFile();
  }

  public Optional<Path> temporaryFilePath() {
    return writesTemporaryFile()
        ? Optional.of(instanceDirectory.resolve(TEMPORARY_RELATIVE_PATH))
        : Optional.empty();
  }

  public Optional<IOException> takeFailure() {
    return Optional.ofNullable(failure.getAndSet(null));
  }

  public void close() {
    TemporaryInstanceLog current = temporaryLog;
    temporaryLog = null;
    if (current != null) {
      try {
        current.close();
      } catch (IOException exception) {
        recordFailure(exception);
      }
    }
  }

  void recordFailure(IOException exception) {
    failure.compareAndSet(null, exception);
  }
}
