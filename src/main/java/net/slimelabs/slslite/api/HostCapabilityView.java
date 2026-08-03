package net.slimelabs.slslite.api;

/** Redacted result of one startup host capability probe. */
public record HostCapabilityView(String name, HostCapabilityState state, String detail) {

  public HostCapabilityView {
    name = MaintenanceView.boundedText(name, 128, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    state = java.util.Objects.requireNonNull(state, "state");
    detail = MaintenanceView.boundedText(detail, 512, "detail");
  }
}
