package net.slimelabs.slslite.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdministratorStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void persistsAdministratorsAcrossReloads() throws Exception {
    UUID uniqueId = UUID.randomUUID();
    AdministratorStore store = new AdministratorStore(temporaryDirectory);
    store.initialize();

    store.add(uniqueId, "Yeetoxic");

    AdministratorStore reloaded = new AdministratorStore(temporaryDirectory);
    reloaded.initialize();
    assertTrue(reloaded.contains(uniqueId));
    assertEquals("Yeetoxic", reloaded.list().getFirst().lastKnownName());
  }

  @Test
  void removesAdministratorsByNameOrUuid() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    AdministratorStore store = new AdministratorStore(temporaryDirectory);
    store.initialize();
    store.add(first, "FirstAdmin");
    store.add(second, "SecondAdmin");

    assertEquals(first, store.remove("firstadmin").orElseThrow().uniqueId());
    assertEquals(second, store.remove(second.toString()).orElseThrow().uniqueId());
    assertTrue(store.isEmpty());
  }

  @Test
  void createsStructuredStoreWithoutClaimCodes() throws Exception {
    AdministratorStore store = new AdministratorStore(temporaryDirectory);

    store.initialize();

    String stored = Files.readString(temporaryDirectory.resolve(AdministratorStore.FILE_NAME));
    assertTrue(stored.contains("schema=1"));
    assertFalse(stored.toLowerCase().contains("claim"));
  }

  @Test
  void failedPersistenceDoesNotMutateLiveAdministrators() throws Exception {
    UUID existing = UUID.randomUUID();
    AdministratorStore store = new AdministratorStore(temporaryDirectory);
    store.initialize();
    store.add(existing, "Existing");

    AdministratorStore failing =
        new AdministratorStore(
            temporaryDirectory,
            (dataDirectory, storePath, values) -> {
              throw new IOException("disk unavailable");
            });
    failing.initialize();
    UUID rejected = UUID.randomUUID();

    assertThrows(IOException.class, () -> failing.add(rejected, "Rejected"));
    assertTrue(failing.contains(existing));
    assertFalse(failing.contains(rejected));

    assertThrows(IOException.class, () -> failing.remove("Existing"));
    assertTrue(failing.contains(existing));
  }

  @Test
  void rejectsOversizedAdministratorStoreBeforeParsing() throws Exception {
    Files.write(
        temporaryDirectory.resolve(AdministratorStore.FILE_NAME),
        new byte[(int) AdministratorStore.MAX_STORE_BYTES + 1]);

    assertThrows(IOException.class, () -> new AdministratorStore(temporaryDirectory).initialize());
  }

  @Test
  void rejectsAdministratorStoreSymbolicLink() throws Exception {
    Path outside = temporaryDirectory.resolveSibling("outside-administrators.properties");
    Files.writeString(outside, "schema=1\n");
    try {
      Files.createSymbolicLink(temporaryDirectory.resolve(AdministratorStore.FILE_NAME), outside);
    } catch (IOException | UnsupportedOperationException exception) {
      assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
    }

    assertThrows(IOException.class, () -> new AdministratorStore(temporaryDirectory).initialize());
  }
}
