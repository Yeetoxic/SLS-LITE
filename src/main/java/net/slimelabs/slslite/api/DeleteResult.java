package net.slimelabs.slslite.api;

/**
 * Result of an ownership-aware instance deletion.
 *
 * @param instanceId deleted managed-instance identifier
 * @param reconciliationMarkerCleaned whether owned crash-recovery metadata was removed
 */
public record DeleteResult(String instanceId, boolean reconciliationMarkerCleaned) {

  public DeleteResult {
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }
  }
}
