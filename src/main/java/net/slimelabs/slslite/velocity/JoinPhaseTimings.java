package net.slimelabs.slslite.velocity;

import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;

final class JoinPhaseTimings {

  private final LongSupplier nanoTime;
  private final long acceptedAt;
  private final boolean queueTracked;

  private Long readyAt;
  private Long transferStartedAt;
  private boolean reported;

  JoinPhaseTimings() {
    this(System::nanoTime, true);
  }

  JoinPhaseTimings(LongSupplier nanoTime) {
    this(nanoTime, true);
  }

  JoinPhaseTimings(boolean queueTracked) {
    this(System::nanoTime, queueTracked);
  }

  JoinPhaseTimings(LongSupplier nanoTime, boolean queueTracked) {
    this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
    this.queueTracked = queueTracked;
    acceptedAt = nanoTime.getAsLong();
  }

  synchronized void backendReady() {
    if (readyAt == null) {
      readyAt = nanoTime.getAsLong();
    }
  }

  synchronized void transferStarted() {
    backendReady();
    if (transferStartedAt == null) {
      transferStartedAt = nanoTime.getAsLong();
    }
  }

  synchronized Optional<String> complete(String outcome) {
    if (reported) {
      return Optional.empty();
    }
    reported = true;
    long completedAt = nanoTime.getAsLong();
    StringBuilder summary = new StringBuilder();
    summary.append("outcome=").append(normalizeOutcome(outcome));
    summary.append(" total=").append(formatMillis(elapsed(acceptedAt, completedAt)));
    Long queueFinishedAt =
        transferStartedAt != null ? transferStartedAt : readyAt != null ? readyAt : completedAt;
    if (queueTracked) {
      summary.append(" queue=").append(formatMillis(elapsed(acceptedAt, queueFinishedAt)));
    }
    if (transferStartedAt != null) {
      summary.append(" transfer=").append(formatMillis(elapsed(transferStartedAt, completedAt)));
    }
    return Optional.of(summary.toString());
  }

  private static long elapsed(long startedAt, long completedAt) {
    return Math.max(0L, completedAt - startedAt);
  }

  private static String formatMillis(long nanos) {
    return String.format(Locale.ROOT, "%.3fms", nanos / 1_000_000.0d);
  }

  private static String normalizeOutcome(String outcome) {
    if (outcome == null || outcome.isBlank()) {
      return "unknown";
    }
    return outcome.strip().replace(' ', '-').toLowerCase(Locale.ROOT);
  }
}
