package net.slimelabs.slslite.api;

/**
 * Final result of a matchmaking request after the connection attempt.
 *
 * @param ticket terminal matchmaking ticket
 * @param instanceCreated whether the request provisioned the target instance
 * @param connected whether Velocity reported a successful player transfer
 */
public record QueueResult(QueueTicket ticket, boolean instanceCreated, boolean connected) {

  public QueueResult {
    ticket = java.util.Objects.requireNonNull(ticket, "ticket");
  }
}
