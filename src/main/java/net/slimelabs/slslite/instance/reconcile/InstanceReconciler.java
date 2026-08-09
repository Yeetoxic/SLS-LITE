package net.slimelabs.slslite.instance.reconcile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataStore;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.io.ConfinedFiles;
import net.slimelabs.slslite.log.DiagnosticRedactor;
import net.slimelabs.slslite.log.SLSDetailLog;
import org.slf4j.Logger;

public final class InstanceReconciler {

  private static final long PROCESS_STOP_TIMEOUT_SECONDS = 5;

  private final InstanceDirectoryPreparer directoryPreparer;
  private final InstanceMetadataStore metadataStore;
  private final Logger logger;
  private final SLSDetailLog detailLog;
  private final String correlationId;
  private final DiagnosticRedactor redactor;

  public InstanceReconciler(InstanceDirectoryPreparer directoryPreparer, Logger logger) {
    this(directoryPreparer, logger, SLSDetailLog.disabled(), "startup");
  }

  public InstanceReconciler(
      InstanceDirectoryPreparer directoryPreparer,
      Logger logger,
      SLSDetailLog detailLog,
      String correlationId) {
    this.directoryPreparer = directoryPreparer;
    this.metadataStore = new InstanceMetadataStore(directoryPreparer.root());
    this.logger = logger;
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
    this.correlationId = java.util.Objects.requireNonNull(correlationId, "correlationId");
    this.redactor = new DiagnosticRedactor(directoryPreparer.root(), null, true);
  }

  public InstanceReconciliationReport reconcile() throws IOException {
    ConfinedFiles.ensureDirectory(directoryPreparer.root());
    int recoveredTransactions =
        directoryPreparer.recoverInterruptedReplacements(
            (directory, instanceId) ->
                metadataStore
                    .read(directory)
                    .filter(metadata -> metadata.instanceId().equals(instanceId))
                    .filter(InstanceMetadata::persistent)
                    .isPresent());
    if (recoveredTransactions > 0) {
      logger.warn(
          "Recovered {} interrupted persistent storage transaction(s)", recoveredTransactions);
    }
    List<Path> directories;
    try (var entries = Files.list(directoryPreparer.root())) {
      directories =
          entries
              .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .toList();
    }

    MutableReport report = new MutableReport();
    report.recoveredStorageTransactions = recoveredTransactions;
    for (Path directory : directories) {
      reconcile(directory, report);
    }
    return report.snapshot();
  }

  private void reconcile(Path directory, MutableReport report) {
    Optional<InstanceMetadata> loaded;
    try {
      loaded = metadataStore.read(directory);
    } catch (IOException exception) {
      report.preservedUnknown++;
      logger.warn(
          "Preserving instance directory with unreadable metadata {}: {}",
          directory.getFileName(),
          redactor.redact(exception.getMessage()));
      return;
    }
    if (loaded.isEmpty()) {
      report.preservedUnknown++;
      logger.warn(
          "Preserving unrecognized instance directory without SLS-LITE metadata: {}",
          directory.getFileName());
      return;
    }

    InstanceMetadata metadata = loaded.orElseThrow();
    if (!directory.getFileName().toString().equals(metadata.instanceId())) {
      report.preservedUnknown++;
      logger.warn(
          "Preserving instance directory {} because metadata identifies it as {}",
          directory.getFileName(),
          metadata.instanceId());
      return;
    }
    Optional<ProcessHandle> verifiedProcess = verifiedRecordedProcess(metadata);
    if (verifiedProcess.isPresent()) {
      try {
        terminate(verifiedProcess.orElseThrow(), metadata.instanceId());
        logger.warn(
            "Stopped orphaned managed process for instance {} during reconciliation",
            metadata.instanceId());
      } catch (Exception exception) {
        report.failures++;
        logger.error(
            "Unable to stop orphaned managed process for {}: {}",
            metadata.instanceId(),
            redactor.redact(exception.getMessage()));
        return;
      }
    } else if (hasAmbiguousLiveProcess(metadata)) {
      report.preservedRunning++;
      logger.warn(
          "Preserving instance {} because its live process identity " + "cannot be verified safely",
          metadata.instanceId());
      return;
    }
    if (metadata.persistent()) {
      try {
        directoryPreparer.publishPersistentFiles(metadata.instanceId());
        directoryPreparer.suspend(metadata.instanceId());
        metadataStore.write(directory, metadata.withoutProcess(InstanceState.STOPPED));
        report.preservedPersistent++;
        detailLog.normal(
            correlationId,
            "reconciliation",
            "Preserving persistent instance {} from blueprint {}",
            metadata.instanceId(),
            metadata.blueprintId());
      } catch (IOException | InstancePreparationException exception) {
        report.failures++;
        logger.error(
            "Unable to normalize persistent instance {}: {}",
            metadata.instanceId(),
            redactor.redact(exception.getMessage()));
      }
      return;
    }
    if (metadata.processId() == null && !isSafeProcesslessEphemeralState(metadata.state())) {
      report.preservedUnknown++;
      logger.warn(
          "Preserving ambiguous ephemeral instance {} in state {} without process identity",
          metadata.instanceId(),
          metadata.state());
      return;
    }

    try {
      directoryPreparer.publishPersistentFiles(metadata.instanceId());
      directoryPreparer.delete(metadata.instanceId());
      report.removedEphemeral++;
      detailLog.normal(
          correlationId,
          "reconciliation",
          "Removed stale ephemeral instance {}",
          metadata.instanceId());
    } catch (InstancePreparationException exception) {
      report.failures++;
      logger.error(
          "Unable to remove stale ephemeral instance {}: {}",
          metadata.instanceId(),
          redactor.redact(exception.getMessage()));
    }
  }

  private static boolean isSafeProcesslessEphemeralState(InstanceState state) {
    return state == InstanceState.PREPARING
        || state == InstanceState.STOPPING
        || state == InstanceState.STOPPED
        || state == InstanceState.FAILED;
  }

  private static Optional<ProcessHandle> verifiedRecordedProcess(InstanceMetadata metadata) {
    if (metadata.processId() == null || metadata.processStartedAt() == null) {
      return Optional.empty();
    }
    return ProcessHandle.of(metadata.processId())
        .filter(ProcessHandle::isAlive)
        .filter(
            handle ->
                handle
                    .info()
                    .startInstant()
                    .map(metadata.processStartedAt()::equals)
                    .orElse(false));
  }

  private static boolean hasAmbiguousLiveProcess(InstanceMetadata metadata) {
    if (metadata.processId() == null) {
      return false;
    }
    Optional<ProcessHandle> process = ProcessHandle.of(metadata.processId());
    if (process.isEmpty() || !process.orElseThrow().isAlive()) {
      return false;
    }
    Optional<Instant> actualStart = process.orElseThrow().info().startInstant();
    return metadata.processStartedAt() == null || actualStart.isEmpty();
  }

  private static void terminate(ProcessHandle process, String instanceId) throws Exception {
    if (process.pid() == ProcessHandle.current().pid()) {
      throw new IllegalStateException(
          "Refusing to terminate the current process for " + instanceId);
    }
    process.destroy();
    try {
      process.onExit().get(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return;
    } catch (TimeoutException ignored) {
      process.destroyForcibly();
    }
    process.onExit().get(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static final class MutableReport {

    private int recoveredStorageTransactions;
    private int removedEphemeral;
    private int preservedPersistent;
    private int preservedRunning;
    private int preservedUnknown;
    private int failures;

    private InstanceReconciliationReport snapshot() {
      return new InstanceReconciliationReport(
          recoveredStorageTransactions,
          removedEphemeral,
          preservedPersistent,
          preservedRunning,
          preservedUnknown,
          failures);
    }
  }
}
