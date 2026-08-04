package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Deduplicated status snapshot for the effective lobby service.
 *
 * @param sequence provider-lifetime event sequence number
 * @param occurredAt status-change time
 * @param primaryStatus configured primary lobby state
 * @param holdingStatus built-in holding-lobby state
 * @param route lobby tier selected for player routing
 */
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
