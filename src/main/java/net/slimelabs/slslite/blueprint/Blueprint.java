package net.slimelabs.slslite.blueprint;

import java.util.List;
import java.util.Map;

public record Blueprint(
        String id,
        String name,
        String type,
        String software,
        String version,
        String image,
        String softwarePath,
        int memoryLimitMiB,
        int maxPlayers,
        int maxInstances,
        boolean save,
        Map<String, String> serverProperties,
        Map<String, Map<String, Object>> yamlConfigs,
        Map<String, Map<String, String>> textFileConfigs,
        Map<String, Object> annotations,
        List<BlueprintVolume> volumes,
        List<BlueprintCopy> copies,
        Map<String, String> environment
) {

    public Blueprint {
        if (memoryLimitMiB <= 0 || maxPlayers <= 0 || maxInstances <= 0) {
            throw new IllegalArgumentException("Blueprint limits must be positive");
        }
        image = normalizeOptional(image);
        softwarePath = normalizeOptional(softwarePath);
        serverProperties = Map.copyOf(serverProperties);
        yamlConfigs = copyYamlConfigs(yamlConfigs);
        textFileConfigs = copyTextFileConfigs(textFileConfigs);
        annotations = Map.copyOf(annotations);
        volumes = List.copyOf(volumes);
        copies = List.copyOf(copies);
        environment = validateEnvironment(environment);
    }

    public Blueprint(
            String id,
            String name,
            String type,
            String software,
            String version,
            String image,
            String softwarePath,
            int memoryLimitMiB,
            int maxPlayers,
            int maxInstances,
            boolean save,
            Map<String, String> serverProperties,
            Map<String, Map<String, Object>> yamlConfigs,
            Map<String, Map<String, String>> textFileConfigs,
            Map<String, Object> annotations,
            List<BlueprintVolume> volumes
    ) {
        this(
                id,
                name,
                type,
                software,
                version,
                image,
                softwarePath,
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
                serverProperties,
                yamlConfigs,
                textFileConfigs,
                annotations,
                volumes,
                List.of(),
                Map.of()
        );
    }

    public Blueprint(
            String id,
            String name,
            String type,
            String software,
            String version,
            String image,
            String softwarePath,
            int memoryLimitMiB,
            int maxPlayers,
            int maxInstances,
            boolean save,
            Map<String, String> serverProperties,
            Map<String, Map<String, Object>> yamlConfigs,
            Map<String, Object> annotations,
            List<BlueprintVolume> volumes
    ) {
        this(
                id,
                name,
                type,
                software,
                version,
                image,
                softwarePath,
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
                serverProperties,
                yamlConfigs,
                Map.of(),
                annotations,
                volumes
        );
    }

    public Blueprint(
            String id,
            String name,
            String type,
            String software,
            String version,
            String image,
            String softwarePath,
            int memoryLimitMiB,
            int maxPlayers,
            int maxInstances,
            boolean save,
            Map<String, String> serverProperties,
            Map<String, Object> annotations,
            List<BlueprintVolume> volumes
    ) {
        this(
                id,
                name,
                type,
                software,
                version,
                image,
                softwarePath,
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
                serverProperties,
                Map.of(),
                Map.of(),
                annotations,
                volumes
        );
    }

    public Blueprint(
            String id,
            String name,
            String type,
            String software,
            String version,
            int memoryLimitMiB,
            int maxPlayers,
            int maxInstances,
            boolean save,
            Map<String, String> serverProperties,
            Map<String, Object> annotations,
            List<BlueprintVolume> volumes
    ) {
        this(
                id,
                name,
                type,
                software,
                version,
                null,
                null,
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
                serverProperties,
                Map.of(),
                Map.of(),
                annotations,
                volumes
        );
    }

    public Blueprint(
            String id,
            String name,
            String type,
            String software,
            String version,
            int memoryLimitMiB,
            int maxPlayers,
            int maxInstances,
            boolean save,
            Map<String, Object> annotations,
            List<BlueprintVolume> volumes
    ) {
        this(
                id,
                name,
                type,
                software,
                version,
                null,
                null,
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
                Map.of(),
                Map.of(),
                Map.of(),
                annotations,
                volumes
        );
    }

    public Blueprint(
            String id,
            String name,
            String type,
            String software,
            String version,
            int memoryLimitMiB,
            int maxPlayers,
            int maxInstances,
            boolean save,
            Map<String, Object> annotations
    ) {
        this(
                id,
                name,
                type,
                software,
                version,
                null,
                null,
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
                Map.of(),
                Map.of(),
                Map.of(),
                annotations,
                List.of()
        );
    }

    public Blueprint(
            String id,
            String name,
            String type,
            String software,
            String version,
            int memoryLimitMiB,
            boolean save,
            Map<String, Object> annotations
    ) {
        this(
                id,
                name,
                type,
                software,
                version,
                null,
                null,
                memoryLimitMiB,
                20,
                1,
                save,
                Map.of(),
                Map.of(),
                Map.of(),
                annotations,
                List.of()
        );
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Map<String, Object>> copyYamlConfigs(
            Map<String, Map<String, Object>> configured
    ) {
        java.util.LinkedHashMap<String, Map<String, Object>> copied =
                new java.util.LinkedHashMap<>();
        configured.forEach((target, values) -> copied.put(target, copyMap(values)));
        return Map.copyOf(copied);
    }

    private static Map<String, Map<String, String>> copyTextFileConfigs(
            Map<String, Map<String, String>> configured
    ) {
        java.util.LinkedHashMap<String, Map<String, String>> copied =
                new java.util.LinkedHashMap<>();
        configured.forEach((target, replacements) -> copied.put(
                target,
                java.util.Collections.unmodifiableMap(
                        new java.util.LinkedHashMap<>(replacements)
                )
        ));
        return java.util.Collections.unmodifiableMap(copied);
    }

    static Map<String, String> validateEnvironment(
            Map<String, String> configured
    ) {
        if (configured.size() > 64) {
            throw new IllegalArgumentException(
                    "Blueprint environment must not contain more than 64 variables"
            );
        }
        java.util.LinkedHashMap<String, String> normalized =
                new java.util.LinkedHashMap<>();
        java.util.HashSet<String> portableNames = new java.util.HashSet<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                throw new IllegalArgumentException(
                        "Invalid blueprint environment variable name: " + name
                );
            }
            String portableName = name.toUpperCase(java.util.Locale.ROOT);
            if (!portableNames.add(portableName)) {
                throw new IllegalArgumentException(
                        "Duplicate portable environment variable name: " + name
                );
            }
            if (isProtectedEnvironmentName(portableName)) {
                throw new IllegalArgumentException(
                        "Blueprint environment variable is protected: " + name
                );
            }
            if (value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "Blueprint environment value must not contain NUL: " + name
                );
            }
            int valueBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (valueBytes > 8 * 1024) {
                throw new IllegalArgumentException(
                        "Blueprint environment value exceeds 8192 bytes: " + name
                );
            }
            totalBytes += name.length() + valueBytes;
            if (totalBytes > 64 * 1024) {
                throw new IllegalArgumentException(
                        "Blueprint environment exceeds 65536 bytes"
                );
            }
            normalized.put(name, value);
        }
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private static boolean isProtectedEnvironmentName(String name) {
        return name.startsWith("SLS_")
                || name.startsWith("LD_")
                || name.startsWith("DYLD_")
                || java.util.Set.of(
                        "JAVA_TOOL_OPTIONS",
                        "_JAVA_OPTIONS",
                        "JDK_JAVA_OPTIONS",
                        "CLASSPATH",
                        "PATH",
                        "PATHEXT",
                        "COMSPEC",
                        "SYSTEMROOT",
                        "WINDIR"
                ).contains(name);
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        java.util.LinkedHashMap<String, Object> copied = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(key, copyValue(value)));
        return Map.copyOf(copied);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<String, Object> copied = new java.util.LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("YAML config keys must be strings");
                }
                copied.put(stringKey, copyValue(nested));
            });
            return Map.copyOf(copied);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Blueprint::copyValue).toList();
        }
        return value;
    }
}
