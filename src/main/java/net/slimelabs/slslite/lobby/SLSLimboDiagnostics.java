package net.slimelabs.slslite.lobby;

import java.util.Optional;
import java.util.OptionalInt;

public record SLSLimboDiagnostics(
        boolean enabled,
        LobbyStatus status,
        int memoryMiB,
        OptionalInt port,
        int recoveryAttempts,
        int maxRecoveryAttempts,
        Optional<String> lastFailure
) {
}
