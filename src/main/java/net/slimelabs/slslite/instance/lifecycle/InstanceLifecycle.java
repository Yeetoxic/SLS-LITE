package net.slimelabs.slslite.instance.lifecycle;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import net.slimelabs.slslite.instance.model.InstanceState;

public final class InstanceLifecycle {

  private final String instanceId;
  private final Consumer<Transition> observer;
  private volatile InstanceState state = InstanceState.CREATED;

  public InstanceLifecycle(String instanceId) {
    this(instanceId, ignored -> {});
  }

  public InstanceLifecycle(String instanceId, Consumer<Transition> observer) {
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }
    this.instanceId = instanceId;
    this.observer = Objects.requireNonNull(observer, "observer");
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
          "Invalid instance transition for " + instanceId + ": " + state + " -> " + next);
    }
    InstanceState previous = state;
    state = next;
    try {
      observer.accept(new Transition(instanceId, previous, next, Instant.now()));
    } catch (RuntimeException ignored) {
      // Observability must never break an accepted lifecycle transition.
    }
  }

  public static boolean canTransition(InstanceState current, InstanceState next) {
    if (current == next) {
      return false;
    }

    return switch (current) {
      case CREATED -> next == InstanceState.PREPARING || next == InstanceState.FAILED;
      case PREPARING ->
          next == InstanceState.STARTING
              || next == InstanceState.STOPPING
              || next == InstanceState.FAILED;
      case STARTING ->
          next == InstanceState.READY
              || next == InstanceState.STOPPING
              || next == InstanceState.FAILED;
      case READY -> next == InstanceState.STOPPING || next == InstanceState.FAILED;
      case STOPPING -> next == InstanceState.STOPPED || next == InstanceState.FAILED;
      case FAILED -> next == InstanceState.STOPPING || next == InstanceState.STOPPED;
      case STOPPED -> false;
    };
  }

  public record Transition(
      String instanceId, InstanceState previous, InstanceState current, Instant occurredAt) {

    public Transition {
      Objects.requireNonNull(instanceId, "instanceId");
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(current, "current");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }
}
