package net.slimelabs.slslite.api;

/**
 * Terminal result of an asynchronous instance operation.
 *
 * @param instanceId affected managed-instance identifier
 * @param status resulting public lifecycle status
 */
public record InstanceOperationResult(String instanceId, InstanceStatus status) {

  public InstanceOperationResult {
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }
    status = java.util.Objects.requireNonNull(status, "status");
  }
}
