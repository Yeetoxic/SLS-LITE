package net.slimelabs.slslite.api;

/** Request to provision one managed instance and complete when it is ready. */
public record StartRequest(String blueprintId, InstanceOverrides overrides) {

  public StartRequest {
    if (blueprintId == null || blueprintId.isBlank()) {
      throw new IllegalArgumentException("blueprintId must not be blank");
    }
    overrides = overrides == null ? InstanceOverrides.NONE : overrides;
  }

  public StartRequest(String blueprintId) {
    this(blueprintId, InstanceOverrides.NONE);
  }
}
