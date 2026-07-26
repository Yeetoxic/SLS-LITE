package net.slimelabs.slslite.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdministratorStoreTest {

    @TempDir
    Path temporaryDirectory;

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

        String stored = Files.readString(
                temporaryDirectory.resolve(AdministratorStore.FILE_NAME)
        );
        assertTrue(stored.contains("schema=1"));
        assertFalse(stored.toLowerCase().contains("claim"));
    }
}
