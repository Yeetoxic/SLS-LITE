package net.slimelabs.slslite.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.io.ConfinedFiles;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class SLSConfigRepository {
  static final int MAX_CONFIG_BYTES = 1024 * 1024;

  private static final String DEFAULT_CONFIG_RESOURCE = "defaults/host/config.yml";

  private static final int DEFAULT_TOTAL_MEMORY_MIB = 2048;
  private static final int DEFAULT_PORT_RANGE_START = 25570;
  private static final int DEFAULT_PORT_RANGE_END = 25589;
  private static final int DEFAULT_QUEUE_TIMEOUT_SECONDS = 180;
  private static final String DEFAULT_BLUEPRINT_SELECTION = "first-available";
  private static final int DEFAULT_IDLE_SHUTDOWN_SECONDS = 180;
  private static final boolean DEFAULT_MIRROR_MANAGED_OUTPUT = false;
  private static final boolean DEFAULT_WRITE_TEMPORARY_LOG = true;
  private static final int DEFAULT_TEMPORARY_LOG_MAX_KIB = 2048;
  private static final String DEFAULT_DETAIL_LOG_LEVEL = "normal";
  private static final boolean DEFAULT_DETAIL_CONSOLE_MIRROR = false;
  private static final int DEFAULT_DETAIL_LOG_MAX_KIB = 4096;
  private static final int DEFAULT_DETAIL_LOG_RETAINED_FILES = 3;
  private static final int DEFAULT_DETAIL_LOG_QUEUE_CAPACITY = 1024;
  private static final boolean DEFAULT_DETAIL_LOG_REDACT_PATHS = true;
  private static final String DEFAULT_FORWARDING_MODE = "none";
  private static final boolean DEFAULT_FORWARDING_ONLINE_MODE = true;
  private static final String DEFAULT_FORWARDING_SECRET_FILE = "forwarding.secret";
  private static final boolean DEFAULT_ALLOW_INSECURE_OFFLINE_ADMINISTRATORS = false;
  private static final int DEFAULT_CLAIM_CODE_EXPIRY_SECONDS = 600;
  private static final boolean DEFAULT_LIMBO_ENABLED = true;
  private static final int DEFAULT_LIMBO_MEMORY_MIB = 96;
  private static final int DEFAULT_LIMBO_STARTUP_TIMEOUT_SECONDS = 30;
  private static final int DEFAULT_LIMBO_ADVERTISED_PROTOCOL = -1;
  private static final int DEFAULT_LIMBO_MAX_RESTART_ATTEMPTS = 5;
  private static final int DEFAULT_LIMBO_INITIAL_BACKOFF_SECONDS = 2;
  private static final int DEFAULT_LIMBO_MAX_BACKOFF_SECONDS = 30;
  private static final int DEFAULT_LIMBO_STABLE_AFTER_SECONDS = 120;
  private static final String DEFAULT_LOBBY_MODE = "velocity";
  private static final String DEFAULT_LOBBY_REGISTRY = "lobby";
  private static final String DEFAULT_LOBBY_SERVER = "lobby";
  private static final boolean DEFAULT_LOBBY_AUTO_START = true;
  private static final String DEFAULT_STORAGE_STRATEGY = "auto";
  private static final int DEFAULT_SNAPSHOT_HOOK_TIMEOUT_SECONDS = 30;
  private static final int DEFAULT_LOBBY_MAX_RESTART_ATTEMPTS = 5;
  private static final int DEFAULT_LOBBY_INITIAL_BACKOFF_SECONDS = 5;
  private static final int DEFAULT_LOBBY_MAX_BACKOFF_SECONDS = 60;
  private static final int DEFAULT_LOBBY_STABLE_AFTER_SECONDS = 120;
  private static final String DEFAULT_INSTANCES_DIRECTORY = "instances";

  private final Path dataDirectory;
  private final Path proxyDirectory;
  private final Path configPath;
  private volatile SLSConfig config;

  public SLSConfigRepository(Path dataDirectory) {
    this(dataDirectory, dataDirectory);
  }

  public SLSConfigRepository(Path dataDirectory, Path proxyDirectory) {
    this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    this.proxyDirectory = proxyDirectory.toAbsolutePath().normalize();
    this.configPath = this.dataDirectory.resolve("config.yml");
  }

  public void initialize() throws IOException, ConfigurationException {
    ConfinedFiles.ensureDirectory(dataDirectory);
    installDefaultWhenMissing();
    reload();
    ConfinedFiles.ensureDirectory(get().instancesDirectory());
  }

  public synchronized void reload() throws ConfigurationException {
    config = read();
  }

  public SLSConfig get() {
    SLSConfig current = config;
    if (current == null) {
      throw new IllegalStateException("SLS-LITE configuration has not been initialized");
    }
    return current;
  }

  private SLSConfig read() throws ConfigurationException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(MAX_CONFIG_BYTES);
    options.setMaxAliasesForCollections(50);
    options.setNestingDepthLimit(50);
    Yaml yaml = new Yaml(new SafeConstructor(options));

    try (InputStream input = BoundedFileReader.openNoFollow(configPath, MAX_CONFIG_BYTES)) {
      Map<String, Object> root = YamlValues.asMap(yaml.load(input), "root", configPath);
      Map<String, Object> resources = YamlValues.optionalMap(root, "resources", configPath);
      Map<String, Object> network = YamlValues.optionalMap(root, "network", configPath);
      Map<String, Object> ports = YamlValues.optionalMap(network, "ports", "network", configPath);
      Map<String, Object> matchmaking = YamlValues.optionalMap(root, "matchmaking", configPath);
      Map<String, Object> lifecycle = YamlValues.optionalMap(root, "lifecycle", configPath);
      Map<String, Object> managedOutput =
          YamlValues.optionalMap(root, "managed_output", configPath);
      Map<String, Object> detailedLogging =
          YamlValues.optionalMap(root, "detailed_logging", configPath);
      Map<String, Object> forwarding = YamlValues.optionalMap(root, "forwarding", configPath);
      Map<String, Object> security = YamlValues.optionalMap(root, "security", configPath);
      Map<String, Object> presentation = YamlValues.optionalMap(root, "presentation", configPath);
      Map<String, Object> transferActionBar =
          YamlValues.optionalMap(presentation, "transfer_action_bar", "presentation", configPath);
      Map<String, Object> lobby = YamlValues.optionalMap(root, "lobby", configPath);
      Map<String, Object> lobbyRecovery =
          YamlValues.optionalMap(lobby, "recovery", "lobby", configPath);
      if (lobby.containsKey("limbo") && lobby.containsKey("emergency")) {
        throw YamlValues.error(
            configPath,
            "'lobby.limbo' and deprecated 'lobby.emergency' " + "cannot both be configured");
      }
      Map<String, Object> limbo =
          lobby.containsKey("limbo")
              ? YamlValues.optionalMap(lobby, "limbo", "lobby", configPath)
              : YamlValues.optionalMap(lobby, "emergency", "lobby", configPath);
      String limboSection = lobby.containsKey("limbo") ? "lobby.limbo" : "lobby.emergency";
      Map<String, Object> limboRecovery =
          YamlValues.optionalMap(limbo, "recovery", limboSection, configPath);
      Map<String, Object> storage = YamlValues.optionalMap(root, "storage", configPath);
      Map<String, Object> snapshotHook =
          YamlValues.optionalMap(storage, "snapshot_hook", "storage", configPath);
      Map<String, Object> paths = YamlValues.optionalMap(root, "paths", configPath);

      YamlValues.requireOnlyKeys(
          root,
          "",
          configPath,
          "resources",
          "network",
          "matchmaking",
          "lifecycle",
          "managed_output",
          "detailed_logging",
          "forwarding",
          "security",
          "presentation",
          "lobby",
          "storage",
          "paths");
      YamlValues.requireOnlyKeys(
          resources, "resources", configPath, "total_memory_mib", "max_managed_processes");
      YamlValues.requireOnlyKeys(network, "network", configPath, "ports");
      YamlValues.requireOnlyKeys(ports, "network.ports", configPath, "start", "end");
      YamlValues.requireOnlyKeys(
          matchmaking, "matchmaking", configPath, "queue_timeout_seconds", "blueprint_selection");
      YamlValues.requireOnlyKeys(lifecycle, "lifecycle", configPath, "idle_shutdown_seconds");
      YamlValues.requireOnlyKeys(
          managedOutput,
          "managed_output",
          configPath,
          "mirror_to_proxy_console",
          "write_temporary_file",
          "temporary_file_max_kib");
      YamlValues.requireOnlyKeys(
          detailedLogging,
          "detailed_logging",
          configPath,
          "level",
          "mirror_to_proxy_console",
          "max_file_kib",
          "retained_files",
          "queue_capacity",
          "redact_paths");
      YamlValues.requireOnlyKeys(
          forwarding, "forwarding", configPath, "mode", "online_mode", "secret_file");
      YamlValues.requireOnlyKeys(
          security,
          "security",
          configPath,
          "allow_insecure_offline_administrators",
          "claim_code_expiry_seconds");
      YamlValues.requireOnlyKeys(presentation, "presentation", configPath, "transfer_action_bar");
      YamlValues.requireOnlyKeys(
          transferActionBar,
          "presentation.transfer_action_bar",
          configPath,
          "enabled",
          "joining",
          "force_joining",
          "dequeued",
          "frames",
          "frame_interval_millis");
      YamlValues.requireOnlyKeys(
          lobby,
          "lobby",
          configPath,
          "mode",
          "registry",
          "server",
          "auto_start",
          "limbo",
          "emergency",
          "recovery");
      YamlValues.requireOnlyKeys(
          lobbyRecovery,
          "lobby.recovery",
          configPath,
          "max_attempts",
          "initial_backoff_seconds",
          "max_backoff_seconds",
          "stable_after_seconds");
      YamlValues.requireOnlyKeys(
          limbo,
          limboSection,
          configPath,
          "enabled",
          "memory_mib",
          "startup_timeout_seconds",
          "advertised_protocol",
          "recovery");
      YamlValues.requireOnlyKeys(
          limboRecovery,
          limboSection + ".recovery",
          configPath,
          "max_attempts",
          "initial_backoff_seconds",
          "max_backoff_seconds",
          "stable_after_seconds");
      YamlValues.requireOnlyKeys(storage, "storage", configPath, "strategy", "snapshot_hook");
      YamlValues.requireOnlyKeys(
          snapshotHook, "storage.snapshot_hook", configPath, "executable", "timeout_seconds");
      YamlValues.requireOnlyKeys(paths, "paths", configPath, "instances");

      int totalMemory =
          YamlValues.optionalPositiveInt(
              resources, "total_memory_mib", DEFAULT_TOTAL_MEMORY_MIB, configPath);
      int portStart =
          YamlValues.optionalPositiveInt(ports, "start", DEFAULT_PORT_RANGE_START, configPath);
      int portEnd =
          YamlValues.optionalPositiveInt(ports, "end", DEFAULT_PORT_RANGE_END, configPath);
      int maxManagedProcesses =
          YamlValues.optionalPositiveInt(
              resources, "max_managed_processes", portEnd - portStart + 1, configPath);
      int queueTimeout =
          YamlValues.optionalPositiveInt(
              matchmaking, "queue_timeout_seconds", DEFAULT_QUEUE_TIMEOUT_SECONDS, configPath);
      String blueprintSelection =
          YamlValues.optionalString(
              matchmaking, "blueprint_selection", DEFAULT_BLUEPRINT_SELECTION, configPath);
      int idleShutdown =
          YamlValues.optionalNonNegativeInt(
              lifecycle, "idle_shutdown_seconds", DEFAULT_IDLE_SHUTDOWN_SECONDS, configPath);
      boolean mirrorManagedOutput =
          YamlValues.optionalBoolean(
              managedOutput, "mirror_to_proxy_console", DEFAULT_MIRROR_MANAGED_OUTPUT, configPath);
      boolean writeTemporaryLog =
          YamlValues.optionalBoolean(
              managedOutput, "write_temporary_file", DEFAULT_WRITE_TEMPORARY_LOG, configPath);
      int temporaryLogMaxKiB =
          YamlValues.optionalPositiveInt(
              managedOutput, "temporary_file_max_kib", DEFAULT_TEMPORARY_LOG_MAX_KIB, configPath);
      String detailLogLevel =
          YamlValues.optionalString(detailedLogging, "level", DEFAULT_DETAIL_LOG_LEVEL, configPath);
      boolean detailConsoleMirror =
          YamlValues.optionalBoolean(
              detailedLogging,
              "mirror_to_proxy_console",
              DEFAULT_DETAIL_CONSOLE_MIRROR,
              configPath);
      int detailLogMaxKiB =
          YamlValues.optionalPositiveInt(
              detailedLogging, "max_file_kib", DEFAULT_DETAIL_LOG_MAX_KIB, configPath);
      int detailLogRetainedFiles =
          YamlValues.optionalPositiveInt(
              detailedLogging, "retained_files", DEFAULT_DETAIL_LOG_RETAINED_FILES, configPath);
      int detailLogQueueCapacity =
          YamlValues.optionalPositiveInt(
              detailedLogging, "queue_capacity", DEFAULT_DETAIL_LOG_QUEUE_CAPACITY, configPath);
      boolean detailLogRedactPaths =
          YamlValues.optionalBoolean(
              detailedLogging, "redact_paths", DEFAULT_DETAIL_LOG_REDACT_PATHS, configPath);
      String forwardingMode =
          YamlValues.optionalString(forwarding, "mode", DEFAULT_FORWARDING_MODE, configPath);
      boolean forwardingOnlineMode =
          YamlValues.optionalBoolean(
              forwarding, "online_mode", DEFAULT_FORWARDING_ONLINE_MODE, configPath);
      String forwardingSecretFile =
          YamlValues.optionalString(
              forwarding, "secret_file", DEFAULT_FORWARDING_SECRET_FILE, configPath);
      boolean allowInsecureOfflineAdministrators =
          YamlValues.optionalBoolean(
              security,
              "allow_insecure_offline_administrators",
              DEFAULT_ALLOW_INSECURE_OFFLINE_ADMINISTRATORS,
              configPath);
      int claimCodeExpirySeconds =
          YamlValues.optionalPositiveInt(
              security, "claim_code_expiry_seconds", DEFAULT_CLAIM_CODE_EXPIRY_SECONDS, configPath);
      TransferActionBarConfig defaultActionBar = TransferActionBarConfig.defaults();
      boolean actionBarEnabled =
          YamlValues.optionalBoolean(
              transferActionBar, "enabled", defaultActionBar.enabled(), configPath);
      String actionBarJoining =
          YamlValues.optionalString(
              transferActionBar, "joining", defaultActionBar.joining(), configPath);
      String actionBarForceJoining =
          YamlValues.optionalString(
              transferActionBar, "force_joining", defaultActionBar.forceJoining(), configPath);
      String actionBarDequeued =
          YamlValues.optionalString(
              transferActionBar, "dequeued", defaultActionBar.dequeued(), configPath);
      java.util.List<String> actionBarFrames =
          YamlValues.optionalStringList(
              transferActionBar, "frames", defaultActionBar.frames(), configPath);
      int actionBarFrameInterval =
          YamlValues.optionalPositiveInt(
              transferActionBar,
              "frame_interval_millis",
              defaultActionBar.frameIntervalMillis(),
              configPath);
      String lobbyMode = YamlValues.optionalString(lobby, "mode", DEFAULT_LOBBY_MODE, configPath);
      String lobbyRegistry =
          YamlValues.optionalString(lobby, "registry", DEFAULT_LOBBY_REGISTRY, configPath);
      String lobbyServer =
          YamlValues.optionalString(lobby, "server", DEFAULT_LOBBY_SERVER, configPath);
      boolean lobbyAutoStart =
          YamlValues.optionalBoolean(lobby, "auto_start", DEFAULT_LOBBY_AUTO_START, configPath);
      int lobbyMaxRestartAttempts =
          YamlValues.optionalNonNegativeInt(
              lobbyRecovery, "max_attempts", DEFAULT_LOBBY_MAX_RESTART_ATTEMPTS, configPath);
      int lobbyInitialBackoff =
          YamlValues.optionalPositiveInt(
              lobbyRecovery,
              "initial_backoff_seconds",
              DEFAULT_LOBBY_INITIAL_BACKOFF_SECONDS,
              configPath);
      int lobbyMaxBackoff =
          YamlValues.optionalPositiveInt(
              lobbyRecovery, "max_backoff_seconds", DEFAULT_LOBBY_MAX_BACKOFF_SECONDS, configPath);
      int lobbyStableAfter =
          YamlValues.optionalPositiveInt(
              lobbyRecovery,
              "stable_after_seconds",
              DEFAULT_LOBBY_STABLE_AFTER_SECONDS,
              configPath);
      boolean limboEnabled =
          YamlValues.optionalBoolean(limbo, "enabled", DEFAULT_LIMBO_ENABLED, configPath);
      int limboMemory =
          YamlValues.optionalPositiveInt(limbo, "memory_mib", DEFAULT_LIMBO_MEMORY_MIB, configPath);
      int limboStartupTimeout =
          YamlValues.optionalPositiveInt(
              limbo, "startup_timeout_seconds", DEFAULT_LIMBO_STARTUP_TIMEOUT_SECONDS, configPath);
      int limboAdvertisedProtocol =
          YamlValues.optionalMinusOneOrPositiveInt(
              limbo, "advertised_protocol", DEFAULT_LIMBO_ADVERTISED_PROTOCOL, configPath);
      int limboMaxRestartAttempts =
          YamlValues.optionalNonNegativeInt(
              limboRecovery, "max_attempts", DEFAULT_LIMBO_MAX_RESTART_ATTEMPTS, configPath);
      int limboInitialBackoff =
          YamlValues.optionalPositiveInt(
              limboRecovery,
              "initial_backoff_seconds",
              DEFAULT_LIMBO_INITIAL_BACKOFF_SECONDS,
              configPath);
      int limboMaxBackoff =
          YamlValues.optionalPositiveInt(
              limboRecovery, "max_backoff_seconds", DEFAULT_LIMBO_MAX_BACKOFF_SECONDS, configPath);
      int limboStableAfter =
          YamlValues.optionalPositiveInt(
              limboRecovery,
              "stable_after_seconds",
              DEFAULT_LIMBO_STABLE_AFTER_SECONDS,
              configPath);
      String storageStrategy =
          YamlValues.optionalString(storage, "strategy", DEFAULT_STORAGE_STRATEGY, configPath);
      String snapshotHookExecutable =
          YamlValues.optionalString(snapshotHook, "executable", "", configPath);
      int snapshotHookTimeoutSeconds =
          YamlValues.optionalPositiveInt(
              snapshotHook, "timeout_seconds", DEFAULT_SNAPSHOT_HOOK_TIMEOUT_SECONDS, configPath);
      String instances =
          YamlValues.optionalString(paths, "instances", DEFAULT_INSTANCES_DIRECTORY, configPath);

      Path instancesDirectory = resolveManagedPath(instances, "paths.instances");
      try {
        StorageStrategy parsedStorageStrategy = StorageStrategy.parse(storageStrategy);
        if (parsedStorageStrategy == StorageStrategy.SNAPSHOT_HOOK
            && snapshotHookExecutable.isBlank()) {
          throw new IllegalArgumentException(
              "storage.snapshot_hook.executable is required when "
                  + "storage.strategy is snapshot-hook");
        }
        return new SLSConfig(
            totalMemory,
            maxManagedProcesses,
            portStart,
            portEnd,
            queueTimeout,
            BlueprintSelectionMode.parse(blueprintSelection),
            idleShutdown,
            new ManagedOutputConfig(mirrorManagedOutput, writeTemporaryLog, temporaryLogMaxKiB),
            new ForwardingConfig(
                ForwardingMode.parse(forwardingMode),
                forwardingOnlineMode,
                resolveProxyPath(forwardingSecretFile, "forwarding.secret_file")),
            new SecurityConfig(allowInsecureOfflineAdministrators, claimCodeExpirySeconds),
            new SLSLimboConfig(
                limboEnabled,
                limboMemory,
                limboStartupTimeout,
                limboAdvertisedProtocol,
                limboMaxRestartAttempts,
                limboInitialBackoff,
                limboMaxBackoff,
                limboStableAfter),
            new LobbyConfig(
                LobbyMode.parse(lobbyMode),
                lobbyRegistry,
                lobbyServer,
                lobbyAutoStart,
                lobbyMaxRestartAttempts,
                lobbyInitialBackoff,
                lobbyMaxBackoff,
                lobbyStableAfter),
            new StorageConfig(
                parsedStorageStrategy,
                snapshotHookExecutable.isBlank()
                    ? null
                    : resolveManagedPath(
                        snapshotHookExecutable, "storage.snapshot_hook.executable"),
                snapshotHookTimeoutSeconds),
            new DetailedLoggingConfig(
                DetailLogLevel.parse(detailLogLevel),
                detailConsoleMirror,
                detailLogMaxKiB,
                detailLogRetainedFiles,
                detailLogQueueCapacity,
                detailLogRedactPaths),
            new TransferActionBarConfig(
                actionBarEnabled,
                actionBarJoining,
                actionBarForceJoining,
                actionBarDequeued,
                actionBarFrames,
                actionBarFrameInterval),
            instancesDirectory);
      } catch (IllegalArgumentException exception) {
        throw YamlValues.error(configPath, exception.getMessage());
      }
    } catch (IOException exception) {
      throw new ConfigurationException("Unable to read " + configPath, exception);
    } catch (InvalidPathException exception) {
      throw new ConfigurationException(
          configPath + ": invalid path: " + exception.getMessage(), exception);
    } catch (ConfigurationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ConfigurationException(
          "Invalid YAML in " + configPath + ": " + exception.getMessage(), exception);
    }
  }

  private Path resolveManagedPath(String value, String key) throws ConfigurationException {
    Path configured = Path.of(value);
    if (configured.isAbsolute()) {
      throw YamlValues.error(configPath, "'" + key + "' must be relative");
    }

    Path resolved = dataDirectory.resolve(configured).normalize();
    if (!resolved.startsWith(dataDirectory)) {
      throw YamlValues.error(configPath, "'" + key + "' must stay inside " + dataDirectory);
    }
    return resolved;
  }

  private Path resolveProxyPath(String value, String key) throws ConfigurationException {
    Path configured = Path.of(value);
    if (configured.isAbsolute()) {
      throw YamlValues.error(configPath, "'" + key + "' must be relative");
    }
    Path resolved = proxyDirectory.resolve(configured).normalize();
    if (!resolved.startsWith(proxyDirectory)) {
      throw YamlValues.error(configPath, "'" + key + "' must stay inside " + proxyDirectory);
    }
    return resolved;
  }

  private void installDefaultWhenMissing() throws IOException {
    ConfinedFiles.ensureDirectory(dataDirectory);
    if (Files.exists(configPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      ConfinedFiles.requireRegularFile(configPath);
      return;
    }

    try (InputStream source =
        getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
      if (source == null) {
        throw new IOException("Bundled host default is missing: " + DEFAULT_CONFIG_RESOURCE);
      }
      ConfinedFiles.atomicCopy(dataDirectory, "config.yml", source, MAX_CONFIG_BYTES);
    }
  }
}
