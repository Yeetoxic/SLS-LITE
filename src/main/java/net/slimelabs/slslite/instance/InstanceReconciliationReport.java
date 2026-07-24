package net.slimelabs.slslite.instance;

public record InstanceReconciliationReport(
        int removedEphemeral,
        int preservedPersistent,
        int preservedRunning,
        int preservedUnknown,
        int failures
) {

    public int inspected() {
        return removedEphemeral
                + preservedPersistent
                + preservedRunning
                + preservedUnknown
                + failures;
    }
}
