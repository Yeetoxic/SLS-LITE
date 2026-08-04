package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Final event emitted before the SLS-LITE API dispatcher closes.
 *
 * @param sequence provider-lifetime event sequence number
 * @param occurredAt shutdown-event publication time
 */
public record ApiShutdownEvent(long sequence, Instant occurredAt) implements SLSLiteEvent {

  public ApiShutdownEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
