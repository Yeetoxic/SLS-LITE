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
    private static final String DEFAULT_INSTANCES_DIRECTORY = "instances";

    private final Path dataDirectory;
    private final Path configPath;
    private volatile SLSConfig config;

    public SLSConfigRepository(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
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
            String instances = YamlValues.optionalString(
                    paths,
                    "instances",
                    DEFAULT_INSTANCES_DIRECTORY,
                    configPath
            );

            Path instancesDirectory = resolveManagedPath(instances, "paths.instances");
            try {
                return new SLSConfig(totalMemory, portStart, portEnd, instancesDirectory);
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
