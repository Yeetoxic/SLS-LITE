package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/** Deduplicated status snapshot for the effective lobby service. */
public record LobbyStatusEvent(
    long sequence,
    Instant occurredAt,
    LobbyServiceStatus primaryStatus,
    LobbyServiceStatus holdingStatus,
    LobbyRoute route)
    implements SLSLiteEvent {

  public LobbyStatusEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(primaryStatus, "primaryStatus");
    Objects.requireNonNull(holdingStatus, "holdingStatus");
    Objects.requireNonNull(route, "route");
  }

  public boolean available() {
    return route != LobbyRoute.NONE;
  }
}
