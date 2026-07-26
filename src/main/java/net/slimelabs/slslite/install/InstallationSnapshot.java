package net.slimelabs.slslite.install;

import java.time.Instant;
import java.util.List;

public record InstallationSnapshot(
        InstallationKey key,
        InstallationState state,
        String detail,
        Instant startedAt,
        Instant completedAt,
        List<String> logs
) {
    public InstallationSnapshot {
        logs = List.copyOf(logs);
    }
}
