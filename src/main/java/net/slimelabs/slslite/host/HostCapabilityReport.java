package net.slimelabs.slslite.host;

import java.util.List;
import java.util.Optional;
import net.slimelabs.slslite.config.StorageStrategy;

public record HostCapabilityReport(
    List<HostCapability> capabilities, Optional<StorageStrategy> selectedStorageStrategy) {

  public HostCapabilityReport(List<HostCapability> capabilities) {
    this(capabilities, Optional.empty());
  }

  public HostCapabilityReport {
    capabilities = List.copyOf(capabilities);
    selectedStorageStrategy =
        selectedStorageStrategy == null ? Optional.empty() : selectedStorageStrategy;
  }

  public boolean hasFailures() {
    return capabilities.stream()
        .anyMatch(capability -> capability.status() == HostCapabilityStatus.FAILURE);
  }

  public String failureSummary() {
    return capabilities.stream()
        .filter(capability -> capability.status() == HostCapabilityStatus.FAILURE)
        .map(capability -> capability.name() + ": " + capability.detail())
        .reduce((left, right) -> left + "; " + right)
        .orElse("none");
  }
}
