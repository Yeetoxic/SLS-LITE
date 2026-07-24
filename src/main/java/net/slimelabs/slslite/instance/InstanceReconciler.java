package net.slimelabs.slslite.instance;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class InstanceReconciler {

    private final InstanceDirectoryPreparer directoryPreparer;
    private final InstanceMetadataStore metadataStore;
    private final Logger logger;

    public InstanceReconciler(
            InstanceDirectoryPreparer directoryPreparer,
            Logger logger
    ) {
        this.directoryPreparer = directoryPreparer;
        this.metadataStore = new InstanceMetadataStore(directoryPreparer.root());
        this.logger = logger;
    }

    public InstanceReconciliationReport reconcile() throws IOException {
        Files.createDirectories(directoryPreparer.root());
        List<Path> directories;
        try (var entries = Files.list(directoryPreparer.root())) {
            directories = entries
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        MutableReport report = new MutableReport();
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
                    directory,
                    exception.getMessage()
            );
            return;
        }
        if (loaded.isEmpty()) {
            report.preservedUnknown++;
            logger.warn(
                    "Preserving unrecognized instance directory without SLS-LITE metadata: {}",
                    directory
            );
            return;
        }

        InstanceMetadata metadata = loaded.orElseThrow();
        if (!directory.getFileName().toString().equals(metadata.instanceId())) {
            report.preservedUnknown++;
            logger.warn(
                    "Preserving instance directory {} because metadata identifies it as {}",
                    directory,
                    metadata.instanceId()
            );
            return;
        }
        if (metadata.persistent()) {
            report.preservedPersistent++;
            logger.info(
                    "Preserving persistent instance {} from blueprint {}",
                    metadata.instanceId(),
                    metadata.blueprintId()
            );
            return;
        }
        if (isRecordedProcessRunning(metadata)) {
            report.preservedRunning++;
            logger.warn(
                    "Preserving ephemeral instance {} because its recorded process is still running",
                    metadata.instanceId()
            );
            return;
        }
        if (metadata.processId() == null && metadata.state() != InstanceState.PREPARING) {
            report.preservedUnknown++;
            logger.warn(
                    "Preserving ambiguous ephemeral instance {} in state {} without process identity",
                    metadata.instanceId(),
                    metadata.state()
            );
            return;
        }

        try {
            directoryPreparer.delete(metadata.instanceId());
            report.removedEphemeral++;
            logger.info("Removed stale ephemeral instance {}", metadata.instanceId());
        } catch (InstancePreparationException exception) {
            report.failures++;
            logger.error(
                    "Unable to remove stale ephemeral instance " + metadata.instanceId(),
                    exception
            );
        }
    }

    private static boolean isRecordedProcessRunning(InstanceMetadata metadata) {
        if (metadata.processId() == null) {
            return false;
        }
        Optional<ProcessHandle> process = ProcessHandle.of(metadata.processId());
        if (process.isEmpty() || !process.orElseThrow().isAlive()) {
            return false;
        }
        if (metadata.processStartedAt() == null) {
            return true;
        }
        Optional<Instant> actualStart = process.orElseThrow().info().startInstant();
        return actualStart.isEmpty()
                || actualStart.orElseThrow().equals(metadata.processStartedAt());
    }

    private static final class MutableReport {

        private int removedEphemeral;
        private int preservedPersistent;
        private int preservedRunning;
        private int preservedUnknown;
        private int failures;

        private InstanceReconciliationReport snapshot() {
            return new InstanceReconciliationReport(
                    removedEphemeral,
                    preservedPersistent,
                    preservedRunning,
                    preservedUnknown,
                    failures
            );
        }
    }
}
