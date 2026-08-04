package net.slimelabs.slslite.api;

import java.time.Instant;

/**
 * Immutable input delivered after an instance is registered with Velocity.
 *
 * @param instance READY instance snapshot
 * @param annotations annotation data owned by the receiving extension namespace
 * @param occurredAt time of the corresponding READY transition
 */
public record InstanceReadyAction(
    InstanceView instance, NamespacedAnnotations annotations, Instant occurredAt) {

  public InstanceReadyAction {
    instance = java.util.Objects.requireNonNull(instance, "instance");
    annotations = java.util.Objects.requireNonNull(annotations, "annotations");
    occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
    if (instance.status() != InstanceStatus.READY) {
      throw new IllegalArgumentException("Instance-ready action requires READY status");
    }
  }
}
