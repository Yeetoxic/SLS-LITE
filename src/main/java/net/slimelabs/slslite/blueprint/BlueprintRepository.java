package net.slimelabs.slslite.blueprint;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class BlueprintRepository {

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int DEFAULT_MEMORY_MIB = 1024;
    private static final int DEFAULT_MAX_PLAYERS = 20;
    private static final int DEFAULT_MAX_INSTANCES = 1;

    private final Path directory;
    private volatile Map<String, Blueprint> blueprints = Map.of();

    public BlueprintRepository(Path directory) {
        this.directory = directory;
    }

    public void initialize() throws IOException, BlueprintException {
        Files.createDirectories(directory);
        installTemplateWhenEmpty();
        reload();
    }

    public synchronized void reload() throws IOException, BlueprintException {
        install(loadSnapshot());
    }

    public Snapshot loadSnapshot() throws IOException, BlueprintException {
        Map<String, Blueprint> loaded = new LinkedHashMap<>();

        for (Path path : blueprintFiles()) {
            Blueprint blueprint = read(path);
            Blueprint previous = loaded.putIfAbsent(blueprint.id(), blueprint);
            if (previous != null) {
                throw new BlueprintException("Duplicate blueprint id '" + blueprint.id() + "'");
            }
        }
        return new Snapshot(loaded);
    }

    public Snapshot snapshot() {
        return new Snapshot(blueprints);
    }

    public synchronized void install(Snapshot snapshot) {
        blueprints = snapshot.values();
    }

    public Optional<Blueprint> get(String id) {
        return Optional.ofNullable(blueprints.get(id));
    }

    public Optional<Blueprint> get(String type, String id) {
        return get(id).filter(blueprint -> blueprint.type().equals(type));
    }

    public Collection<Blueprint> getAll() {
        return blueprints.values().stream()
                .sorted(java.util.Comparator.comparing(Blueprint::id))
                .toList();
    }

    public Collection<Blueprint> getByType(String type) {
        return blueprints.values().stream()
                .filter(blueprint -> blueprint.type().equals(type))
                .sorted(java.util.Comparator.comparing(Blueprint::id))
                .toList();
    }

    public Set<String> getTypes() {
        return blueprints.values().stream()
                .map(Blueprint::type)
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<Path> blueprintFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(BlueprintRepository::isYaml)
                    .sorted()
                    .toList();
        }
    }

    private void installTemplateWhenEmpty() throws IOException {
        if (!blueprintFiles().isEmpty()) {
            return;
        }

        try (InputStream source = getClass().getClassLoader().getResourceAsStream("template.yml")) {
            if (source == null) {
                throw new IOException("Bundled blueprint template.yml is missing");
            }
            Files.copy(source, directory.resolve("template.yml"));
        }
    }

    private Blueprint read(Path path) throws BlueprintException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        try (InputStream input = Files.newInputStream(path)) {
            Object document = yaml.load(input);
            Map<String, Object> root = asMap(document, "root", path);
            Map<String, Object> metadata = requiredMap(root, "blueprint", path);
            Map<String, Object> server = requiredMap(root, "server", path);
            Map<String, Object> limits = optionalMap(server, "limits", path);
            Map<String, Object> annotations = optionalMap(root, "annotations", path);

            String id = requiredString(metadata, "id", path);
            if (!VALID_ID.matcher(id).matches()) {
                throw error(path, "blueprint.id must match " + VALID_ID.pattern());
            }

            String name = requiredString(metadata, "name", path);
            String type = requiredString(metadata, "type", path);
            String software = requiredString(server, "software", path);
            String version = requiredString(server, "version", path);
            int memory = optionalPositiveInt(limits, "memory_limit", DEFAULT_MEMORY_MIB, path);
            int maxPlayers = optionalPositiveInt(
                    limits,
                    "max_players",
                    DEFAULT_MAX_PLAYERS,
                    path
            );
            int maxInstances = optionalPositiveInt(
                    limits,
                    "max_instances",
                    DEFAULT_MAX_INSTANCES,
                    path
            );
            boolean save = optionalBoolean(root, "save", false, path);

            return new Blueprint(
                    id,
                    name,
                    type,
                    software,
                    version,
                    memory,
                    maxPlayers,
                    maxInstances,
                    save,
                    annotations
            );
        } catch (IOException exception) {
            throw new BlueprintException("Unable to read blueprint " + path, exception);
        } catch (BlueprintException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BlueprintException("Invalid YAML in " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static Map<String, Object> requiredMap(
            Map<String, Object> parent,
            String key,
            Path path
    ) throws BlueprintException {
        if (!parent.containsKey(key)) {
            throw error(path, "missing required object '" + key + "'");
        }
        return asMap(parent.get(key), key, path);
    }

    private static Map<String, Object> optionalMap(
            Map<String, Object> parent,
            String key,
            Path path
    ) throws BlueprintException {
        if (!parent.containsKey(key)) {
            return Map.of();
        }
        return asMap(parent.get(key), key, path);
    }

    private static Map<String, Object> asMap(Object value, String key, Path path)
            throws BlueprintException {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw error(path, "'" + key + "' must be an object");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String stringKey)) {
                throw error(path, "'" + key + "' contains a non-string key");
            }
            result.put(stringKey, entry.getValue());
        }
        return result;
    }

    private static String requiredString(Map<String, Object> values, String key, Path path)
            throws BlueprintException {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw error(path, "missing required value '" + key + "'");
        }
        return value.toString().trim();
    }

    private static int optionalPositiveInt(
            Map<String, Object> values,
            String key,
            int defaultValue,
            Path path
    ) throws BlueprintException {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
                || number.intValue() <= 0
                || number.doubleValue() != number.intValue()) {
            throw error(path, "'" + key + "' must be a positive integer");
        }
        return number.intValue();
    }

    private static boolean optionalBoolean(
            Map<String, Object> values,
            String key,
            boolean defaultValue,
            Path path
    ) throws BlueprintException {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw error(path, "'" + key + "' must be true or false");
        }
        return booleanValue;
    }

    private static BlueprintException error(Path path, String message) {
        return new BlueprintException(path + ": " + message);
    }

    public record Snapshot(Map<String, Blueprint> values) {
        public Snapshot {
            values = Map.copyOf(values);
        }

        public Collection<Blueprint> getAll() {
            return values.values();
        }
    }
}
