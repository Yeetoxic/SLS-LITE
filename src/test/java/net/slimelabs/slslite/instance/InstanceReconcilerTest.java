package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import net.slimelabs.slslite.process.FixtureProcessMain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InstanceReconcilerTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void removesOnlyConfirmedStaleEphemeralDirectories() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(root);
        InstanceMetadataStore metadata = new InstanceMetadataStore(root);

        Path ephemeral = directory(root, "smoke.abc123");
        metadata.write(ephemeral, record(
                "smoke.abc123",
                false,
                InstanceState.PREPARING,
                null,
                null
        ));

        Path cancelled = directory(root, "cancelled.abc123");
        metadata.write(cancelled, record(
                "cancelled.abc123",
                false,
                InstanceState.STOPPING,
                null,
                null
        ));

        Path persistent = directory(root, "survival.def456");
        metadata.write(persistent, record(
                "survival.def456",
                true,
                InstanceState.STOPPED,
                null,
                null
        ));

        Path running = directory(root, "game.abcd89");
        Process process = fixtureProcess();
        metadata.write(running, record(
                "game.abcd89",
                false,
                InstanceState.READY,
                process.pid(),
                process.info().startInstant().orElseThrow()
        ));

        Path unknown = directory(root, "legacy.abc012");
        Files.writeString(unknown.resolve("server.properties"), "server-port=25565");

        InstanceReconciliationReport report;
        try {
            report = new InstanceReconciler(
                    preparer,
                    LoggerFactory.getLogger(InstanceReconcilerTest.class)
            ).reconcile();
            process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        assertEquals(5, report.inspected());
        assertEquals(3, report.removedEphemeral());
        assertEquals(1, report.preservedPersistent());
        assertEquals(0, report.preservedRunning());
        assertEquals(1, report.preservedUnknown());
        assertEquals(0, report.failures());
        assertFalse(Files.exists(ephemeral));
        assertFalse(Files.exists(cancelled));
        assertTrue(Files.isDirectory(persistent));
        assertFalse(Files.exists(running));
        assertTrue(Files.isDirectory(unknown));
        assertFalse(process.isAlive());
    }

    @Test
    void preservesMalformedMetadata() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        Path malformed = directory(root, "smoke.abc123");
        Files.writeString(
                malformed.resolve(InstanceMetadataStore.FILE_NAME),
                "schema=1\ninstance_id=smoke.abc123\n"
        );

        InstanceReconciliationReport report = new InstanceReconciler(
                new InstanceDirectoryPreparer(root),
                LoggerFactory.getLogger(InstanceReconcilerTest.class)
        ).reconcile();

        assertEquals(1, report.preservedUnknown());
        assertTrue(Files.isDirectory(malformed));
    }

    @Test
    void stopsVerifiedPersistentChildAndKeepsWorldAsStopped() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        Path persistent = directory(root, "survival.abc123");
        Process process = fixtureProcess();
        InstanceMetadataStore metadata = new InstanceMetadataStore(root);
        metadata.write(persistent, record(
                "survival.abc123",
                true,
                InstanceState.READY,
                process.pid(),
                process.info().startInstant().orElseThrow()
        ));

        InstanceReconciliationReport report;
        try {
            report = new InstanceReconciler(
                    new InstanceDirectoryPreparer(root),
                    LoggerFactory.getLogger(InstanceReconcilerTest.class)
            ).reconcile();
            process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        InstanceMetadata normalized = metadata.read(persistent).orElseThrow();
        assertEquals(1, report.preservedPersistent());
        assertTrue(Files.isDirectory(persistent));
        assertEquals(InstanceState.STOPPED, normalized.state());
        assertEquals(null, normalized.processId());
    }

    @Test
    void preservesLiveProcessWhenStartIdentityIsMissing() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        Path ambiguous = directory(root, "game.abc123");
        ProcessHandle current = ProcessHandle.current();
        new InstanceMetadataStore(root).write(ambiguous, record(
                "game.abc123",
                false,
                InstanceState.READY,
                current.pid(),
                null
        ));

        InstanceReconciliationReport report = new InstanceReconciler(
                new InstanceDirectoryPreparer(root),
                LoggerFactory.getLogger(InstanceReconcilerTest.class)
        ).reconcile();

        assertEquals(1, report.preservedRunning());
        assertTrue(Files.isDirectory(ambiguous));
        assertTrue(current.isAlive());
    }

    @Test
    void restoresBackupWhenResetReplacementNeverCommitted() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        String id = "survival.abc123";
        String nonce = "12345678-1234-1234-1234-123456789abc";
        Path original = directory(root, id);
        Files.writeString(original.resolve("world.dat"), "original");
        new InstanceMetadataStore(root).write(original, record(
                id,
                true,
                InstanceState.STOPPED,
                null,
                null
        ));
        Path backup = root.resolve("." + id + ".backup-" + nonce);
        Files.move(original, backup);
        Path replacement = directory(root, id);
        Files.writeString(replacement.resolve("world.dat"), "incomplete replacement");

        InstanceReconciliationReport report = new InstanceReconciler(
                new InstanceDirectoryPreparer(root),
                LoggerFactory.getLogger(InstanceReconcilerTest.class)
        ).reconcile();

        assertEquals(1, report.recoveredResetTransactions());
        assertEquals("original", Files.readString(root.resolve(id).resolve("world.dat")));
        assertFalse(Files.exists(backup));
    }

    @Test
    void keepsCommittedReplacementAndRemovesResetBackup() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        String id = "survival.abc123";
        String nonce = "12345678-1234-1234-1234-123456789abc";
        Path backup = directory(root, "." + id + ".backup-" + nonce);
        Files.writeString(backup.resolve("world.dat"), "original");
        Path replacement = directory(root, id);
        Files.writeString(replacement.resolve("world.dat"), "replacement");
        new InstanceMetadataStore(root).write(replacement, record(
                id,
                true,
                InstanceState.STOPPED,
                null,
                null
        ));

        InstanceReconciliationReport report = new InstanceReconciler(
                new InstanceDirectoryPreparer(root),
                LoggerFactory.getLogger(InstanceReconcilerTest.class)
        ).reconcile();

        assertEquals(1, report.recoveredResetTransactions());
        assertEquals(
                "replacement",
                Files.readString(root.resolve(id).resolve("world.dat"))
        );
        assertFalse(Files.exists(backup));
    }

    private static Path directory(Path root, String name) throws Exception {
        return Files.createDirectories(root.resolve(name));
    }

    private static Process fixtureProcess() throws Exception {
        boolean windows = System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                windows ? "java.exe" : "java"
        ).toString();
        return new ProcessBuilder(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                FixtureProcessMain.class.getName(),
                "silent"
        ).start();
    }

    private static InstanceMetadata record(
            String id,
            boolean persistent,
            InstanceState state,
            Long processId,
            Instant processStartedAt
    ) {
        return new InstanceMetadata(
                id,
                id.substring(0, id.indexOf('.')),
                persistent,
                state,
                Instant.parse("2026-07-24T12:00:00Z"),
                processId,
                processStartedAt
        );
    }
}
