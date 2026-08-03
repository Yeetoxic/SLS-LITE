package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SLSConfigRepositoryTest {

  @TempDir Path temporaryDirectory;

  @Test
  void rejectsConfigurationLargerThanRepositoryLimit() throws Exception {
    Files.write(
        temporaryDirectory.resolve("config.yml"),
        new byte[SLSConfigRepository.MAX_CONFIG_BYTES + 1]);

    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () -> new SLSConfigRepository(temporaryDirectory).reload());

    assertTrue(failure.getMessage().contains("Unable to read"));
  }

  @Test
  void installsAndLoadsBundledConfiguration() throws Exception {
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.initialize();

    SLSConfig config = repository.get();
    assertEquals(4096, config.totalMemoryMiB());
    assertEquals(101, config.maxManagedProcesses());
    assertEquals(25570, config.portRangeStart());
    assertEquals(25670, config.portRangeEnd());
    assertEquals(180, config.queueTimeoutSeconds());
    assertEquals(BlueprintSelectionMode.FIRST_AVAILABLE, config.blueprintSelectionMode());
    assertEquals(180, config.idleShutdownSeconds());
    assertEquals(false, config.managedOutput().mirrorToProxyConsole());
    assertEquals(true, config.managedOutput().writeTemporaryFile());
    assertEquals(4096, config.managedOutput().temporaryFileMaxKiB());
    assertEquals(ForwardingMode.NONE, config.forwarding().mode());
    assertEquals(true, config.forwarding().onlineMode());
    assertEquals(
        temporaryDirectory.resolve("forwarding.secret").toAbsolutePath().normalize(),
        config.forwarding().secretFile());
    assertEquals(false, config.security().allowInsecureOfflineAdministrators());
    assertEquals(600, config.security().claimCodeExpirySeconds());
    assertEquals(true, config.limbo().enabled());
    assertEquals(96, config.limbo().memoryMiB());
    assertEquals(30, config.limbo().startupTimeoutSeconds());
    assertEquals(5, config.limbo().maxRestartAttempts());
    assertEquals(2, config.limbo().initialBackoffSeconds());
    assertEquals(30, config.limbo().maxBackoffSeconds());
    assertEquals(120, config.limbo().stableAfterSeconds());
    assertEquals(LobbyMode.EXTERNAL, config.lobby().mode());
    assertEquals(true, config.lobby().autoStart());
    assertEquals("lobby", config.lobby().registry());
    assertEquals("lobby", config.lobby().server());
    assertEquals(5, config.lobby().maxRestartAttempts());
    assertEquals(5, config.lobby().initialBackoffSeconds());
    assertEquals(60, config.lobby().maxBackoffSeconds());
    assertEquals(120, config.lobby().stableAfterSeconds());
    assertEquals(StorageStrategy.AUTO, config.storage().strategy());
    assertEquals(DetailLogLevel.DETAILED, config.detailedLogging().level());
    assertEquals(false, config.detailedLogging().mirrorToProxyConsole());
    assertEquals(8192, config.detailedLogging().maxFileKiB());
    assertEquals(5, config.detailedLogging().retainedFiles());
    assertEquals(4096, config.detailedLogging().queueCapacity());
    assertEquals(true, config.detailedLogging().redactPaths());
    assertEquals(
        temporaryDirectory.resolve("instances").toAbsolutePath().normalize(),
        config.instancesDirectory());
    assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config.yml")));
    assertTrue(Files.isDirectory(config.instancesDirectory()));
  }

  @Test
  void rejectsManagedPathTraversal() throws Exception {
    writeConfig(
        """
                paths:
                  instances: ../outside
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsDescendingPortRange() throws Exception {
    writeConfig(
        """
                network:
                  ports:
                    start: 30000
                    end: 29999
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsManagedProcessLimitAbovePortCount() throws Exception {
    writeConfig(
        """
                resources:
                  max_managed_processes: 3
                network:
                  ports:
                    start: 30000
                    end: 30001
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsUnknownStructuralKeyWithSuggestion() throws Exception {
    writeConfig(
        """
                resources:
                  total_memroy_mib: 2048
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, repository::reload);

    assertTrue(exception.getMessage().contains("resources.total_memroy_mib"));
    assertTrue(exception.getMessage().contains("resources.total_memory_mib"));
  }

  @Test
  void reportsTheFullPathForMalformedNestedSections() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    recovery: invalid
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, repository::reload);

    assertTrue(exception.getMessage().contains("lobby.limbo.recovery"));
    assertTrue(exception.getMessage().contains("must be an object"));
  }

  @Test
  void rejectsUnknownLobbyMode() throws Exception {
    writeConfig(
        """
                lobby:
                  mode: virtual
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void loadsDisabledManagedLobbyAutomaticStartup() throws Exception {
    writeConfig(
        """
                lobby:
                  mode: managed
                  auto_start: false
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(false, repository.get().lobby().autoStart());
  }

  @Test
  void loadsExplicitStorageStrategy() throws Exception {
    writeConfig(
        """
                storage:
                  strategy: fuse-overlay
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(StorageStrategy.FUSE_OVERLAY, repository.get().storage().strategy());
  }

  @Test
  void loadsExplicitRandomBlueprintSelection() throws Exception {
    writeConfig(
        """
                matchmaking:
                  blueprint_selection: random
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(BlueprintSelectionMode.RANDOM, repository.get().blueprintSelectionMode());
  }

  @Test
  void rejectsUnknownBlueprintSelection() throws Exception {
    writeConfig(
        """
                matchmaking:
                  blueprint_selection: round-robin
                """);

    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () -> new SLSConfigRepository(temporaryDirectory).reload());

    assertTrue(failure.getMessage().contains("matchmaking.blueprint_selection"));
  }

  @Test
  void loadsDetailedLoggingPolicyAndRejectsUnboundedRetention() throws Exception {
    writeConfig(
        """
                detailed_logging:
                  level: normal
                  mirror_to_proxy_console: true
                  max_file_kib: 128
                  retained_files: 3
                  queue_capacity: 256
                  redact_paths: false
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);
    repository.reload();

    assertEquals(DetailLogLevel.NORMAL, repository.get().detailedLogging().level());
    assertEquals(true, repository.get().detailedLogging().mirrorToProxyConsole());
    assertEquals(128, repository.get().detailedLogging().maxFileKiB());
    assertEquals(3, repository.get().detailedLogging().retainedFiles());
    assertEquals(256, repository.get().detailedLogging().queueCapacity());
    assertEquals(false, repository.get().detailedLogging().redactPaths());

    writeConfig(
        """
                detailed_logging:
                  retained_files: 33
                """);
    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsUnknownStorageStrategy() throws Exception {
    writeConfig(
        """
                storage:
                  strategy: magic-copy
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, repository::reload);

    assertTrue(exception.getMessage().contains("storage.strategy"));
  }

  @Test
  void loadsConfinedSnapshotHelperConfiguration() throws Exception {
    writeConfig(
        """
                storage:
                  strategy: snapshot-hook
                  snapshot_hook:
                    executable: helpers/provider
                    timeout_seconds: 45
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(
        temporaryDirectory.resolve("helpers/provider").toAbsolutePath().normalize(),
        repository.get().storage().snapshotHookExecutable());
    assertEquals(45, repository.get().storage().snapshotHookTimeoutSeconds());
  }

  @Test
  void rejectsSnapshotHookWithoutExecutable() throws Exception {
    writeConfig(
        """
                storage:
                  strategy: snapshot-hook
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    ConfigurationException failure = assertThrows(ConfigurationException.class, repository::reload);

    assertTrue(failure.getMessage().contains("storage.snapshot_hook.executable"));
  }

  @Test
  void rejectsSnapshotHelperTraversal() throws Exception {
    writeConfig(
        """
                storage:
                  strategy: snapshot-hook
                  snapshot_hook:
                    executable: ../provider
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void allowsIdleShutdownToBeDisabled() throws Exception {
    writeConfig(
        """
                lifecycle:
                  idle_shutdown_seconds: 0
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(0, repository.get().idleShutdownSeconds());
  }

  @Test
  void loadsManagedLobbyRecoveryPolicy() throws Exception {
    writeConfig(
        """
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
  void loadsAdministratorSecurityPolicy() throws Exception {
    writeConfig(
        """
                security:
                  allow_insecure_offline_administrators: true
                  claim_code_expiry_seconds: 90
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(true, repository.get().security().allowInsecureOfflineAdministrators());
    assertEquals(90, repository.get().security().claimCodeExpirySeconds());
  }

  @Test
  void loadsSLSLimboPolicy() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    enabled: false
                    memory_mib: 128
                    startup_timeout_seconds: 45
                    advertised_protocol: 770
                    recovery:
                      max_attempts: 3
                      initial_backoff_seconds: 4
                      max_backoff_seconds: 16
                      stable_after_seconds: 60
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    SLSLimboConfig limbo = repository.get().limbo();
    assertEquals(false, limbo.enabled());
    assertEquals(128, limbo.memoryMiB());
    assertEquals(45, limbo.startupTimeoutSeconds());
    assertEquals(770, limbo.advertisedProtocol());
    assertEquals(3, limbo.maxRestartAttempts());
    assertEquals(4, limbo.initialBackoffSeconds());
    assertEquals(16, limbo.maxBackoffSeconds());
    assertEquals(60, limbo.stableAfterSeconds());
  }

  @Test
  void rejectsInvalidSLSLimboAdvertisedProtocol() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    advertised_protocol: 0
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void loadsDeprecatedEmergencyAlias() throws Exception {
    writeConfig(
        """
                lobby:
                  emergency:
                    enabled: false
                    memory_mib: 128
                    startup_timeout_seconds: 45
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(false, repository.get().limbo().enabled());
    assertEquals(128, repository.get().limbo().memoryMiB());
    assertEquals(45, repository.get().limbo().startupTimeoutSeconds());
  }

  @Test
  void rejectsConflictingLimboAndDeprecatedEmergencyKeys() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    enabled: true
                  emergency:
                    enabled: false
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsSLSLimboMemoryBelowMinimum() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    memory_mib: 63
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsSLSLimboMaximumBackoffBelowInitialBackoff() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    recovery:
                      initial_backoff_seconds: 10
                      max_backoff_seconds: 5
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsNonPositiveClaimCodeExpiry() throws Exception {
    writeConfig(
        """
                security:
                  claim_code_expiry_seconds: 0
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void rejectsLobbyMaximumBackoffBelowInitialBackoff() throws Exception {
    writeConfig(
        """
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
    writeConfig(
        """
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
    writeConfig(
        """
                managed_output:
                  mirror_to_proxy_console: sometimes
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    assertThrows(ConfigurationException.class, repository::reload);
  }

  @Test
  void loadsModernForwardingPolicy() throws Exception {
    writeConfig(
        """
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
        temporaryDirectory.resolve("secrets/velocity.secret").toAbsolutePath().normalize(),
        forwarding.secretFile());
  }

  @Test
  void rejectsForwardingSecretTraversal() throws Exception {
    writeConfig(
        """
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
