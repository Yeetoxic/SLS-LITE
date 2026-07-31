package net.slimelabs.slslite.blueprint;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class BlueprintParser {

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern VALID_PROPERTY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final int DEFAULT_MEMORY_MIB = 1024;
    private static final int DEFAULT_MAX_PLAYERS = 20;
    private static final int DEFAULT_MAX_INSTANCES = 1;

    Blueprint parse(Path path) throws BlueprintException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        try (InputStream input = Files.newInputStream(path)) {
            Object document = yaml.load(input);
            Map<String, Object> root = asMap(document, "root", path);
            Map<String, Object> metadata = requiredMap(root, "blueprint", path);
            Map<String, Object> server = requiredMap(root, "server", path);
            Map<String, Object> limits = optionalMap(server, "limits", path);
            ParsedConfigs parsedConfigs = parseConfigs(server, path);
            Map<String, Object> state = optionalMap(root, "state", path);
            Map<String, Object> annotations = optionalMap(root, "annotations", path);
            VSLSBlueprintAnnotations.validate(annotations);
            if (state.containsKey("mounts")) {
                throw error(
                        path,
                        "'state.mounts' is not available in local mode; use a "
                                + "contained state.volume with mode cow or ro"
                );
            }
            requireOnlyKeys(
                    root,
                    "",
                    path,
                    "blueprint", "server", "state", "save", "annotations"
            );
            requireOnlyKeys(metadata, "blueprint", path, "id", "name", "type");
            requireOnlyKeys(
                    server,
                    "server",
                    path,
                    "software", "version", "image", "path", "limits", "configs"
            );
            requireOnlyKeys(state, "state", path, "volumes", "copy", "env");
            requireOnlyKeys(
                    limits,
                    "server.limits",
                    path,
                    "memory_limit", "max_players", "max_instances", "swap",
                    "io_weight", "cpu_limit", "disk_space", "threads",
                    "oom_disabled"
            );
            validateDistributedLimits(limits, path);

            String id = requiredString(metadata, "id", path);
            if (!VALID_ID.matcher(id).matches()) {
                throw error(path, "blueprint.id must match " + VALID_ID.pattern());
            }

            String name = requiredString(metadata, "name", path);
            String type = requiredString(metadata, "type", path);
            String software = requiredString(server, "software", path);
            String version = requiredString(server, "version", path);
            String image = optionalString(server, "image", path);
            String softwarePath = optionalString(server, "path", path);
            validateRelativePath(softwarePath, "server.path", path);
            int memory = optionalPositiveInt(limits, "memory_limit", DEFAULT_MEMORY_MIB, path);
            int maxPlayers = optionalPositiveInt(
                    limits,
                    "max_players",
                    VSLSBlueprintAnnotations.maxPlayers(annotations)
                            .orElse(DEFAULT_MAX_PLAYERS),
                    path
            );
            int maxInstances = optionalPositiveInt(
                    limits,
                    "max_instances",
                    VSLSBlueprintAnnotations.maxInstances(annotations)
                            .orElse(DEFAULT_MAX_INSTANCES),
                    path
            );
            boolean save = optionalBoolean(root, "save", false, path);
            List<BlueprintVolume> volumes = parseVolumes(state, path);
            List<BlueprintCopy> copies = parseCopies(state, path);
            Map<String, String> environment = parseEnvironment(state, path);

            return new Blueprint(
                    id,
                    name,
                    type,
                    software,
                    version,
                    image,
                    softwarePath,
                    memory,
                    maxPlayers,
                    maxInstances,
                    save,
                    parsedConfigs.serverProperties(),
                    parsedConfigs.yamlConfigs(),
                    parsedConfigs.textFileConfigs(),
                    annotations,
                    volumes,
                    copies,
                    environment,
                    !limits.containsKey("memory_limit"),
                    image == null
            );
        } catch (IOException exception) {
            throw new BlueprintException("Unable to read blueprint " + path, exception);
        } catch (BlueprintException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BlueprintException("Invalid YAML in " + path + ": " + exception.getMessage(), exception);
        }
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

    private static String optionalString(
            Map<String, Object> values,
            String key,
            Path path
    ) throws BlueprintException {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw error(path, "'" + key + "' must be a non-blank string");
        }
        return stringValue.trim();
    }

    private static void validateRelativePath(String value, String key, Path path)
            throws BlueprintException {
        if (value == null) {
            return;
        }
        try {
            Path configured = Path.of(value);
            if (configured.isAbsolute() || configured.normalize().startsWith("..")) {
                throw error(path, "'" + key + "' must be a contained relative path");
            }
        } catch (java.nio.file.InvalidPathException exception) {
            throw error(path, "'" + key + "' is not a valid path");
        }
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

    private static void validateDistributedLimits(
            Map<String, Object> limits,
            Path path
    ) throws BlueprintException {
        for (String key : List.of("swap", "io_weight", "cpu_limit", "disk_space")) {
            Object value = limits.get(key);
            if (value != null && (!(value instanceof Number number)
                    || number.intValue() < 0
                    || number.doubleValue() != number.intValue())) {
                throw error(path, "'" + key + "' must be a non-negative integer");
            }
        }
        Object threads = limits.get("threads");
        if (threads != null && !(threads instanceof String)) {
            throw error(path, "'threads' must be a string");
        }
        Object oomDisabled = limits.get("oom_disabled");
        if (oomDisabled != null && !(oomDisabled instanceof Boolean)) {
            throw error(path, "'oom_disabled' must be true or false");
        }
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

    private static List<BlueprintVolume> parseVolumes(
            Map<String, Object> state,
            Path path
    ) throws BlueprintException {
        Object configured = state.get("volumes");
        if (configured == null) {
            return List.of();
        }
        if (!(configured instanceof List<?> rawVolumes)) {
            throw error(path, "'state.volumes' must be a list");
        }

        java.util.ArrayList<BlueprintVolume> volumes = new java.util.ArrayList<>();
        for (int index = 0; index < rawVolumes.size(); index++) {
            String section = "state.volumes[" + index + "]";
            BlueprintVolume parsed = rawVolumes.get(index) instanceof String shorthand
                    ? parseVolumeShorthand(shorthand, section, path)
                    : parseVolumeMap(rawVolumes.get(index), section, path);
            volumes.add(parsed);
        }
        return List.copyOf(volumes);
    }

    private static List<BlueprintCopy> parseCopies(
            Map<String, Object> state,
            Path path
    ) throws BlueprintException {
        Object configured = state.get("copy");
        if (configured == null) {
            return List.of();
        }
        if (!(configured instanceof List<?> rawCopies)) {
            throw error(path, "'state.copy' must be a list");
        }
        if (rawCopies.size() > 128) {
            throw error(path, "'state.copy' must not contain more than 128 entries");
        }

        java.util.ArrayList<BlueprintCopy> copies = new java.util.ArrayList<>();
        for (int index = 0; index < rawCopies.size(); index++) {
            String section = "state.copy[" + index + "]";
            Object rawCopy = rawCopies.get(index);
            if (rawCopy instanceof String shorthand) {
                String[] parts = shorthand.split(":", 2);
                if (parts.length != 2
                        || parts[0].isBlank()
                        || parts[1].isBlank()) {
                    throw error(path, "'" + section + "' must be source:target");
                }
                copies.add(copy(parts[0], parts[1], section, path));
            } else {
                Map<String, Object> values = asMap(rawCopy, section, path);
                requireOnlyKeys(values, section, path, "source", "target");
                copies.add(copy(
                        requiredString(values, "source", path),
                        requiredString(values, "target", path),
                        section,
                        path
                ));
            }
        }
        return List.copyOf(copies);
    }

    private static BlueprintCopy copy(
            String source,
            String target,
            String section,
            Path path
    ) throws BlueprintException {
        String normalizedSource = source.trim();
        String normalizedTarget = target.trim();
        validateRelativePath(normalizedSource, section + ".source", path);
        validateRelativePath(normalizedTarget, section + ".target", path);
        if (normalizedSource.indexOf('\\') >= 0 || normalizedTarget.indexOf('\\') >= 0) {
            throw error(path, "'" + section + "' must use portable '/' separators");
        }
        return new BlueprintCopy(normalizedSource, normalizedTarget);
    }

    private static Map<String, String> parseEnvironment(
            Map<String, Object> state,
            Path path
    ) throws BlueprintException {
        Map<String, Object> configured = optionalMap(state, "env", path);
        if (configured.isEmpty()) {
            return Map.of();
        }
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : configured.entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw error(
                        path,
                        "'state.env." + entry.getKey() + "' must be a string"
                );
            }
            environment.put(entry.getKey(), value);
        }
        try {
            return Blueprint.validateEnvironment(environment);
        } catch (IllegalArgumentException exception) {
            throw error(path, exception.getMessage());
        }
    }

    private static BlueprintVolume parseVolumeMap(
            Object configured,
            String section,
            Path path
    ) throws BlueprintException {
        Map<String, Object> volume = asMap(configured, section, path);
        requireOnlyKeys(volume, section, path, "name", "source", "target", "mode");
        String name = requiredString(volume, "name", path);
        String source = requiredString(volume, "source", path);
        String target = requiredString(volume, "target", path);
        String mode = optionalString(volume, "mode", path);
        return volume(name, source, target, mode == null ? "cow" : mode, section, path);
    }

    private static BlueprintVolume parseVolumeShorthand(
            String configured,
            String section,
            Path path
    ) throws BlueprintException {
        String[] parts = configured.split(":", -1);
        if (parts.length != 3 && parts.length != 4) {
            throw error(
                    path,
                    "'" + section + "' shorthand must be name:source:target[:mode]"
            );
        }
        for (int index = 0; index < parts.length; index++) {
            parts[index] = parts[index].trim();
            if (parts[index].isEmpty()) {
                throw error(path, "'" + section + "' shorthand contains a blank segment");
            }
        }
        return volume(
                parts[0],
                parts[1],
                parts[2],
                parts.length == 4 ? parts[3] : "cow",
                section,
                path
        );
    }

    private static BlueprintVolume volume(
            String name,
            String source,
            String target,
            String mode,
            String section,
            Path path
    ) throws BlueprintException {
        BlueprintVolume.Mode parsedMode;
        try {
            parsedMode = BlueprintVolume.Mode.valueOf(
                    mode.trim().toUpperCase(java.util.Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw error(path, "'" + section + ".mode' must be cow, ro, or rw");
        }
        return new BlueprintVolume(
                name,
                source,
                target,
                parsedMode
        );
    }

    private static ParsedConfigs parseConfigs(
            Map<String, Object> server,
            Path path
    ) throws BlueprintException {
        Map<String, Object> configs = optionalMap(server, "configs", path);
        if (configs.isEmpty()) {
            return new ParsedConfigs(Map.of(), Map.of(), Map.of());
        }

        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, Map<String, Object>> yamlConfigs = new LinkedHashMap<>();
        Map<String, Map<String, String>> textFileConfigs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : configs.entrySet()) {
            String target = entry.getKey();
            if (target.isBlank()) {
                throw error(path, "'server.configs target' must not be blank");
            }
            validateRelativePath(target, "server.configs target", path);
            Map<String, Object> config = asMap(
                    entry.getValue(),
                    "server.configs." + target,
                    path
            );
            requireOnlyKeys(
                    config,
                    "server.configs." + target,
                    path,
                    "parser", "find"
            );
            String parser = requiredString(config, "parser", path);
            Map<String, Object> find = optionalMap(config, "find", path);
            if (parser.equalsIgnoreCase("properties")) {
                if (!target.equals("server.properties")) {
                    throw error(
                            path,
                            "properties config target '" + target
                                    + "' is not supported; use server.properties"
                    );
                }
                properties.putAll(parseProperties(find, path));
            } else if (parser.equalsIgnoreCase("yaml")) {
                String lowerTarget = target.toLowerCase(Locale.ROOT);
                if (!lowerTarget.endsWith(".yml") && !lowerTarget.endsWith(".yaml")) {
                    throw error(path, "YAML config target must end in .yml or .yaml");
                }
                yamlConfigs.put(target, validateYamlMap(find, target, path));
            } else if (parser.equalsIgnoreCase("file")) {
                textFileConfigs.put(target, parseTextFileReplacements(
                        find,
                        target,
                        path
                ));
            } else {
                throw error(
                        path,
                        "unsupported parser '" + parser + "' for server.configs." + target
                );
            }
        }
        return new ParsedConfigs(
                Map.copyOf(properties),
                Map.copyOf(yamlConfigs),
                java.util.Collections.unmodifiableMap(textFileConfigs)
        );
    }

    private static Map<String, String> parseTextFileReplacements(
            Map<String, Object> configured,
            String target,
            Path path
    ) throws BlueprintException {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : configured.entrySet()) {
            String match = entry.getKey();
            if (match.isEmpty() || match.contains("\n") || match.contains("\r")) {
                throw error(
                        path,
                        "'server.configs." + target
                                + ".find' keys must be non-empty single-line prefixes"
                );
            }
            Object value = entry.getValue();
            if (!(value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean)) {
                throw error(
                        path,
                        "'server.configs." + target + ".find." + match
                                + "' must be a string, number, or boolean"
                );
            }
            replacements.put(match, value.toString());
        }
        List<String> prefixes = List.copyOf(replacements.keySet());
        for (int left = 0; left < prefixes.size(); left++) {
            for (int right = left + 1; right < prefixes.size(); right++) {
                String first = prefixes.get(left);
                String second = prefixes.get(right);
                if (first.startsWith(second) || second.startsWith(first)) {
                    throw error(
                            path,
                            "'server.configs." + target
                                    + ".find' prefixes overlap: '" + first
                                    + "' and '" + second + "'"
                    );
                }
            }
        }
        return java.util.Collections.unmodifiableMap(replacements);
    }

    private static Map<String, String> parseProperties(
            Map<String, Object> configured,
            Path path
    ) throws BlueprintException {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : configured.entrySet()) {
            String key = entry.getKey();
            if (!VALID_PROPERTY_KEY.matcher(key).matches()) {
                throw error(path, "invalid server.properties key '" + key + "'");
            }
            Object value = entry.getValue();
            if (!(value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean)) {
                throw error(
                        path,
                        "'server.configs.server.properties.find." + key
                                + "' must be a string, number, or boolean"
                );
            }
            String rendered = value.toString();
            if (rendered.contains("\n") || rendered.contains("\r")) {
                throw error(
                        path,
                        "'server.configs.server.properties.find." + key
                                + "' must be a single-line value"
                );
            }
            properties.put(key, rendered);
        }
        return Map.copyOf(properties);
    }

    private static Map<String, Object> validateYamlMap(
            Map<String, Object> configured,
            String target,
            Path path
    ) throws BlueprintException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : configured.entrySet()) {
            result.put(
                    entry.getKey(),
                    validateYamlValue(
                            entry.getValue(),
                            "server.configs." + target + ".find." + entry.getKey(),
                            path
                    )
            );
        }
        return Map.copyOf(result);
    }

    private static Object validateYamlValue(
            Object value,
            String key,
            Path path
    ) throws BlueprintException {
        if (value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value == null) {
            throw error(path, "'" + key + "' must not be null");
        }
        if (value instanceof Map<?, ?>) {
            return validateYamlMap(asMap(value, key, path), key, path);
        }
        if (value instanceof List<?> list) {
            java.util.ArrayList<Object> values = new java.util.ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                values.add(validateYamlValue(
                        list.get(index),
                        key + "[" + index + "]",
                        path
                ));
            }
            return List.copyOf(values);
        }
        throw error(path, "'" + key + "' contains an unsupported YAML value");
    }

    private static void requireOnlyKeys(
            Map<String, Object> values,
            String section,
            Path path,
            String... allowedKeys
    ) throws BlueprintException {
        Set<String> allowed = Set.of(allowedKeys);
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw error(
                        path,
                        net.slimelabs.slslite.config.YamlValues.unknownKeyMessage(
                                section,
                                key,
                                allowed
                        )
                );
            }
        }
    }

    private static BlueprintException error(Path path, String message) {
        return new BlueprintException(path + ": " + message);
    }

    private record ParsedConfigs(
            Map<String, String> serverProperties,
            Map<String, Map<String, Object>> yamlConfigs,
            Map<String, Map<String, String>> textFileConfigs
    ) {
    }

}
