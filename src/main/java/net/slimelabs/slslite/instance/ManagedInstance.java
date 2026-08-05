package net.slimelabs.slslite.instance;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.instance.diagnostics.InstanceLogPage;
import net.slimelabs.slslite.instance.diagnostics.InstanceOutput;
import net.slimelabs.slslite.instance.diagnostics.InstanceOutputBatch;
import net.slimelabs.slslite.instance.diagnostics.ProcessResourceMetrics;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.log.CorrelationIds;
import net.slimelabs.slslite.process.SupervisedProcess;

public final class ManagedInstance {

  private final String id;
  private final Blueprint blueprint;
  private final InstanceDefinitionIdentity definitionIdentity;
  private final InstanceLaunchOverrides launchOverrides;
  private final int port;
  private final Path directory;
  private final InstanceLifecycle lifecycle;
  private final Instant createdAt;
  private final CompletableFuture<ManagedInstance> ready = new CompletableFuture<>();
  private final CompletableFuture<Integer> stopped = new CompletableFuture<>();
  private final InstanceOutput output;
  private final InstancePhaseTimings timings = new InstancePhaseTimings();
  private final String correlationId = CorrelationIds.next("instance");

  private volatile SupervisedProcess process;
  private volatile boolean registered;
  private volatile boolean stopRequested;
  private volatile boolean preparationRunning;
  private boolean failureDiagnosticsRecorded;

  ManagedInstance(
      String id, Blueprint blueprint, int port, Path directory, InstanceLifecycle lifecycle) {
    this(id, blueprint, null, port, directory, lifecycle, Instant.now());
  }

  ManagedInstance(
      String id,
      Blueprint blueprint,
      InstanceDefinitionIdentity definitionIdentity,
      int port,
      Path directory,
      InstanceLifecycle lifecycle,
      Instant createdAt) {
    this(
        id,
        blueprint,
        definitionIdentity,
        InstanceLaunchOverrides.NONE,
        port,
        directory,
        lifecycle,
        createdAt);
  }

  ManagedInstance(
      String id,
      Blueprint blueprint,
      InstanceDefinitionIdentity definitionIdentity,
      InstanceLaunchOverrides launchOverrides,
      int port,
      Path directory,
      InstanceLifecycle lifecycle,
      Instant createdAt,
      int consoleTailLines) {
    this.id = id;
    this.blueprint = blueprint;
    this.definitionIdentity = definitionIdentity;
    this.launchOverrides = java.util.Objects.requireNonNull(launchOverrides, "launchOverrides");
    this.port = port;
    this.directory = directory;
    this.output = new InstanceOutput(directory, consoleTailLines);
    this.lifecycle = lifecycle;
    this.createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
  }

  ManagedInstance(
      String id,
      Blueprint blueprint,
      InstanceDefinitionIdentity definitionIdentity,
      InstanceLaunchOverrides launchOverrides,
      int port,
      Path directory,
      InstanceLifecycle lifecycle,
      Instant createdAt) {
    this(
        id,
        blueprint,
        definitionIdentity,
        launchOverrides,
        port,
        directory,
        lifecycle,
        createdAt,
        net.slimelabs.slslite.config.DiagnosticRetentionConfig.defaults().consoleTailLines());
  }

  ManagedInstance(
      String id,
      Blueprint blueprint,
      int port,
      Path directory,
      InstanceLifecycle lifecycle,
      Instant createdAt) {
    this(id, blueprint, null, port, directory, lifecycle, createdAt);
  }

  public String id() {
    return id;
  }

  public Blueprint blueprint() {
    return blueprint;
  }

  public InstanceDefinitionIdentity definitionIdentity() {
    return definitionIdentity;
  }

  public InstanceLaunchOverrides launchOverrides() {
    return launchOverrides;
  }

  public int port() {
    return port;
  }

  public Path directory() {
    return directory;
  }

  public InstanceState state() {
    return lifecycle.state();
  }

  public Instant createdAt() {
    return createdAt;
  }

  public String correlationId() {
    return correlationId;
  }

  public CompletableFuture<ManagedInstance> readyFuture() {
    return ready;
  }

  public CompletableFuture<Integer> stoppedFuture() {
    return stopped;
  }

  public InstanceLogPage logs(int page, int linesPerPage) {
    return output.page(page, linesPerPage);
  }

  public int retainedLogLines() {
    return output.retainedLines();
  }

  public long outputCursor() {
    return output.cursor();
  }

  public InstanceOutputBatch awaitOutputAfter(
      long cursor, int maximumLines, Duration quietPeriod, Duration timeout) {
    return output.awaitAfter(cursor, maximumLines, quietPeriod, timeout);
  }

  public int logRetentionCapacity() {
    return output.retentionCapacity();
  }

  public boolean mirrorsOutputToProxyConsole() {
    return output.mirrorsToProxyConsole();
  }

  public boolean writesTemporaryLog() {
    return output.writesTemporaryFile();
  }

  public Optional<Path> temporaryLogPath() {
    return output.temporaryFilePath();
  }

  public OptionalLong processId() {
    SupervisedProcess current = process;
    if (current == null) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(current.processId());
    } catch (IllegalStateException exception) {
      return OptionalLong.empty();
    }
  }

  public Optional<Instant> processStartedAt() {
    SupervisedProcess current = process;
    return current == null ? Optional.empty() : current.processStartedAt();
  }

  public Optional<Duration> processCpuTime() {
    OptionalLong id = processId();
    if (id.isEmpty()) {
      return Optional.empty();
    }
    return ProcessHandle.of(id.getAsLong()).flatMap(handle -> handle.info().totalCpuDuration());
  }

  public Optional<ProcessResourceSnapshot> processResources() {
    OptionalLong id = processId();
    if (id.isEmpty()) {
      return Optional.empty();
    }
    ProcessHandle handle =
        ProcessHandle.of(id.getAsLong()).filter(ProcessHandle::isAlive).orElse(null);
    if (handle == null
        || processStartedAt()
            .filter(
                started ->
                    handle
                        .info()
                        .startInstant()
                        .map(actual -> !actual.equals(started))
                        .orElse(false))
            .isPresent()) {
      return Optional.empty();
    }
    return ProcessResourceMetrics.inspect(id.getAsLong())
        .map(
            snapshot ->
                new ProcessResourceSnapshot(
                    snapshot.residentBytes(),
                    snapshot.charactersRead(),
                    snapshot.charactersWritten(),
                    snapshot.storageBytesRead(),
                    snapshot.storageBytesWritten()));
  }

  public Optional<Duration> recordFirstPlayerConnected() {
    return timings.firstPlayerConnected().map(Duration::ofNanos);
  }

  void configureOutput(ManagedOutputConfig config) throws IOException {
    output.configure(config);
  }

  void appendLog(String line) {
    output.append(line);
  }

  Optional<IOException> takeOutputFailure() {
    return output.takeFailure();
  }

  void closeOutput() {
    output.close();
  }

  InstanceLifecycle lifecycle() {
    return lifecycle;
  }

  InstancePhaseTimings timings() {
    return timings;
  }

  SupervisedProcess process() {
    return process;
  }

  void attachProcess(SupervisedProcess process) {
    this.process = process;
  }

  boolean registered() {
    return registered;
  }

  void registered(boolean registered) {
    this.registered = registered;
  }

  boolean stopRequested() {
    return stopRequested;
  }

  void requestStop() {
    stopRequested = true;
  }

  boolean preparationRunning() {
    return preparationRunning;
  }

  void preparationStarted() {
    preparationRunning = true;
  }

  void preparationFinished() {
    preparationRunning = false;
  }

  synchronized boolean markFailureDiagnosticsRecorded() {
    if (failureDiagnosticsRecorded) {
      return false;
    }
    failureDiagnosticsRecorded = true;
    return true;
  }

  public record ProcessResourceSnapshot(
      OptionalLong residentBytes,
      OptionalLong charactersRead,
      OptionalLong charactersWritten,
      OptionalLong storageBytesRead,
      OptionalLong storageBytesWritten) {}
}
