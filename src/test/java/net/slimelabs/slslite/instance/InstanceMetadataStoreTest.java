package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InstanceMetadataStoreTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsInstanceMetadata() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        Path instance = Files.createDirectories(root.resolve("smoke.abc123"));
        InstanceMetadataStore store = new InstanceMetadataStore(root);
        Instant createdAt = Instant.parse("2026-07-24T12:00:00Z");
        Instant processStartedAt = Instant.parse("2026-07-24T12:00:01Z");
        InstanceMetadata expected = new InstanceMetadata(
                "smoke.abc123",
                "smoke",
                false,
                InstanceState.STARTING,
                createdAt,
                42L,
                processStartedAt
        );

        store.write(instance, expected);

        assertEquals(expected, store.read(instance).orElseThrow());
        assertFalse(Files.exists(instance.resolve(
                InstanceMetadataStore.FILE_NAME + ".tmp"
        )));
    }

    @Test
    void rejectsMetadataOutsideTheManagedRoot() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        InstanceMetadataStore store = new InstanceMetadataStore(root);
        InstanceMetadata metadata = new InstanceMetadata(
                "smoke.abc123",
                "smoke",
                false,
                InstanceState.PREPARING,
                Instant.now(),
                null,
                null
        );

        assertThrows(IOException.class, () -> store.write(outside, metadata));
    }
}
