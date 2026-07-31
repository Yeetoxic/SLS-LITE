package net.slimelabs.slslite.instance.reconcile;

public record InstanceReconciliationReport(
    int recoveredStorageTransactions,
    int removedEphemeral,
    int preservedPersistent,
    int preservedRunning,
    int preservedUnknown,
    int failures) {

  public int inspected() {
    return removedEphemeral + preservedPersistent + preservedRunning + preservedUnknown + failures;
  }
}
