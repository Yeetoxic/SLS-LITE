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

  ApiVersion version();

  ApiStatus status();

  CompletionStage<Void> ready();

  Set<Capability> capabilities();

  List<BlueprintView> blueprints();

  Optional<BlueprintView> blueprint(String blueprintId);

  List<InstanceView> instances();

  Optional<InstanceView> instance(String instanceId);

  CompletionStage<InstanceView> start(StartRequest request);

  CompletionStage<InstanceOperationResult> stop(String instanceId);

  CompletionStage<DeleteResult> delete(String instanceId);

  CompletionStage<QueueResult> enqueue(QueueRequest request);

  Optional<QueueTicket> queued(UUID playerId);

  Optional<QueueTicket> dequeue(UUID playerId);

  Subscription subscribe(Consumer<? super SLSLiteEvent> listener);
}
