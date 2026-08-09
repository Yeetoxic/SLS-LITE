package net.slimelabs.slslite.instance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataService;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceIdGenerator;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareProfile;
import org.slf4j.Logger;

final class PersistentInstanceOperations {

  private final InstanceManager manager;
  private final InstanceMetadataService metadata;
  private final InstanceDirectoryPreparer directories;
  private final SoftwareBaseDirectoryResolver softwareDirectories;
  private final Logger logger;
  private final ThreadPoolExecutor executor;
  private final Set<String> pendingCycles = new HashSet<>();
  private final Set<String> pendingDeletes = new HashSet<>();

  PersistentInstanceOperations(
      InstanceManager manager,
      InstanceMetadataService metadata,
      InstanceDirectoryPreparer directories,
      SoftwareBaseDirectoryResolver softwareDirectories,
      ThreadPoolExecutor executor,
      Logger logger) {
    this.manager = manager;
    this.metadata = metadata;
    this.directories = directories;
    this.softwareDirectories = softwareDirectories;
    this.executor = executor;
    this.logger = logger;
  }

  CompletableFuture<ManagedInstance> restart(String instanceId) throws InstanceOperationException {
    return cycle(instanceId, false);
  }

  CompletableFuture<ManagedInstance> reset(String instanceId) throws InstanceOperationException {
    return cycle(instanceId, true);
  }

  CompletableFuture<InstanceDeletionResult> delete(String instanceId)
      throws InstanceOperationException {
    ManagedInstance active;
    synchronized (manager) {
      validateRequest(instanceId);
      if (pendingCycles.contains(instanceId) || !pendingDeletes.add(instanceId)) {
        throw conflict(instanceId);
      }
      active = manager.findActive(instanceId);
      try {
        if (active == null) {
          metadata.readPersistent(instanceId);
        } else {
          metadata.snapshot(active);
        }
      } catch (InstanceOperationException exception) {
        pendingDeletes.remove(instanceId);
        throw exception;
      }
    }

    CompletableFuture<Integer> stopped;
    try {
      stopped = active == null ? CompletableFuture.completedFuture(0) : manager.stop(instanceId);
    } catch (InstanceOperationException exception) {
      synchronized (manager) {
        pendingDeletes.remove(instanceId);
      }
      throw exception;
    }
    return stopped
        .thenCompose(ignored -> submitDelete(instanceId))
        .whenComplete(
            (ignored, failure) -> {
              synchronized (manager) {
                pendingDeletes.remove(instanceId);
              }
            });
  }

  boolean hasPendingDelete(String instanceId) {
    return pendingDeletes.contains(instanceId);
  }

  boolean hasPendingOperation(String instanceId) {
    return pendingDeletes.contains(instanceId) || pendingCycles.contains(instanceId);
  }

  private CompletableFuture<ManagedInstance> cycle(String instanceId, boolean reset)
      throws InstanceOperationException {
    ManagedInstance active;
    synchronized (manager) {
      validateRequest(instanceId);
      if (pendingDeletes.contains(instanceId) || !pendingCycles.add(instanceId)) {
        throw conflict(instanceId);
      }
      active = manager.findActive(instanceId);
      try {
        InstanceMetadata snapshot =
            active == null ? metadata.readPersistent(instanceId) : metadata.snapshot(active);
        metadata.requireRestartable(snapshot, active != null);
        if (!reset) {
          manager.requireCompatibleDefinition(snapshot);
        }
      } catch (InstanceOperationException exception) {
        pendingCycles.remove(instanceId);
        throw exception;
      }
    }

    CompletableFuture<Integer> stopped;
    try {
      stopped = active == null ? CompletableFuture.completedFuture(0) : manager.stop(instanceId);
    } catch (InstanceOperationException exception) {
      synchronized (manager) {
        pendingCycles.remove(instanceId);
      }
      throw exception;
    }
    return stopped
        .thenApplyAsync(
            ignored -> {
              try {
                if (reset) {
                  resetPersistent(instanceId);
                }
                return manager.startPersistent(instanceId);
              } catch (InstanceOperationException exception) {
                throw new CompletionException(exception);
              }
            },
            executor)
        .whenComplete(
            (ignored, failure) -> {
              synchronized (manager) {
                pendingCycles.remove(instanceId);
              }
            });
  }

  private CompletableFuture<InstanceDeletionResult> submitDelete(String instanceId) {
    CompletableFuture<InstanceDeletionResult> deletion = new CompletableFuture<>();
    try {
      executor.execute(
          () -> {
            try {
              Path directory = directories.root().resolve(instanceId);
              if (!Files.exists(directory)) {
                logger.info("Instance delete completed: {} (storage already removed)", instanceId);
                deletion.complete(new InstanceDeletionResult(instanceId, true));
                return;
              }
              metadata.readPersistent(instanceId);
              boolean cleaned = directories.deletePersistent(instanceId);
              logger.info(
                  "Instance delete committed: {} (tombstone cleanup {})",
                  instanceId,
                  cleaned ? "complete" : "deferred");
              deletion.complete(new InstanceDeletionResult(instanceId, cleaned));
            } catch (InstanceOperationException | InstancePreparationException exception) {
              deletion.completeExceptionally(exception);
            }
          });
    } catch (RuntimeException exception) {
      deletion.completeExceptionally(
          new InstanceOperationException("Instance deletion queue is full", exception));
    }
    return deletion;
  }

  private void resetPersistent(String instanceId) throws InstanceOperationException {
    InstanceMetadata snapshot = metadata.readPersistent(instanceId);
    metadata.requireRestartable(snapshot, false);
    InstanceManager.ResolvedDefinition definition =
        manager.resolveDefinition(
            snapshot.blueprintId(),
            "Persistent instance "
                + instanceId
                + " references missing blueprint "
                + snapshot.blueprintId());
    Blueprint blueprint = snapshot.launchOverrides().applyTo(definition.blueprint());
    SoftwareProfile profile = definition.softwareProfile();
    try {
      Path baseDirectory =
          softwareDirectories.resolve(
              profile, blueprint.version(), blueprint.softwarePath(), () -> false);
      InstanceMetadata stopped =
          snapshot
              .withDefinitionIdentity(InstanceDefinitionIdentity.from(blueprint, profile))
              .withoutProcess(InstanceState.STOPPED);
      directories.replace(
          instanceId,
          baseDirectory,
          blueprint.volumes(),
          blueprint.copies(),
          blueprint.persistentFiles(),
          directory -> metadata.write(directory, stopped));
    } catch (ProcessSpecificationException | InstancePreparationException exception) {
      throw new InstanceOperationException(exception.getMessage(), exception);
    }
  }

  private void validateRequest(String instanceId) throws InstanceOperationException {
    if (manager.isClosed()) {
      throw new InstanceOperationException("Instance manager is shutting down");
    }
    if (!InstanceIdGenerator.isValid(instanceId)) {
      throw new InstanceOperationException("Invalid instance ID: " + instanceId);
    }
  }

  private static InstanceOperationException conflict(String instanceId) {
    return new InstanceOperationException(
        "Instance restart, reset, or delete is already in progress: " + instanceId);
  }
}
