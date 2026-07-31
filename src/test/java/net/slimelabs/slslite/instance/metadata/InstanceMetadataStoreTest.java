package net.slimelabs.slslite.instance.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InstanceMetadataStoreTest {

  @TempDir private Path temporaryDirectory;

  @Test
  void atomicallyRoundTripsInstanceMetadata() throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
    Path instance = Files.createDirectories(root.resolve("smoke.abc123"));
    InstanceMetadataStore store = new InstanceMetadataStore(root);
    Instant createdAt = Instant.parse("2026-07-24T12:00:00Z");
    Instant processStartedAt = Instant.parse("2026-07-24T12:00:01Z");
    InstanceMetadata expected =
        new InstanceMetadata(
            "smoke.abc123",
            "smoke",
            new InstanceDefinitionIdentity("paper", "1.21.11", "0123456789abcdef"),
            false,
            InstanceState.STARTING,
            createdAt,
            42L,
            processStartedAt,
            new InstanceLaunchOverrides(2048, true, "fixture", 10, false));

    store.write(instance, expected);

    assertEquals(expected, store.read(instance).orElseThrow());
    assertFalse(Files.exists(instance.resolve(InstanceMetadataStore.FILE_NAME + ".tmp")));
    assertEquals(
        "4",
        readProperties(instance.resolve(InstanceMetadataStore.FILE_NAME)).getProperty("schema"));
  }

  @Test
  void rejectsMetadataOutsideTheManagedRoot() throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
    Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
    InstanceMetadataStore store = new InstanceMetadataStore(root);
    InstanceMetadata metadata =
        new InstanceMetadata(
            "smoke.abc123", "smoke", false, InstanceState.PREPARING, Instant.now(), null, null);

    assertThrows(IOException.class, () -> store.write(outside, metadata));
  }

  @Test
  void readsLegacyMetadataWithoutInventingDefinitionIdentity() throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
    Path instance = Files.createDirectories(root.resolve("smoke.abc123"));
    Files.writeString(
        instance.resolve(InstanceMetadataStore.FILE_NAME),
        """
                schema=1
                instance_id=smoke.abc123
                blueprint_id=smoke
                persistent=true
                state=STOPPED
                created_at=2026-07-24T12:00:00Z
                """);

    InstanceMetadata loaded = new InstanceMetadataStore(root).read(instance).orElseThrow();

    assertEquals(null, loaded.definitionIdentity());
    assertEquals(InstanceState.STOPPED, loaded.state());
  }

  @Test
  void treatsSchemaTwoFingerprintAsLegacyAfterIdentityContractChange() throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
    Path instance = Files.createDirectories(root.resolve("smoke.abc123"));
    Files.writeString(
        instance.resolve(InstanceMetadataStore.FILE_NAME),
        """
                schema=2
                instance_id=smoke.abc123
                blueprint_id=smoke
                software_id=paper
                software_version=1.21.11
                definition_fingerprint=old-contract
                persistent=true
                state=STOPPED
                created_at=2026-07-24T12:00:00Z
                """);

    InstanceMetadata loaded = new InstanceMetadataStore(root).read(instance).orElseThrow();

    assertEquals(null, loaded.definitionIdentity());
  }

  @Test
  void rejectsOversizedMetadataBeforeParsing() throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve("instances"));
    Path instance = Files.createDirectories(root.resolve("smoke.abc123"));
    Files.write(
        instance.resolve(InstanceMetadataStore.FILE_NAME),
        new byte[(int) InstanceMetadataStore.MAX_METADATA_BYTES + 1]);

    assertThrows(IOException.class, () -> new InstanceMetadataStore(root).read(instance));
  }

  private static java.util.Properties readProperties(Path path) throws Exception {
    java.util.Properties values = new java.util.Properties();
    try (var input = Files.newInputStream(path)) {
      values.load(input);
    }
    return values;
  }
}
