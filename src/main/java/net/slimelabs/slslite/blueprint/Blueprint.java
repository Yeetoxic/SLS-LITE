package net.slimelabs.slslite.blueprint;

import java.util.List;
import java.util.Map;

public record Blueprint(
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

    public Blueprint {
        if (memoryLimitMiB <= 0 || maxPlayers <= 0 || maxInstances <= 0) {
            throw new IllegalArgumentException("Blueprint limits must be positive");
        }
        serverProperties = Map.copyOf(serverProperties);
        annotations = Map.copyOf(annotations);
        volumes = List.copyOf(volumes);
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
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
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
                memoryLimitMiB,
                maxPlayers,
                maxInstances,
                save,
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
                memoryLimitMiB,
                20,
                1,
                save,
                Map.of(),
                annotations,
                List.of()
        );
    }
}
