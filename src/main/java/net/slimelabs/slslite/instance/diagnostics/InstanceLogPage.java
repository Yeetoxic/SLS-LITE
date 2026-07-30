package net.slimelabs.slslite.instance.diagnostics;

import java.util.List;

public record InstanceLogPage(
        List<String> lines,
        int totalRetainedLines,
        int retentionCapacity
) {

    public InstanceLogPage {
        lines = List.copyOf(lines);
    }
}
