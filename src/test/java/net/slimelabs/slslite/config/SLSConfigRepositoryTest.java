package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
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
  void rejectsConfigurationPathThatIsNotARegularFile() throws Exception {
    Path dataDirectory = temporaryDirectory.resolve("directory-config");
    Files.createDirectories(dataDirectory.resolve("config.yml"));

    assertThrows(
        ConfigurationException.class, () -> new SLSConfigRepository(dataDirectory).reload());
  }

  @Test
  void rejectsSymbolicLinkConfiguration() throws Exception {
    Path dataDirectory = Files.createDirectories(temporaryDirectory.resolve("linked-config"));
    Path target = temporaryDirectory.resolve("outside-config.yml");
    Files.writeString(target, "resources:\n  total_memory_mib: 2048\n");
    try {
      Files.createSymbolicLink(dataDirectory.resolve("config.yml"), target);
    } catch (UnsupportedOperationException | java.io.IOException exception) {
      Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
    }

    assertThrows(
        ConfigurationException.class, () -> new SLSConfigRepository(dataDirectory).reload());
  }

  @Test
  void installsAndLoadsBundledConfiguration() throws Exception {
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.initialize();

    SLSConfig config = repository.get();
    assertEquals(2048, config.totalMemoryMiB());
    assertEquals(20, config.maxManagedProcesses());
    assertEquals(25570, config.portRangeStart());
    assertEquals(25589, config.portRangeEnd());
    assertEquals(180, config.queueTimeoutSeconds());
    assertEquals(BlueprintSelectionMode.FIRST_AVAILABLE, config.blueprintSelectionMode());
    assertEquals(180, config.idleShutdownSeconds());
    assertEquals(false, config.managedOutput().mirrorToProxyConsole());
    assertEquals(true, config.managedOutput().writeTemporaryFile());
    assertEquals(2048, config.managedOutput().temporaryFileMaxKiB());
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
    assertEquals(SLSLimboPresentationConfig.defaults(), config.limbo().presentation());
    assertEquals(LobbyMode.VELOCITY, config.lobby().mode());
    assertEquals(true, config.lobby().autoStart());
    assertEquals("lobby", config.lobby().registry());
    assertEquals("lobby", config.lobby().server());
    assertEquals(5, config.lobby().maxRestartAttempts());
    assertEquals(5, config.lobby().initialBackoffSeconds());
    assertEquals(60, config.lobby().maxBackoffSeconds());
    assertEquals(120, config.lobby().stableAfterSeconds());
    assertEquals(StorageStrategy.AUTO, config.storage().strategy());
    assertEquals(StorageConfig.DEFAULT_AUTO_PRIORITY, config.storage().autoPriority());
    assertEquals(StorageConfig.AUTO_COPY_PARALLELISM, config.storage().copyParallelism());
    assertEquals(DetailLogLevel.NORMAL, config.detailedLogging().level());
    assertEquals(false, config.detailedLogging().mirrorToProxyConsole());
    assertEquals(4096, config.detailedLogging().maxFileKiB());
    assertEquals(3, config.detailedLogging().retainedFiles());
    assertEquals(1024, config.detailedLogging().queueCapacity());
    assertEquals(true, config.detailedLogging().redactPaths());
    assertEquals(TransferActionBarConfig.defaults(), config.transferActionBar());
    assertEquals(ViaVersionSyncPolicy.AUTO, config.viaVersionSyncPolicy());
    assertEquals(DiagnosticRetentionConfig.defaults(), config.diagnosticRetention());
    assertEquals(SoftwareConfig.defaults(), config.software());
    assertEquals(
        temporaryDirectory.resolve("instances").toAbsolutePath().normalize(),
        config.instancesDirectory());
    assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config.yml")));
    assertEquals(false, Files.exists(temporaryDirectory.resolve("config-reference-v2.yml")));
    assertTrue(Files.isDirectory(config.instancesDirectory()));
    assertEquals(2, repository.migrationStatus().configuredVersion());
    assertEquals(false, repository.migrationStatus().updateAvailable());
  }

  @Test
  void loadsLegacyConfigurationWithoutRewritingItAndReportsSafeDefaults() throws Exception {
    String legacy = "resources:\n  total_memory_mib: 3072\n";
    writeConfig(legacy);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.initialize();

    assertEquals(legacy, Files.readString(temporaryDirectory.resolve("config.yml")));
    assertEquals(false, repository.get().software().autoAcceptEula());
    assertEquals(1, repository.migrationStatus().configuredVersion());
    assertEquals(false, repository.migrationStatus().versionDeclared());
    assertEquals(
        "unversioned legacy (treated as generation 1)",
        repository.migrationStatus().generationDescription());
    assertTrue(repository.migrationStatus().updateAvailable());
    assertTrue(
        repository
            .migrationStatus()
            .effectiveDefaults()
            .contains("software.auto_accept_eula: false"));
  }

  @Test
  void rejectsFutureConfigurationVersionWithUpgradeDirection() throws Exception {
    writeConfig("config_version: 99\n");

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> new SLSConfigRepository(temporaryDirectory).reload());

    assertTrue(exception.getMessage().contains("newer than supported version 2"));
    assertTrue(exception.getMessage().contains("newer SLS-LITE build"));
  }

  @Test
  void ignoresLegacyReferenceFilesWithoutCreatingOrChangingThem() throws Exception {
    writeConfig("config_version: 2\n");
    Files.writeString(temporaryDirectory.resolve("config-reference-v2.yml"), "operator file\n");

    new SLSConfigRepository(temporaryDirectory).initialize();

    assertEquals(
        "operator file\n", Files.readString(temporaryDirectory.resolve("config-reference-v2.yml")));
  }

  @Test
  void currentVersionWithOmittedOptionalValuesIsNotReportedAsBehind() throws Exception {
    writeConfig("config_version: 2\n");
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.initialize();

    assertEquals(false, repository.migrationStatus().updateAvailable());
    assertTrue(
        repository
            .migrationStatus()
            .effectiveDefaults()
            .contains("software.auto_accept_eula: false"));
  }

  @Test
  void loadsHostWideAutomaticEulaAcceptance() throws Exception {
    writeConfig(
        """
        config_version: 2
        software:
          auto_accept_eula: true
        """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(true, repository.get().software().autoAcceptEula());
  }

  @Test
  void rejectsMalformedHostWideAutomaticEulaAcceptance() throws Exception {
    writeConfig("software:\n  auto_accept_eula: yes-please\n");

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> new SLSConfigRepository(temporaryDirectory).reload());

    assertTrue(exception.getMessage().contains("software.auto_accept_eula"));
  }

  @Test
  void loadsViaVersionBackendSynchronizationPolicy() throws Exception {
    writeConfig(
        """
                compatibility:
                  viaversion_backend_sync: off
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(ViaVersionSyncPolicy.OFF, repository.get().viaVersionSyncPolicy());
  }

  @Test
  void loadsOrderedStoragePolicyAndNumericCopyParallelism() throws Exception {
    writeConfig(
        """
                storage:
                  strategy: auto
                  auto_priority: [overlay, reflink]
                  copy_parallelism: 7
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(
        List.of(StorageStrategy.OVERLAY, StorageStrategy.REFLINK),
        repository.get().storage().autoPriority());
    assertEquals(7, repository.get().storage().copyParallelism());
    assertEquals(false, repository.get().storage().permitsPortableFallback());
  }

  @Test
  void loadsZeroDiagnosticRetention() throws Exception {
    writeConfig(
        """
                diagnostics:
                  console_tail_lines: 0
                  installer_history_entries: 0
                  failure_reports: 0
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    assertEquals(new DiagnosticRetentionConfig(0, 0, 0), repository.get().diagnosticRetention());
  }

  @Test
  void rejectsExcessiveDiagnosticRetention() throws Exception {
    writeConfig(
        """
                diagnostics:
                  console_tail_lines: 10001
                """);

    assertThrows(
        ConfigurationException.class, () -> new SLSConfigRepository(temporaryDirectory).reload());
  }

  @Test
  void rejectsInvalidStoragePolicyShapes() throws Exception {
    for (String policy :
        List.of(
            "auto_priority: []",
            "auto_priority: [copy, copy]",
            "auto_priority: [snapshot-hook]",
            "auto_priority: [unknown]",
            "copy_parallelism: 17",
            "copy_parallelism: many")) {
      Path directory =
          Files.createDirectories(temporaryDirectory.resolve(Integer.toString(policy.hashCode())));
      Files.writeString(directory.resolve("config.yml"), "storage:\n  " + policy + "\n");

      assertThrows(
          ConfigurationException.class, () -> new SLSConfigRepository(directory).reload(), policy);
    }
  }

  @Test
  void rejectsUnknownViaVersionBackendSynchronizationPolicy() throws Exception {
    writeConfig(
        """
                compatibility:
                  viaversion_backend_sync: sometimes
                """);

    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () -> new SLSConfigRepository(temporaryDirectory).reload());

    assertTrue(failure.getMessage().contains("must be auto, on, or off"));
  }

  @Test
  void codeLevelDetailedLoggingDefaultsMatchGeneratedConfiguration() throws Exception {
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);
    repository.initialize();

    assertEquals(DetailedLoggingConfig.defaults(), repository.get().detailedLogging());
  }

  @Test
  void loadsBoundedTransferActionBarPresentation() throws Exception {
    writeConfig(
        """
                presentation:
                  transfer_action_bar:
                    enabled: false
                    joining: "<aqua>To <server>"
                    force_joining: "<yellow>Forced <server>"
                    dequeued: "<gray>Cancelled"
                    frames: ["<red>A", "<blue>B"]
                    frame_interval_millis: 250
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    TransferActionBarConfig actionBar = repository.get().transferActionBar();
    assertEquals(false, actionBar.enabled());
    assertEquals("<aqua>To <server>", actionBar.joining());
    assertEquals(List.of("<red>A", "<blue>B"), actionBar.frames());
    assertEquals(250, actionBar.frameIntervalMillis());
  }

  @Test
  void rejectsExcessiveTransferActionBarInterval() throws Exception {
    writeConfig(
        """
                presentation:
                  transfer_action_bar:
                    frame_interval_millis: 2001
                """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> new SLSConfigRepository(temporaryDirectory).reload());

    assertTrue(exception.getMessage().contains("frame interval"));
  }

  @Test
  void loadsSlsLimboPresentation() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    presentation:
                      dimension: OVERWORLD
                      ping:
                        enabled: false
                        description: "custom: ping"
                        version: "<aqua>Network"
                      player_list:
                        enabled: true
                        username: Holder
                      brand:
                        enabled: false
                        text: "hidden brand"
                      join_message:
                        enabled: true
                        text: "line one\nline two: safe"
                      boss_bar:
                        enabled: true
                        text: "<blue>Waiting"
                        health: 0.5
                        color: BLUE
                        division: NOTCHED_10
                      title:
                        enabled: false
                        title: "Title"
                        subtitle: "Subtitle"
                        fade_in_ticks: 0
                        stay_ticks: 40
                        fade_out_ticks: 5
                      header_footer:
                        enabled: true
                        header: "Header"
                        footer: "Footer"
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    SLSLimboPresentationConfig presentation = repository.get().limbo().presentation();
    assertEquals(SLSLimboPresentationConfig.Dimension.OVERWORLD, presentation.dimension());
    assertEquals(false, presentation.ping().enabled());
    assertEquals("custom: ping", presentation.ping().description());
    assertEquals(true, presentation.playerList().enabled());
    assertEquals("line one line two: safe", presentation.joinMessage().text());
    assertEquals(0.5, presentation.bossBar().health());
    assertEquals("NOTCHED_10", presentation.bossBar().division());
    assertEquals(0, presentation.title().fadeInTicks());
    assertEquals(true, presentation.headerFooter().enabled());
  }

  @Test
  void rejectsInvalidSlsLimboPresentationBounds() throws Exception {
    writeConfig(
        """
                lobby:
                  limbo:
                    presentation:
                      boss_bar:
                        health: 1.1
                """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> new SLSConfigRepository(temporaryDirectory).reload());

    assertTrue(exception.getMessage().contains("boss-bar health"));
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
  void loadsSecureBackendMessagingPolicy() throws Exception {
    writeConfig(
        """
                security:
                  backend_messaging:
                    enabled: true
                    command_relay_enabled: true
                    requests_per_window: 7
                    window_seconds: 12
                    sources:
                      external-lobby:
                        server: lobby
                        actions: [matchmake, command]
                        command_roots: [sls join, sls list]
                      managed-lobbies:
                        blueprint: production/lobby
                        actions: [matchmake]
                """);
    SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

    repository.reload();

    BackendMessagingConfig messaging = repository.get().security().backendMessaging();
    assertEquals(true, messaging.enabled());
    assertEquals(true, messaging.commandRelayEnabled());
    assertEquals(7, messaging.requestsPerWindow());
    assertEquals(12, messaging.windowSeconds());
    assertEquals(2, messaging.sources().size());
    assertEquals("lobby", messaging.sources().get(0).server());
    assertEquals("production/lobby", messaging.sources().get(1).blueprint());
  }

  @Test
  void rejectsUnsafeBackendMessagingPolicies() throws Exception {
    List<InvalidConfig> invalidConfigurations =
        List.of(
            invalid("enabled without source", "security:\n  backend_messaging:\n    enabled: true"),
            invalid(
                "unknown action",
                """
                security:
                  backend_messaging:
                    sources:
                      lobby:
                        server: lobby
                        actions: [execute]
                """),
            invalid(
                "relay without global opt-in",
                """
                security:
                  backend_messaging:
                    enabled: true
                    sources:
                      lobby:
                        server: lobby
                        actions: [command]
                        command_roots: [sls]
                """));

    for (InvalidConfig invalid : invalidConfigurations) {
      writeConfig(invalid.yaml());
      assertThrows(
          ConfigurationException.class,
          () -> new SLSConfigRepository(temporaryDirectory).reload(),
          () -> "Expected repository rejection for " + invalid.description());
    }
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

  @Test
  void rejectsEveryDocumentedOutOfRangeConfigurationPolicy() throws Exception {
    List<InvalidConfig> invalidConfigurations =
        List.of(
            invalid("total memory", "resources:\n  total_memory_mib: 0"),
            invalid("managed processes", "resources:\n  max_managed_processes: 0"),
            invalid("low port", "network:\n  ports:\n    start: 1023"),
            invalid("high port", "network:\n  ports:\n    end: 65536"),
            invalid("queue timeout", "matchmaking:\n  queue_timeout_seconds: 0"),
            invalid("selection mode", "matchmaking:\n  blueprint_selection: newest"),
            invalid("idle shutdown", "lifecycle:\n  idle_shutdown_seconds: -1"),
            invalid("temporary log lower bound", "managed_output:\n  temporary_file_max_kib: 0"),
            invalid(
                "temporary log upper bound", "managed_output:\n  temporary_file_max_kib: 1048577"),
            invalid("detail level", "detailed_logging:\n  level: verbose"),
            invalid("detail file lower bound", "detailed_logging:\n  max_file_kib: 63"),
            invalid("detail file upper bound", "detailed_logging:\n  max_file_kib: 1048577"),
            invalid("detail retention lower bound", "detailed_logging:\n  retained_files: 0"),
            invalid("detail retention upper bound", "detailed_logging:\n  retained_files: 33"),
            invalid("detail queue lower bound", "detailed_logging:\n  queue_capacity: 127"),
            invalid("detail queue upper bound", "detailed_logging:\n  queue_capacity: 65537"),
            invalid("forwarding mode", "forwarding:\n  mode: legacy"),
            invalid("claim expiry", "security:\n  claim_code_expiry_seconds: 0"),
            invalid("lobby registry", "lobby:\n  registry: ''"),
            invalid("lobby server", "lobby:\n  server: ''"),
            invalid("lobby attempts", "lobby:\n  recovery:\n    max_attempts: -1"),
            invalid("lobby initial backoff", "lobby:\n  recovery:\n    initial_backoff_seconds: 0"),
            invalid("lobby stable period", "lobby:\n  recovery:\n    stable_after_seconds: 0"),
            invalid("limbo startup timeout", "lobby:\n  limbo:\n    startup_timeout_seconds: 0"),
            invalid("limbo attempts", "lobby:\n  limbo:\n    recovery:\n      max_attempts: -1"),
            invalid(
                "limbo initial backoff",
                "lobby:\n  limbo:\n    recovery:\n      initial_backoff_seconds: 0"),
            invalid(
                "limbo stable period",
                "lobby:\n  limbo:\n    recovery:\n      stable_after_seconds: 0"),
            invalid(
                "snapshot timeout lower bound",
                "storage:\n  snapshot_hook:\n    timeout_seconds: 0"),
            invalid(
                "snapshot timeout upper bound",
                "storage:\n  snapshot_hook:\n    timeout_seconds: 301"));

    for (InvalidConfig invalid : invalidConfigurations) {
      writeConfig(invalid.yaml());
      SLSConfigRepository repository = new SLSConfigRepository(temporaryDirectory);

      assertThrows(
          ConfigurationException.class,
          repository::reload,
          () -> "Expected repository rejection for " + invalid.description());
    }
  }

  private static InvalidConfig invalid(String description, String yaml) {
    return new InvalidConfig(description, yaml + "\n");
  }

  private record InvalidConfig(String description, String yaml) {}

  private void writeConfig(String content) throws Exception {
    Files.writeString(temporaryDirectory.resolve("config.yml"), content);
  }
}
