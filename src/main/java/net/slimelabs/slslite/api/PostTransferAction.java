package net.slimelabs.slslite.api;

import java.time.Instant;

/**
 * Immutable input delivered after a queued request actually moves a player.
 *
 * @param ticket terminal matchmaking ticket identifying player and instance
 * @param instanceCreated whether this request provisioned the target instance
 * @param annotations annotation data owned by the receiving extension namespace
 * @param occurredAt time at which Velocity reported transfer success
 */
public record PostTransferAction(
    QueueTicket ticket,
    boolean instanceCreated,
    NamespacedAnnotations annotations,
    Instant occurredAt) {

  public PostTransferAction {
    ticket = java.util.Objects.requireNonNull(ticket, "ticket");
    annotations = java.util.Objects.requireNonNull(annotations, "annotations");
    occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
