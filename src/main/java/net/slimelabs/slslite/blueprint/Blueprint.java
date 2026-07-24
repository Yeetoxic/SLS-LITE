package net.slimelabs.slslite.blueprint;

import java.util.Map;

public record Blueprint(
        String id,
        String name,
        String type,
        String software,
        String version,
        int memoryLimitMiB,
        boolean save,
        Map<String, Object> annotations
) {

    public Blueprint {
        annotations = Map.copyOf(annotations);
    }
}
