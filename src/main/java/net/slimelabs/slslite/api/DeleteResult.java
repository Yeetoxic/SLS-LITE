package net.slimelabs.slslite.api;

/** Result of an ownership-aware instance deletion. */
public record DeleteResult(String instanceId, boolean reconciliationMarkerCleaned) {

  public DeleteResult {
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }
  }
}
