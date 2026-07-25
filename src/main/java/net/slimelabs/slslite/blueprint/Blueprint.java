package net.slimelabs.slslite.blueprint;

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
        Map<String, Object> annotations
) {

    public Blueprint {
        if (memoryLimitMiB <= 0 || maxPlayers <= 0 || maxInstances <= 0) {
            throw new IllegalArgumentException("Blueprint limits must be positive");
        }
        annotations = Map.copyOf(annotations);
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
        this(id, name, type, software, version, memoryLimitMiB, 20, 1, save, annotations);
    }
}
