package net.slimelabs.slslite.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SLSConfigRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsAndLoadsBundledConfiguration() throws Exception {
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        repository.initialize();

        SLSConfig config = repository.get();
        assertEquals(4096, config.totalMemoryMiB());
        assertEquals(25570, config.portRangeStart());
        assertEquals(25670, config.portRangeEnd());
        assertEquals(180, config.queueTimeoutSeconds());
        assertEquals(LobbyMode.EXTERNAL, config.lobby().mode());
        assertEquals("lobby", config.lobby().registry());
        assertEquals("lobby", config.lobby().server());
        assertEquals(
                temporaryDirectory.resolve("instances").toAbsolutePath().normalize(),
                config.instancesDirectory()
        );
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config.yml")));
        assertTrue(Files.isDirectory(config.instancesDirectory()));
    }

    @Test
    void rejectsManagedPathTraversal() throws Exception {
        writeConfig("""
                paths:
                  instances: ../outside
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    @Test
    void rejectsDescendingPortRange() throws Exception {
        writeConfig("""
                network:
                  ports:
                    start: 30000
                    end: 29999
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    @Test
    void rejectsUnknownLobbyMode() throws Exception {
        writeConfig("""
                lobby:
                  mode: virtual
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    private void writeConfig(String content) throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.yml"), content);
    }
}
