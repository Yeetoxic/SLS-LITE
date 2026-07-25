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
        assertEquals(180, config.idleShutdownSeconds());
        assertEquals(false, config.managedOutput().mirrorToProxyConsole());
        assertEquals(true, config.managedOutput().writeTemporaryFile());
        assertEquals(4096, config.managedOutput().temporaryFileMaxKiB());
        assertEquals(ForwardingMode.NONE, config.forwarding().mode());
        assertEquals(true, config.forwarding().onlineMode());
        assertEquals(
                temporaryDirectory.resolve("forwarding.secret").toAbsolutePath().normalize(),
                config.forwarding().secretFile()
        );
        assertEquals(LobbyMode.EXTERNAL, config.lobby().mode());
        assertEquals("lobby", config.lobby().registry());
        assertEquals("lobby", config.lobby().server());
        assertEquals(5, config.lobby().maxRestartAttempts());
        assertEquals(5, config.lobby().initialBackoffSeconds());
        assertEquals(60, config.lobby().maxBackoffSeconds());
        assertEquals(120, config.lobby().stableAfterSeconds());
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

    @Test
    void allowsIdleShutdownToBeDisabled() throws Exception {
        writeConfig("""
                lifecycle:
                  idle_shutdown_seconds: 0
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        repository.reload();

        assertEquals(0, repository.get().idleShutdownSeconds());
    }

    @Test
    void loadsManagedLobbyRecoveryPolicy() throws Exception {
        writeConfig("""
                lobby:
                  mode: managed
                  registry: lobby
                  server: lobby
                  recovery:
                    max_attempts: 3
                    initial_backoff_seconds: 2
                    max_backoff_seconds: 8
                    stable_after_seconds: 30
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        repository.reload();

        LobbyConfig lobby = repository.get().lobby();
        assertEquals(3, lobby.maxRestartAttempts());
        assertEquals(2, lobby.initialBackoffSeconds());
        assertEquals(8, lobby.maxBackoffSeconds());
        assertEquals(30, lobby.stableAfterSeconds());
    }

    @Test
    void rejectsLobbyMaximumBackoffBelowInitialBackoff() throws Exception {
        writeConfig("""
                lobby:
                  recovery:
                    initial_backoff_seconds: 10
                    max_backoff_seconds: 5
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    @Test
    void loadsManagedOutputPolicy() throws Exception {
        writeConfig("""
                managed_output:
                  mirror_to_proxy_console: true
                  write_temporary_file: false
                  temporary_file_max_kib: 128
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        repository.reload();

        ManagedOutputConfig output = repository.get().managedOutput();
        assertEquals(true, output.mirrorToProxyConsole());
        assertEquals(false, output.writeTemporaryFile());
        assertEquals(128, output.temporaryFileMaxKiB());
    }

    @Test
    void rejectsNonBooleanManagedOutputSetting() throws Exception {
        writeConfig("""
                managed_output:
                  mirror_to_proxy_console: sometimes
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    @Test
    void loadsModernForwardingPolicy() throws Exception {
        writeConfig("""
                forwarding:
                  mode: modern
                  online_mode: false
                  secret_file: secrets/velocity.secret
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        repository.reload();

        ForwardingConfig forwarding = repository.get().forwarding();
        assertEquals(ForwardingMode.MODERN, forwarding.mode());
        assertEquals(false, forwarding.onlineMode());
        assertEquals(
                temporaryDirectory.resolve("secrets/velocity.secret")
                        .toAbsolutePath().normalize(),
                forwarding.secretFile()
        );
    }

    @Test
    void rejectsForwardingSecretTraversal() throws Exception {
        writeConfig("""
                forwarding:
                  mode: modern
                  secret_file: ../outside.secret
                """);
        SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    private void writeConfig(String content) throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.yml"), content);
    }
}
