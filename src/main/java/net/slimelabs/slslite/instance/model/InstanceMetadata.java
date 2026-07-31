package net.slimelabs.slslite.instance.model;

import java.time.Instant;
import java.util.Objects;

public record InstanceMetadata(
    String instanceId,
    String blueprintId,
    InstanceDefinitionIdentity definitionIdentity,
    boolean persistent,
    InstanceState state,
    Instant createdAt,
    Long processId,
    Instant processStartedAt,
    InstanceLaunchOverrides launchOverrides) {

  public InstanceMetadata {
    if (!InstanceIdGenerator.isValid(instanceId)) {
      throw new IllegalArgumentException("Invalid instance ID: " + instanceId);
    }
    if (blueprintId == null || blueprintId.isBlank()) {
      throw new IllegalArgumentException("blueprintId must not be blank");
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(launchOverrides, "launchOverrides");
    if (processId == null && processStartedAt != null) {
      throw new IllegalArgumentException("processStartedAt requires processId");
    }
    if (processId != null && processId <= 0) {
      throw new IllegalArgumentException("processId must be positive");
    }
  }

  public InstanceMetadata(
      String instanceId,
      String blueprintId,
      InstanceDefinitionIdentity definitionIdentity,
      boolean persistent,
      InstanceState state,
      Instant createdAt,
      Long processId,
      Instant processStartedAt) {
    this(
        instanceId,
        blueprintId,
        definitionIdentity,
        persistent,
        state,
        createdAt,
        processId,
        processStartedAt,
        InstanceLaunchOverrides.NONE);
  }

  public InstanceMetadata(
      String instanceId,
      String blueprintId,
      boolean persistent,
      InstanceState state,
      Instant createdAt,
      Long processId,
      Instant processStartedAt) {
    this(
        instanceId,
        blueprintId,
        null,
        persistent,
        state,
        createdAt,
        processId,
        processStartedAt,
        InstanceLaunchOverrides.NONE);
  }

  public InstanceMetadata withDefinitionIdentity(InstanceDefinitionIdentity nextIdentity) {
    return new InstanceMetadata(
        instanceId,
        blueprintId,
        Objects.requireNonNull(nextIdentity, "nextIdentity"),
        persistent,
        state,
        createdAt,
        processId,
        processStartedAt,
        launchOverrides);
  }

  public InstanceMetadata withState(InstanceState nextState) {
    return new InstanceMetadata(
        instanceId,
        blueprintId,
        definitionIdentity,
        persistent,
        nextState,
        createdAt,
        processId,
        processStartedAt,
        launchOverrides);
  }

  public InstanceMetadata withProcess(
      InstanceState nextState, long nextProcessId, Instant nextProcessStartedAt) {
    return new InstanceMetadata(
        instanceId,
        blueprintId,
        definitionIdentity,
        persistent,
        nextState,
        createdAt,
        nextProcessId,
        nextProcessStartedAt,
        launchOverrides);
  }

  public InstanceMetadata withoutProcess(InstanceState nextState) {
    return new InstanceMetadata(
        instanceId,
        blueprintId,
        definitionIdentity,
        persistent,
        nextState,
        createdAt,
        null,
        null,
        launchOverrides);
  }
}
