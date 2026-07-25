package net.slimelabs.slslite.host;

import java.util.List;

public record HostCapabilityReport(List<HostCapability> capabilities) {

    public HostCapabilityReport {
        capabilities = List.copyOf(capabilities);
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
