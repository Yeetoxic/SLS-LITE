package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/** Final event emitted before the SLS-LITE API dispatcher closes. */
public record ApiShutdownEvent(long sequence, Instant occurredAt) implements SLSLiteEvent {

  public ApiShutdownEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
