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
        Map<String, Object> annotations,
        List<BlueprintVolume> volumes
) {

    public Blueprint {
        if (memoryLimitMiB <= 0 || maxPlayers <= 0 || maxInstances <= 0) {
            throw new IllegalArgumentException("Blueprint limits must be positive");
        }
        image = normalizeOptional(image);
        softwarePath = normalizeOptional(softwarePath);
        serverProperties = Map.copyOf(serverProperties);
        yamlConfigs = copyYamlConfigs(yamlConfigs);
        annotations = Map.copyOf(annotations);
        volumes = List.copyOf(volumes);
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
