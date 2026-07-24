package net.slimelabs.slslite.instance;

import java.time.Instant;
import java.util.Objects;

public record InstanceMetadata(
        String instanceId,
        String blueprintId,
        boolean persistent,
        InstanceState state,
        Instant createdAt,
        Long processId,
        Instant processStartedAt
) {

    public InstanceMetadata {
        if (!InstanceIdGenerator.isValid(instanceId)) {
            throw new IllegalArgumentException("Invalid instance ID: " + instanceId);
        }
        if (blueprintId == null || blueprintId.isBlank()) {
            throw new IllegalArgumentException("blueprintId must not be blank");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        if (processId == null && processStartedAt != null) {
            throw new IllegalArgumentException(
                    "processStartedAt requires processId"
            );
        }
        if (processId != null && processId <= 0) {
            throw new IllegalArgumentException("processId must be positive");
        }
    }

    public InstanceMetadata withState(InstanceState nextState) {
        return new InstanceMetadata(
                instanceId,
                blueprintId,
                persistent,
                nextState,
                createdAt,
                processId,
                processStartedAt
        );
    }

    public InstanceMetadata withProcess(
            InstanceState nextState,
            long nextProcessId,
        Instant nextProcessStartedAt
    ) {
        return new InstanceMetadata(
                instanceId,
                blueprintId,
                persistent,
                nextState,
                createdAt,
                nextProcessId,
                nextProcessStartedAt
        );
    }

    public InstanceMetadata withoutProcess(InstanceState nextState) {
        return new InstanceMetadata(
                instanceId,
                blueprintId,
                persistent,
                nextState,
                createdAt,
                null,
                null
        );
    }
}
