package net.slimelabs.slslite.api;

/** Final result of a matchmaking request after the connection attempt. */
public record QueueResult(QueueTicket ticket, boolean instanceCreated, boolean connected) {

  public QueueResult {
    ticket = java.util.Objects.requireNonNull(ticket, "ticket");
  }
}
