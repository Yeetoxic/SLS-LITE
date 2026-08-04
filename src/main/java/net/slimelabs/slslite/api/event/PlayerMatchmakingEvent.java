package net.slimelabs.slslite.api.event;

import java.time.Instant;
import net.slimelabs.slslite.api.QueueTicket;

/**
 * Ordered notification for an accepted player-matchmaking state change.
 *
 * @param sequence provider-lifetime event sequence number
 * @param occurredAt state-change time
 * @param ticket immutable matchmaking ticket snapshot
 * @param instanceCreated whether this request provisioned its target instance
 * @param status new matchmaking status
 */
public record PlayerMatchmakingEvent(
    long sequence,
    Instant occurredAt,
    QueueTicket ticket,
    boolean instanceCreated,
    MatchmakingStatus status)
    implements SLSLiteEvent {

  public PlayerMatchmakingEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
    ticket = java.util.Objects.requireNonNull(ticket, "ticket");
    status = java.util.Objects.requireNonNull(status, "status");
  }
}
