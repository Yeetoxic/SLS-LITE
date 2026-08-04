package net.slimelabs.slslite.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.slimelabs.slslite.api.event.SLSLiteEvent;
import net.slimelabs.slslite.api.event.Subscription;

/**
 * Stable in-process API for trusted Velocity extensions.
 *
 * <p>Calls do not perform player permission checks. The calling plugin is trusted code and must
 * authorize its own users. SLS-LITE still applies admission, ownership, capacity, path, and
 * lifecycle safety rules.
 */
public interface SLSLiteApi {

  /** Returns the supported public API contract version. */
  ApiVersion version();

  /** Returns the provider's current availability. */
  ApiStatus status();

  /** Returns a stage completed when the provider becomes ready, or exceptionally if it fails. */
  CompletionStage<Void> ready();

  /** Returns an immutable set of optional features supported by this provider. */
  Set<Capability> capabilities();

  /**
   * Creates one exclusively namespaced ownership context.
   *
   * @param namespace plugin-style extension namespace
   * @return owned callback and action context
   */
  ExtensionContext extension(String namespace);

  /** Returns a new bounded, redacted operational snapshot. */
  DiagnosticsSnapshot diagnostics();

  /** Returns an immutable snapshot of all loaded blueprints. */
  List<BlueprintView> blueprints();

  /**
   * Finds a loaded blueprint by identifier.
   *
   * @param blueprintId blueprint identifier
   * @return immutable view when present
   */
  Optional<BlueprintView> blueprint(String blueprintId);

  /** Returns an immutable snapshot of all managed instances. */
  List<InstanceView> instances();

  /**
   * Finds a managed instance by identifier.
   *
   * @param instanceId managed-instance identifier
   * @return immutable view when present
   */
  Optional<InstanceView> instance(String instanceId);

  /**
   * Provisions and registers an instance through normal admission and lifecycle rules.
   *
   * @param request validated start request
   * @return stage completed with a READY instance view
   */
  CompletionStage<InstanceView> start(StartRequest request);

  /**
   * Gracefully stops a managed instance without deleting persistent data.
   *
   * @param instanceId managed-instance identifier
   * @return stage completed with the terminal operation result
   */
  CompletionStage<InstanceOperationResult> stop(String instanceId);

  /**
   * Deletes a stopped instance through ownership-aware cleanup.
   *
   * @param instanceId managed-instance identifier
   * @return stage completed with deletion metadata
   */
  CompletionStage<DeleteResult> delete(String instanceId);

  /**
   * Matches and transfers an online player through the normal queue.
   *
   * @param request validated queue request
   * @return stage completed after the transfer attempt reaches a terminal state
   */
  CompletionStage<QueueResult> enqueue(QueueRequest request);

  /**
   * Inspects one player's current queue ticket.
   *
   * @param playerId player UUID
   * @return immutable ticket when queued
   */
  Optional<QueueTicket> queued(UUID playerId);

  /**
   * Atomically removes one player's queued request when it is still cancellable.
   *
   * @param playerId player UUID
   * @return removed ticket when cancellation won
   */
  Optional<QueueTicket> dequeue(UUID playerId);

  /**
   * Registers a global non-blocking event listener.
   *
   * <p>Prefer {@link #extension(String)} when callback ownership should follow another plugin's
   * lifecycle.
   *
   * @param listener non-blocking event consumer
   * @return idempotent subscription handle
   */
  Subscription subscribe(Consumer<? super SLSLiteEvent> listener);
}
