package net.slimelabs.slslite.instance.metadata;

import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstanceMetadataServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversOnlyPersistentInstancesAndSupportsBlueprintFiltering() throws Exception {
        InstanceMetadataStore store = new InstanceMetadataStore(temporaryDirectory);
        write(store, metadata("lobby.abcdef", "lobby", true, identity("paper", "1.21")));
        write(store, metadata("game.abcdef", "game", true, identity("paper", "1.21")));
        write(store, metadata("temp.abcdef", "lobby", false, identity("paper", "1.21")));
        Files.createDirectory(temporaryDirectory.resolve("unmanaged"));

        InstanceMetadataService service = service();

        assertEquals(
                List.of("game.abcdef", "lobby.abcdef"),
                service.persistentInstanceIds(null)
        );
        assertEquals(
                List.of("lobby.abcdef"),
                service.persistentInstanceIds("lobby")
        );
    }

    @Test
    void migratesLegacyIdentityOnceCurrentDefinitionIsCompatible() throws Exception {
        InstanceMetadataStore store = new InstanceMetadataStore(temporaryDirectory);
        InstanceMetadata legacy = metadata("lobby.abcdef", "lobby", true, null);
        write(store, legacy);
        InstanceDefinitionIdentity current = identity("paper", "1.21.1");

        InstanceMetadata migrated = service().requireCompatibleDefinition(
                legacy,
                current,
                true
        );

        assertEquals(current, migrated.definitionIdentity());
        assertEquals(
                current,
                store.read(temporaryDirectory.resolve(legacy.instanceId()))
                        .orElseThrow()
                        .definitionIdentity()
        );
    }

    @Test
    void rejectsDefinitionDriftWithoutRewritingMetadata() throws Exception {
        InstanceDefinitionIdentity recorded = identity("paper", "1.21");
        InstanceMetadata original = metadata(
                "lobby.abcdef",
                "lobby",
                true,
                recorded
        );
        InstanceMetadataStore store = new InstanceMetadataStore(temporaryDirectory);
        write(store, original);

        assertThrows(
                InstanceOperationException.class,
                () -> service().requireCompatibleDefinition(
                        original,
                        identity("paper", "1.21.1"),
                        true
                )
        );
        assertEquals(
                recorded,
                store.read(temporaryDirectory.resolve(original.instanceId()))
                        .orElseThrow()
                        .definitionIdentity()
        );
    }

    private InstanceMetadataService service() {
        return new InstanceMetadataService(
                temporaryDirectory,
                LoggerFactory.getLogger(InstanceMetadataServiceTest.class)
        );
    }

    private void write(InstanceMetadataStore store, InstanceMetadata metadata)
            throws Exception {
        Path directory = Files.createDirectory(
                temporaryDirectory.resolve(metadata.instanceId())
        );
        store.write(directory, metadata);
    }

    private static InstanceMetadata metadata(
            String instanceId,
            String blueprintId,
            boolean persistent,
            InstanceDefinitionIdentity identity
    ) {
        return new InstanceMetadata(
                instanceId,
                blueprintId,
                identity,
                persistent,
                InstanceState.STOPPED,
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null
        );
    }

    private static InstanceDefinitionIdentity identity(
            String software,
            String version
    ) {
        return new InstanceDefinitionIdentity(software, version, software + "-" + version);
    }
}
