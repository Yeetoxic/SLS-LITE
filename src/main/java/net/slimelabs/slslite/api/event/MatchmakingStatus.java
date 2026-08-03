package net.slimelabs.slslite.api.event;

/** Accepted state changes for one SLS-LITE matchmaking request. */
public enum MatchmakingStatus {
  QUEUED,
  TRANSFER_STARTED,
  TRANSFER_SUCCEEDED,
  TRANSFER_REJECTED,
  TRANSFER_FAILED,
  CANCELLED,
  DISCONNECTED,
  TIMED_OUT,
  INSTANCE_FAILED,
  BACKEND_UNAVAILABLE,
  SHUTDOWN
}
