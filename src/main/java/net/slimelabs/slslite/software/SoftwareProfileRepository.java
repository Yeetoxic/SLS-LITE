package net.slimelabs.slslite.software;

import net.slimelabs.slslite.config.ConfigurationException;
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

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final List<String> DEFAULT_JVM_ARGUMENTS =
            List.of("-Xms{memory_mib}M", "-Xmx{memory_mib}M");
    private static final List<String> DEFAULT_SERVER_ARGUMENTS = List.of("--nogui");
    private static final String DEFAULT_READINESS_PATTERN = "Done \\([^)]+\\)! For help";
    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 180;
    private static final String DEFAULT_STOP_COMMAND = "stop";
    private static final int DEFAULT_STOP_TIMEOUT_SECONDS = 30;

    private final Path directory;
    private volatile Map<String, SoftwareProfile> profiles = Map.of();

    public SoftwareProfileRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
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
        return new Snapshot(profiles);
    }

    public synchronized void install(Snapshot snapshot) {
        profiles = snapshot.values();
    }

    public Optional<SoftwareProfile> get(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public Collection<SoftwareProfile> getAll() {
        return profiles.values().stream()
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

            String id = YamlValues.requiredString(software, "id", path);
            if (!VALID_ID.matcher(id).matches()) {
                throw YamlValues.error(path, "software.id must match " + VALID_ID.pattern());
            }

            String javaExecutable = YamlValues.optionalString(
                    launch, "java", "java", path
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
                        javaExecutable,
                        baseDirectory,
                        serverJar,
                        jvmArguments,
                        serverArguments,
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

    private List<Path> profileFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
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
                .getResourceAsStream("paper-software.yml")) {
            if (source == null) {
                throw new IOException("Bundled paper-software.yml is missing");
            }
            Files.copy(source, directory.resolve("paper.yml"));
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
