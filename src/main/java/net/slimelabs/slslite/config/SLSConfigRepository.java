package net.slimelabs.slslite.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

public final class SLSConfigRepository {

    private static final int DEFAULT_TOTAL_MEMORY_MIB = 4096;
    private static final int DEFAULT_PORT_RANGE_START = 25570;
    private static final int DEFAULT_PORT_RANGE_END = 25670;
    private static final int DEFAULT_QUEUE_TIMEOUT_SECONDS = 180;
    private static final int DEFAULT_IDLE_SHUTDOWN_SECONDS = 180;
    private static final boolean DEFAULT_MIRROR_MANAGED_OUTPUT = false;
    private static final boolean DEFAULT_WRITE_TEMPORARY_LOG = true;
    private static final int DEFAULT_TEMPORARY_LOG_MAX_KIB = 4096;
    private static final String DEFAULT_FORWARDING_MODE = "none";
    private static final boolean DEFAULT_FORWARDING_ONLINE_MODE = true;
    private static final String DEFAULT_FORWARDING_SECRET_FILE = "forwarding.secret";
    private static final boolean DEFAULT_ALLOW_INSECURE_OFFLINE_ADMINISTRATORS = false;
    private static final int DEFAULT_CLAIM_CODE_EXPIRY_SECONDS = 600;
    private static final boolean DEFAULT_LIMBO_ENABLED = true;
    private static final int DEFAULT_LIMBO_MEMORY_MIB = 96;
    private static final int DEFAULT_LIMBO_STARTUP_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_LOBBY_MODE = "external";
    private static final String DEFAULT_LOBBY_REGISTRY = "lobby";
    private static final String DEFAULT_LOBBY_SERVER = "lobby";
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
        Files.createDirectories(dataDirectory);
        installDefaultWhenMissing();
        reload();
        Files.createDirectories(get().instancesDirectory());
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
        Yaml yaml = new Yaml(new SafeConstructor(options));

        try (InputStream input = Files.newInputStream(configPath)) {
            Map<String, Object> root = YamlValues.asMap(yaml.load(input), "root", configPath);
            Map<String, Object> resources = YamlValues.optionalMap(root, "resources", configPath);
            Map<String, Object> network = YamlValues.optionalMap(root, "network", configPath);
            Map<String, Object> ports = YamlValues.optionalMap(network, "ports", configPath);
            Map<String, Object> matchmaking =
                    YamlValues.optionalMap(root, "matchmaking", configPath);
            Map<String, Object> lifecycle =
                    YamlValues.optionalMap(root, "lifecycle", configPath);
            Map<String, Object> managedOutput =
                    YamlValues.optionalMap(root, "managed_output", configPath);
            Map<String, Object> forwarding =
                    YamlValues.optionalMap(root, "forwarding", configPath);
            Map<String, Object> security =
                    YamlValues.optionalMap(root, "security", configPath);
            Map<String, Object> lobby = YamlValues.optionalMap(root, "lobby", configPath);
            Map<String, Object> lobbyRecovery =
                    YamlValues.optionalMap(lobby, "recovery", configPath);
            if (lobby.containsKey("limbo") && lobby.containsKey("emergency")) {
                throw YamlValues.error(
                        configPath,
                        "'lobby.limbo' and deprecated 'lobby.emergency' "
                                + "cannot both be configured"
                );
            }
            Map<String, Object> limbo = lobby.containsKey("limbo")
                    ? YamlValues.optionalMap(lobby, "limbo", configPath)
                    : YamlValues.optionalMap(lobby, "emergency", configPath);
            Map<String, Object> paths = YamlValues.optionalMap(root, "paths", configPath);

            int totalMemory = YamlValues.optionalPositiveInt(
                    resources,
                    "total_memory_mib",
                    DEFAULT_TOTAL_MEMORY_MIB,
                    configPath
            );
            int portStart = YamlValues.optionalPositiveInt(
                    ports,
                    "start",
                    DEFAULT_PORT_RANGE_START,
                    configPath
            );
            int portEnd = YamlValues.optionalPositiveInt(
                    ports,
                    "end",
                    DEFAULT_PORT_RANGE_END,
                    configPath
            );
            int queueTimeout = YamlValues.optionalPositiveInt(
                    matchmaking,
                    "queue_timeout_seconds",
                    DEFAULT_QUEUE_TIMEOUT_SECONDS,
                    configPath
            );
            int idleShutdown = YamlValues.optionalNonNegativeInt(
                    lifecycle,
                    "idle_shutdown_seconds",
                    DEFAULT_IDLE_SHUTDOWN_SECONDS,
                    configPath
            );
            boolean mirrorManagedOutput = YamlValues.optionalBoolean(
                    managedOutput,
                    "mirror_to_proxy_console",
                    DEFAULT_MIRROR_MANAGED_OUTPUT,
                    configPath
            );
            boolean writeTemporaryLog = YamlValues.optionalBoolean(
                    managedOutput,
                    "write_temporary_file",
                    DEFAULT_WRITE_TEMPORARY_LOG,
                    configPath
            );
            int temporaryLogMaxKiB = YamlValues.optionalPositiveInt(
                    managedOutput,
                    "temporary_file_max_kib",
                    DEFAULT_TEMPORARY_LOG_MAX_KIB,
                    configPath
            );
            String forwardingMode = YamlValues.optionalString(
                    forwarding,
                    "mode",
                    DEFAULT_FORWARDING_MODE,
                    configPath
            );
            boolean forwardingOnlineMode = YamlValues.optionalBoolean(
                    forwarding,
                    "online_mode",
                    DEFAULT_FORWARDING_ONLINE_MODE,
                    configPath
            );
            String forwardingSecretFile = YamlValues.optionalString(
                    forwarding,
                    "secret_file",
                    DEFAULT_FORWARDING_SECRET_FILE,
                    configPath
            );
            boolean allowInsecureOfflineAdministrators = YamlValues.optionalBoolean(
                    security,
                    "allow_insecure_offline_administrators",
                    DEFAULT_ALLOW_INSECURE_OFFLINE_ADMINISTRATORS,
                    configPath
            );
            int claimCodeExpirySeconds = YamlValues.optionalPositiveInt(
                    security,
                    "claim_code_expiry_seconds",
                    DEFAULT_CLAIM_CODE_EXPIRY_SECONDS,
                    configPath
            );
            String lobbyMode = YamlValues.optionalString(
                    lobby,
                    "mode",
                    DEFAULT_LOBBY_MODE,
                    configPath
            );
            String lobbyRegistry = YamlValues.optionalString(
                    lobby,
                    "registry",
                    DEFAULT_LOBBY_REGISTRY,
                    configPath
            );
            String lobbyServer = YamlValues.optionalString(
                    lobby,
                    "server",
                    DEFAULT_LOBBY_SERVER,
                    configPath
            );
            int lobbyMaxRestartAttempts = YamlValues.optionalNonNegativeInt(
                    lobbyRecovery,
                    "max_attempts",
                    DEFAULT_LOBBY_MAX_RESTART_ATTEMPTS,
                    configPath
            );
            int lobbyInitialBackoff = YamlValues.optionalPositiveInt(
                    lobbyRecovery,
                    "initial_backoff_seconds",
                    DEFAULT_LOBBY_INITIAL_BACKOFF_SECONDS,
                    configPath
            );
            int lobbyMaxBackoff = YamlValues.optionalPositiveInt(
                    lobbyRecovery,
                    "max_backoff_seconds",
                    DEFAULT_LOBBY_MAX_BACKOFF_SECONDS,
                    configPath
            );
            int lobbyStableAfter = YamlValues.optionalPositiveInt(
                    lobbyRecovery,
                    "stable_after_seconds",
                    DEFAULT_LOBBY_STABLE_AFTER_SECONDS,
                    configPath
            );
            boolean limboEnabled = YamlValues.optionalBoolean(
                    limbo,
                    "enabled",
                    DEFAULT_LIMBO_ENABLED,
                    configPath
            );
            int limboMemory = YamlValues.optionalPositiveInt(
                    limbo,
                    "memory_mib",
                    DEFAULT_LIMBO_MEMORY_MIB,
                    configPath
            );
            int limboStartupTimeout = YamlValues.optionalPositiveInt(
                    limbo,
                    "startup_timeout_seconds",
                    DEFAULT_LIMBO_STARTUP_TIMEOUT_SECONDS,
                    configPath
            );
            String instances = YamlValues.optionalString(
                    paths,
                    "instances",
                    DEFAULT_INSTANCES_DIRECTORY,
                    configPath
            );

            Path instancesDirectory = resolveManagedPath(instances, "paths.instances");
            try {
                return new SLSConfig(
                        totalMemory,
                        portStart,
                        portEnd,
                        queueTimeout,
                        idleShutdown,
                        new ManagedOutputConfig(
                                mirrorManagedOutput,
                                writeTemporaryLog,
                                temporaryLogMaxKiB
                        ),
                        new ForwardingConfig(
                                ForwardingMode.parse(forwardingMode),
                                forwardingOnlineMode,
                                resolveProxyPath(
                                        forwardingSecretFile,
                                        "forwarding.secret_file"
                                )
                        ),
                        new SecurityConfig(
                                allowInsecureOfflineAdministrators,
                                claimCodeExpirySeconds
                        ),
                        new SLSLimboConfig(
                                limboEnabled,
                                limboMemory,
                                limboStartupTimeout
                        ),
                        new LobbyConfig(
                                LobbyMode.parse(lobbyMode),
                                lobbyRegistry,
                                lobbyServer,
                                lobbyMaxRestartAttempts,
                                lobbyInitialBackoff,
                                lobbyMaxBackoff,
                                lobbyStableAfter
                        ),
                        instancesDirectory
                );
            } catch (IllegalArgumentException exception) {
                throw YamlValues.error(configPath, exception.getMessage());
            }
        } catch (IOException exception) {
            throw new ConfigurationException("Unable to read " + configPath, exception);
        } catch (InvalidPathException exception) {
            throw new ConfigurationException(configPath + ": invalid path: "
                    + exception.getMessage(), exception);
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                    "Invalid YAML in " + configPath + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    private Path resolveManagedPath(String value, String key) throws ConfigurationException {
        Path configured = Path.of(value);
        if (configured.isAbsolute()) {
            throw YamlValues.error(configPath, "'" + key + "' must be relative");
        }

        Path resolved = dataDirectory.resolve(configured).normalize();
        if (!resolved.startsWith(dataDirectory)) {
            throw YamlValues.error(configPath, "'" + key + "' must stay inside "
                    + dataDirectory);
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
            throw YamlValues.error(
                    configPath,
                    "'" + key + "' must stay inside " + proxyDirectory
            );
        }
        return resolved;
    }

    private void installDefaultWhenMissing() throws IOException {
        if (Files.exists(configPath)) {
            return;
        }

        try (InputStream source = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (source == null) {
                throw new IOException("Bundled config.yml is missing");
            }
            Files.copy(source, configPath);
        }
    }
}
