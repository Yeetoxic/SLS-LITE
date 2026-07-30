package net.slimelabs.slslite;

import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;

final class ProxyRecoveryTiming {

    private final LongSupplier nanoTime;
    private final long startedAt;
    private boolean reported;

    ProxyRecoveryTiming() {
        this(System::nanoTime);
    }

    ProxyRecoveryTiming(LongSupplier nanoTime) {
        this.nanoTime = java.util.Objects.requireNonNull(
                nanoTime,
                "nanoTime"
        );
        startedAt = nanoTime.getAsLong();
    }

    synchronized Optional<String> complete(String outcome, String primary) {
        if (reported) {
            return Optional.empty();
        }
        reported = true;
        long elapsed = Math.max(0L, nanoTime.getAsLong() - startedAt);
        String normalizedOutcome = outcome == null || outcome.isBlank()
                ? "unknown"
                : outcome.strip()
                        .replace(' ', '-')
                        .toLowerCase(Locale.ROOT);
        String normalizedPrimary = primary == null || primary.isBlank()
                ? "unavailable"
                : primary.strip();
        return Optional.of(
                "outcome=" + normalizedOutcome
                        + " total=" + String.format(
                                Locale.ROOT,
                                "%.3fms",
                                elapsed / 1_000_000.0d
                        )
                        + " primary=" + normalizedPrimary
        );
    }
}
