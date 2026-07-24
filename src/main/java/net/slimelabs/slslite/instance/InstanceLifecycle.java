package net.slimelabs.slslite.instance;

import java.util.Objects;

public final class InstanceLifecycle {

    private final String instanceId;
    private volatile InstanceState state = InstanceState.CREATED;

    public InstanceLifecycle(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        this.instanceId = instanceId;
    }

    public String instanceId() {
        return instanceId;
    }

    public InstanceState state() {
        return state;
    }

    public synchronized void transitionTo(InstanceState next) {
        Objects.requireNonNull(next, "next");
        if (!canTransition(state, next)) {
            throw new IllegalStateException(
                    "Invalid instance transition for " + instanceId + ": " + state + " -> " + next
            );
        }
        state = next;
    }

    public static boolean canTransition(InstanceState current, InstanceState next) {
        if (current == next) {
            return false;
        }

        return switch (current) {
            case CREATED -> next == InstanceState.PREPARING || next == InstanceState.FAILED;
            case PREPARING -> next == InstanceState.STARTING || next == InstanceState.FAILED;
            case STARTING -> next == InstanceState.READY
                    || next == InstanceState.STOPPING
                    || next == InstanceState.FAILED;
            case READY -> next == InstanceState.STOPPING || next == InstanceState.FAILED;
            case STOPPING -> next == InstanceState.STOPPED || next == InstanceState.FAILED;
            case FAILED -> next == InstanceState.STOPPING || next == InstanceState.STOPPED;
            case STOPPED -> false;
        };
    }
}
