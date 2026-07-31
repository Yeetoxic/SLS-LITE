package net.slimelabs.slslite.software;

import net.slimelabs.slslite.config.ConfigurationException;
import net.slimelabs.slslite.config.DefinitionCatalog;
import net.slimelabs.slslite.config.YamlValues;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class SoftwareProfileRepository {

    private static final String DEFAULT_PAPER_RESOURCE =
            "defaults/software/paper-software.yml";
    private static final String DEFAULT_VANILLA_RESOURCE =
            "defaults/software/vanilla-software.yml";

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final List<String> DEFAULT_JVM_ARGUMENTS =
            List.of("-Xms128M", "-Xmx{memory_mib}M");
    private static final List<String> DEFAULT_SERVER_ARGUMENTS = List.of();
    private static final String DEFAULT_READINESS_PATTERN = "Done \\([^)]+\\)! For help";
    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 180;
    private static final String DEFAULT_STOP_COMMAND = "stop";
    private static final int DEFAULT_STOP_TIMEOUT_SECONDS = 30;

    private final Path directory;
    private final DefinitionCatalog catalog;

    public SoftwareProfileRepository(Path directory) {
        this(directory, new DefinitionCatalog());
    }

    public SoftwareProfileRepository(Path directory, DefinitionCatalog catalog) {
        this.directory = directory.toAbsolutePath().normalize();
        this.catalog = catalog;
    }

    public void initialize() throws IOException, ConfigurationException {
        Files.createDirectories(directory);
        installTemplateWhenEmpty();
        reload();
    }

    public synchronized void reload() throws IOException, ConfigurationException {
        install(loadSnapshot());
    }

    public Snapshot loadSnapshot() throws IOException, ConfigurationException {
        Map<String, SoftwareProfile> loaded = new LinkedHashMap<>();
        for (Path path : profileFiles()) {
            SoftwareProfile profile = read(path);
            SoftwareProfile previous = loaded.putIfAbsent(profile.id(), profile);
            if (previous != null) {
                throw new ConfigurationException(
                        "Duplicate software profile id '" + profile.id() + "'"
                );
            }
        }
        return new Snapshot(loaded);
    }

    public Snapshot snapshot() {
        return new Snapshot(catalog.snapshot().softwareProfiles());
    }

    public synchronized void install(Snapshot snapshot) {
        catalog.installSoftwareProfiles(snapshot.values());
    }

    public DefinitionCatalog catalog() {
        return catalog;
    }

    public Optional<SoftwareProfile> get(String id) {
        return Optional.ofNullable(catalog.snapshot().softwareProfiles().get(id));
    }

    public Collection<SoftwareProfile> getAll() {
        return catalog.snapshot().softwareProfiles().values().stream()
                .sorted(Comparator.comparing(SoftwareProfile::id))
                .toList();
    }

    private SoftwareProfile read(Path path) throws ConfigurationException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        try (InputStream input = Files.newInputStream(path)) {
            Map<String, Object> root = YamlValues.asMap(yaml.load(input), "root", path);
            Map<String, Object> software = YamlValues.optionalMap(root, "software", path);
            Map<String, Object> launch = YamlValues.optionalMap(root, "launch", path);
            Map<String, Object> readiness = YamlValues.optionalMap(root, "readiness", path);
            Map<String, Object> shutdown = YamlValues.optionalMap(root, "shutdown", path);
            if (ModernSLSSoftwareAdapter.supports(software)) {
                return ModernSLSSoftwareAdapter.adapt(root, software, path);
            }
            YamlValues.requireOnlyKeys(
                    root,
                    "",
                    path,
                    "software", "launch", "readiness", "shutdown"
            );
            YamlValues.requireOnlyKeys(
                    software,
                    "software",
                    path,
                    "id", "name", "runtime", "configurator", "source", "channel",
                    "accept_eula",
                    "base_directory", "server_jar"
            );
            YamlValues.requireOnlyKeys(
                    launch,
                    "launch",
                    path,
                    "java", "java_versions", "jvm_arguments", "server_arguments"
            );
            YamlValues.requireOnlyKeys(
                    readiness,
                    "readiness",
                    path,
                    "pattern", "timeout_seconds"
            );
            YamlValues.requireOnlyKeys(
                    shutdown,
                    "shutdown",
                    path,
                    "command", "timeout_seconds"
            );

            String id = YamlValues.requiredString(software, "id", path);
            if (!VALID_ID.matcher(id).matches()) {
                throw YamlValues.error(path, "software.id must match " + VALID_ID.pattern());
            }
            String name = YamlValues.optionalString(software, "name", id, path);

            String javaExecutable = YamlValues.optionalString(
                    launch, "java", "java", path
            );
            Map<Integer, String> javaExecutables = javaExecutables(
                    YamlValues.optionalMap(launch, "java_versions", path),
                    path
            );
            SoftwareRuntime runtime = enumValue(
                    YamlValues.optionalString(
                            software, "runtime", "java-jar", path
                    ),
                    SoftwareRuntime.class,
                    "software.runtime",
                    path
            );
            SoftwareConfigurator configurator = enumValue(
                    YamlValues.optionalString(
                            software, "configurator", "paper", path
                    ),
                    SoftwareConfigurator.class,
                    "software.configurator",
                    path
            );
            SoftwareSource source = enumValue(
                    YamlValues.optionalString(
                            software, "source", "manual", path
                    ),
                    SoftwareSource.class,
                    "software.source",
                    path
            );
            SoftwareReleaseChannel channel = enumValue(
                    YamlValues.optionalString(
                            software, "channel", "stable", path
                    ),
                    SoftwareReleaseChannel.class,
                    "software.channel",
                    path
            );
            if (source != SoftwareSource.PAPER
                    && channel != SoftwareReleaseChannel.STABLE) {
                throw YamlValues.error(
                        path,
                        "software.channel is only configurable for source: paper"
                );
            }
            boolean acceptEula = YamlValues.optionalBoolean(
                    software,
                    "accept_eula",
                    false,
                    path
            );
            String baseDirectory = YamlValues.requiredString(
                    software, "base_directory", path
            );
            String serverJar = YamlValues.requiredString(software, "server_jar", path);
            validateRelativeTemplate(baseDirectory, "software.base_directory", path);
            validateRelativeTemplate(serverJar, "software.server_jar", path);

            List<String> jvmArguments = YamlValues.optionalStringList(
                    launch, "jvm_arguments", DEFAULT_JVM_ARGUMENTS, path
            );
            List<String> serverArguments = YamlValues.optionalStringList(
                    launch, "server_arguments", DEFAULT_SERVER_ARGUMENTS, path
            );
            String readinessPattern = YamlValues.optionalString(
                    readiness, "pattern", DEFAULT_READINESS_PATTERN, path
            );
            int startupTimeout = YamlValues.optionalPositiveInt(
                    readiness, "timeout_seconds", DEFAULT_STARTUP_TIMEOUT_SECONDS, path
            );
            String stopCommand = YamlValues.optionalString(
                    shutdown, "command", DEFAULT_STOP_COMMAND, path
            );
            int stopTimeout = YamlValues.optionalPositiveInt(
                    shutdown, "timeout_seconds", DEFAULT_STOP_TIMEOUT_SECONDS, path
            );

            try {
                return new SoftwareProfile(
                        id,
                        name,
                        runtime,
                        configurator,
                        source,
                        channel,
                        acceptEula,
                        javaExecutable,
                        javaExecutables,
                        baseDirectory,
                        serverJar,
                        jvmArguments,
                        serverArguments,
                        Map.of(),
                        readinessPattern,
                        startupTimeout,
                        stopCommand,
                        stopTimeout
                );
            } catch (PatternSyntaxException exception) {
                throw YamlValues.error(path, "readiness.pattern is not a valid regular expression");
            }
        } catch (IOException exception) {
            throw new ConfigurationException("Unable to read software profile " + path, exception);
        } catch (InvalidPathException exception) {
            throw new ConfigurationException(path + ": invalid path: "
                    + exception.getMessage(), exception);
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                    "Invalid YAML in " + path + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    private static void validateRelativeTemplate(String value, String key, Path path)
            throws ConfigurationException {
        Path configured = Path.of(value.replace("{version}", "version"));
        if (configured.isAbsolute() || configured.normalize().startsWith("..")) {
            throw YamlValues.error(path, "'" + key + "' must be a relative path without traversal");
        }
    }

    private static <E extends Enum<E>> E enumValue(
            String value,
            Class<E> type,
            String key,
            Path path
    ) throws ConfigurationException {
        try {
            return Enum.valueOf(
                    type,
                    value.replace('-', '_').toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw YamlValues.error(
                    path,
                    key + " has unsupported value '" + value + "'"
            );
        }
    }

    private static Map<Integer, String> javaExecutables(
            Map<String, Object> values,
            Path path
    ) throws ConfigurationException {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            int major;
            try {
                major = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException exception) {
                throw YamlValues.error(
                        path,
                        "launch.java_versions keys must be Java major versions"
                );
            }
            if (major < 8 || !(entry.getValue() instanceof String executable)
                    || executable.isBlank()) {
                throw YamlValues.error(
                        path,
                        "launch.java_versions." + entry.getKey()
                                + " must be a non-empty Java executable"
                );
            }
            result.put(major, executable);
        }
        return Map.copyOf(result);
    }

    private List<Path> profileFiles() throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(SoftwareProfileRepository::isYaml)
                    .sorted()
                    .toList();
        }
    }

    private void installTemplateWhenEmpty() throws IOException {
        if (!profileFiles().isEmpty()) {
            return;
        }

        try (InputStream source = getClass().getClassLoader()
                .getResourceAsStream(DEFAULT_PAPER_RESOURCE)) {
            if (source == null) {
                throw new IOException(
                        "Bundled Paper software default is missing: "
                                + DEFAULT_PAPER_RESOURCE
                );
            }
            Files.copy(source, directory.resolve("paper.yml"));
        }
        try (InputStream source = getClass().getClassLoader()
                .getResourceAsStream(DEFAULT_VANILLA_RESOURCE)) {
            if (source == null) {
                throw new IOException(
                        "Bundled vanilla software default is missing: "
                                + DEFAULT_VANILLA_RESOURCE
                );
            }
            Files.copy(source, directory.resolve("vanilla.yml"));
        }
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    public record Snapshot(Map<String, SoftwareProfile> values) {
        public Snapshot {
            values = Map.copyOf(values);
        }

        public Collection<SoftwareProfile> getAll() {
            return values.values();
        }
    }
}
