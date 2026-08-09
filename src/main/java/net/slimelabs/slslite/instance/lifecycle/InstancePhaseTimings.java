package net.slimelabs.slslite.instance.lifecycle;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class InstancePhaseTimings {

  public enum Phase {
    DISPATCH_QUEUE("dispatch"),
    SOFTWARE_RESOLUTION("software"),
    FILE_PREPARATION("files"),
    CONFIGURATION("configuration"),
    PROCESS_LAUNCH("launch"),
    READINESS("readiness"),
    REGISTRATION("registration"),
    SHUTDOWN("shutdown"),
    CLEANUP("cleanup");

    private final String label;

    Phase(String label) {
      this.label = label;
    }
  }

  private static final Phase[] PROVISIONING_PHASES = {
    Phase.DISPATCH_QUEUE,
    Phase.SOFTWARE_RESOLUTION,
    Phase.FILE_PREPARATION,
    Phase.CONFIGURATION,
    Phase.PROCESS_LAUNCH,
    Phase.READINESS,
    Phase.REGISTRATION
  };
  private static final Phase[] TERMINATION_PHASES = {Phase.SHUTDOWN, Phase.CLEANUP};

  private final LongSupplier nanoTime;
  private final long acceptedAt;
  private final EnumMap<Phase, Long> startedAt = new EnumMap<>(Phase.class);
  private final EnumMap<Phase, Long> elapsedNanos = new EnumMap<>(Phase.class);

  private Long provisionedAt;
  private boolean provisioningReported;
  private boolean terminationReported;
  private boolean firstPlayerReported;

  public InstancePhaseTimings() {
    this(System::nanoTime);
  }

  InstancePhaseTimings(LongSupplier nanoTime) {
    this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
    acceptedAt = nanoTime.getAsLong();
    startedAt.put(Phase.DISPATCH_QUEUE, acceptedAt);
  }

  public synchronized void begin(Phase phase) {
    java.util.Objects.requireNonNull(phase, "phase");
    if (!elapsedNanos.containsKey(phase)) {
      startedAt.putIfAbsent(phase, nanoTime.getAsLong());
    }
  }

  public synchronized void finish(Phase phase) {
    java.util.Objects.requireNonNull(phase, "phase");
    if (elapsedNanos.containsKey(phase)) {
      return;
    }
    Long started = startedAt.remove(phase);
    if (started != null) {
      elapsedNanos.put(phase, nonNegativeElapsed(started, nanoTime.getAsLong()));
    }
  }

  public synchronized void provisioned() {
    if (provisionedAt == null) {
      provisionedAt = nanoTime.getAsLong();
    }
  }

  public synchronized Optional<String> provisioningSummary(String outcome) {
    if (provisioningReported) {
      return Optional.empty();
    }
    provisioningReported = true;
    long completedAt = provisionedAt == null ? nanoTime.getAsLong() : provisionedAt;
    return Optional.of(
        summary(outcome, nonNegativeElapsed(acceptedAt, completedAt), PROVISIONING_PHASES));
  }

  public synchronized Optional<String> terminationSummary(String outcome) {
    if (terminationReported) {
      return Optional.empty();
    }
    terminationReported = true;
    return Optional.of(
        summary(outcome, nonNegativeElapsed(acceptedAt, nanoTime.getAsLong()), TERMINATION_PHASES));
  }

  public synchronized Optional<Long> elapsedNanos(Phase phase) {
    return Optional.ofNullable(elapsedNanos.get(phase));
  }

  /** Returns the currently active phase without exposing mutable timing state. */
  public synchronized Optional<Phase> currentPhase() {
    return java.util.Arrays.stream(Phase.values()).filter(startedAt::containsKey).findFirst();
  }

  public synchronized Optional<Long> firstPlayerConnected() {
    if (firstPlayerReported) {
      return Optional.empty();
    }
    firstPlayerReported = true;
    long started = provisionedAt == null ? acceptedAt : provisionedAt;
    return Optional.of(nonNegativeElapsed(started, nanoTime.getAsLong()));
  }

  private String summary(String outcome, long totalNanos, Phase[] phases) {
    long observedAt = nanoTime.getAsLong();
    StringBuilder summary = new StringBuilder();
    summary.append("outcome=").append(normalizeOutcome(outcome));
    summary.append(" total=").append(formatMillis(totalNanos));
    for (Phase phase : phases) {
      Long elapsed = elapsedNanos.get(phase);
      if (elapsed == null) {
        Long started = startedAt.get(phase);
        if (started != null) {
          elapsed = nonNegativeElapsed(started, observedAt);
        }
      }
      if (elapsed != null) {
        summary.append(' ').append(phase.label).append('=').append(formatMillis(elapsed));
      }
    }
    return summary.toString();
  }

  private static long nonNegativeElapsed(long started, long finished) {
    return Math.max(0L, finished - started);
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
