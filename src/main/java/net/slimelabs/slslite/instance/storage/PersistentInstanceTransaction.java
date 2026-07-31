package net.slimelabs.slslite.instance.storage;

import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.model.InstanceIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the atomic swap, rollback, and startup recovery protocol used when a
 * persistent instance is replaced.
 */
final class PersistentInstanceTransaction {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PersistentInstanceTransaction.class);
    private static final Pattern BACKUP_DIRECTORY = Pattern.compile(
            "^\\.(.+)\\.backup-([0-9a-f-]{36})$"
    );
    private static final Pattern STAGING_DIRECTORY = Pattern.compile(
            "^\\.(.+)\\.reset-([0-9a-f-]{36})$"
    );

    private final Path instancesRoot;
    private final PreparedStorageLifecycle storageLifecycle;
    private final Supplier<UUID> nonceSupplier;

    PersistentInstanceTransaction(
            Path instancesRoot,
            PreparedStorageLifecycle storageLifecycle
    ) {
        this(instancesRoot, storageLifecycle, UUID::randomUUID);
    }

    PersistentInstanceTransaction(
            Path instancesRoot,
            PreparedStorageLifecycle storageLifecycle,
            Supplier<UUID> nonceSupplier
    ) {
        this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
        this.storageLifecycle = java.util.Objects.requireNonNull(
                storageLifecycle,
                "storageLifecycle"
        );
        this.nonceSupplier = java.util.Objects.requireNonNull(
                nonceSupplier,
                "nonceSupplier"
        );
    }

    void replace(
            String instanceId,
            Path destination,
            StagingPreparer stagingPreparer,
            DirectoryInitializer initializer
    ) throws InstancePreparationException {
        java.util.Objects.requireNonNull(
                stagingPreparer,
                "stagingPreparer"
        );
        String nonce = nonceSupplier.get().toString();
        Path staging = transactionDirectory(instanceId, "reset", nonce);
        Path backup = transactionDirectory(instanceId, "backup", nonce);
        boolean originalMoved = false;
        boolean replacementMoved = false;
        boolean initialized = false;
        try {
            storageLifecycle.suspend(destination);
            stagingPreparer.prepare(staging);
            storageLifecycle.suspend(staging);
            moveDirectory(destination, backup);
            originalMoved = true;
            moveDirectory(staging, destination);
            replacementMoved = true;
            initializer.initialize(destination);
            initialized = true;
            storageLifecycle.delete(backup);
        } catch (Exception exception) {
            if (initialized) {
                LOGGER.warn(
                        "Persistent reset for {} committed, but backup cleanup "
                                + "failed; {} will be retried during the next "
                                + "startup: {}",
                        instanceId,
                        backup,
                        exception.getMessage()
                );
                return;
            }
            try {
                if (replacementMoved) {
                    storageLifecycle.delete(destination);
                }
                if (originalMoved && Files.exists(backup)) {
                    moveDirectory(backup, destination);
                }
                storageLifecycle.delete(staging);
            } catch (IOException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw new InstancePreparationException(
                    "Unable to reset persistent instance " + destination,
                    exception
            );
        }
    }

    int recover(DirectoryCommitVerifier verifier) throws IOException {
        java.util.Objects.requireNonNull(verifier, "verifier");
        Files.createDirectories(instancesRoot);
        List<Path> directories;
        try (var entries = Files.list(instancesRoot)) {
            directories = entries
                    .filter(path -> Files.isDirectory(
                            path,
                            LinkOption.NOFOLLOW_LINKS
                    ))
                    .toList();
        }

        int recovered = 0;
        Set<Path> handledStaging = new HashSet<>();
        for (Path backup : directories) {
            Matcher match = BACKUP_DIRECTORY.matcher(
                    backup.getFileName().toString()
            );
            if (!match.matches()
                    || !InstanceIdGenerator.isValid(match.group(1))) {
                continue;
            }
            String instanceId = match.group(1);
            Path destination = instancesRoot.resolve(instanceId);
            Path staging = transactionDirectory(
                    instanceId,
                    "reset",
                    match.group(2)
            );
            handledStaging.add(staging);

            if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                    && verifier.isCommitted(destination, instanceId)) {
                storageLifecycle.delete(backup);
            } else {
                storageLifecycle.delete(destination);
                moveDirectory(backup, destination);
            }
            storageLifecycle.delete(staging);
            recovered++;
        }

        for (Path staging : directories) {
            if (handledStaging.contains(staging)) {
                continue;
            }
            Matcher match = STAGING_DIRECTORY.matcher(
                    staging.getFileName().toString()
            );
            if (!match.matches()
                    || !InstanceIdGenerator.isValid(match.group(1))) {
                continue;
            }
            storageLifecycle.delete(staging);
            recovered++;
        }
        return recovered;
    }

    private Path transactionDirectory(
            String instanceId,
            String phase,
            String nonce
    ) {
        return instancesRoot.resolve(
                "." + instanceId + "." + phase + "-" + nonce
        );
    }

    private static void moveDirectory(Path source, Path destination)
            throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    @FunctionalInterface
    interface StagingPreparer {
        void prepare(Path staging) throws Exception;
    }

    @FunctionalInterface
    interface DirectoryInitializer {
        void initialize(Path directory) throws Exception;
    }

    @FunctionalInterface
    interface DirectoryCommitVerifier {
        boolean isCommitted(Path directory, String instanceId)
                throws IOException;
    }
}
