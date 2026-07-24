package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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

        Path persistent = directory(root, "survival.def456");
        metadata.write(persistent, record(
                "survival.def456",
                true,
                InstanceState.STOPPED,
                null,
                null
        ));

        Path running = directory(root, "game.abcd89");
        ProcessHandle current = ProcessHandle.current();
        metadata.write(running, record(
                "game.abcd89",
                false,
                InstanceState.READY,
                current.pid(),
                current.info().startInstant().orElse(null)
        ));

        Path unknown = directory(root, "legacy.abc012");
        Files.writeString(unknown.resolve("server.properties"), "server-port=25565");

        InstanceReconciliationReport report = new InstanceReconciler(
                preparer,
                LoggerFactory.getLogger(InstanceReconcilerTest.class)
        ).reconcile();

        assertEquals(4, report.inspected());
        assertEquals(1, report.removedEphemeral());
        assertEquals(1, report.preservedPersistent());
        assertEquals(1, report.preservedRunning());
        assertEquals(1, report.preservedUnknown());
        assertEquals(0, report.failures());
        assertFalse(Files.exists(ephemeral));
        assertTrue(Files.isDirectory(persistent));
        assertTrue(Files.isDirectory(running));
        assertTrue(Files.isDirectory(unknown));
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

    private static Path directory(Path root, String name) throws Exception {
        return Files.createDirectories(root.resolve(name));
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
