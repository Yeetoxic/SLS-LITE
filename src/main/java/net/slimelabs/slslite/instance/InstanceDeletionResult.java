package net.slimelabs.slslite.instance;

public record InstanceDeletionResult(String instanceId, boolean tombstoneCleaned) {}
