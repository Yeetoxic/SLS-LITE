package net.slimelabs.slslite.instance.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.process.SupervisedProcess;
import org.slf4j.Logger;

/**
 * Owns persisted instance metadata and the compatibility rules applied when a
 * saved instance is resumed.
 */
public final class InstanceMetadataService {

  private final Path instancesRoot;
  private final InstanceMetadataStore store;
  private final Logger logger;

  public InstanceMetadataService(Path instancesRoot, Logger logger) {
    this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    this.store = new InstanceMetadataStore(this.instancesRoot);
    this.logger = logger;
  }

  public Collection<String> persistentInstanceIds(String blueprintId) {
    if (!Files.isDirectory(instancesRoot)) {
      return List.of();
    }
    try (var directories = Files.list(instancesRoot)) {
      return directories
          .filter(Files::isDirectory)
          .map(
              directory -> {
                try {
                  return store.read(directory).orElse(null);
                } catch (IOException | RuntimeException exception) {
                  return null;
                }
              })
          .filter(java.util.Objects::nonNull)
          .filter(InstanceMetadata::persistent)
          .filter(metadata -> blueprintId == null || metadata.blueprintId().equals(blueprintId))
          .map(InstanceMetadata::instanceId)
          .distinct()
          .sorted()
          .toList();
    } catch (IOException exception) {
      logger.warn("Unable to discover persistent instances: {}", exception.getMessage());
      return List.of();
    }
  }

  public InstanceMetadata readPersistent(String instanceId) throws InstanceOperationException {
    Path directory = instancesRoot.resolve(instanceId);
    try {
      InstanceMetadata metadata =
          store
              .read(directory)
              .orElseThrow(
                  () ->
                      new InstanceOperationException(
                          "No persistent SLS-LITE instance exists: " + instanceId));
      if (!metadata.instanceId().equals(instanceId)) {
        throw new InstanceOperationException(
            "Persistent metadata does not match instance ID: " + instanceId);
      }
      return metadata;
    } catch (IOException exception) {
      throw new InstanceOperationException(
          "Unable to read persistent instance metadata: " + instanceId, exception);
    }
  }

  public InstanceMetadata snapshot(ManagedInstance instance) {
    return new InstanceMetadata(
        instance.id(),
        instance.blueprint().id(),
        instance.definitionIdentity(),
        instance.blueprint().save(),
        instance.state(),
        instance.createdAt(),
        instance.processId().isPresent() ? instance.processId().getAsLong() : null,
        instance.processStartedAt().orElse(null),
        instance.launchOverrides());
  }

  public void requireRestartable(InstanceMetadata metadata, boolean active)
      throws InstanceOperationException {
    if (!metadata.persistent()) {
      throw new InstanceOperationException(
          "Instance is ephemeral and cannot be restarted: " + metadata.instanceId());
    }
    if (!active && isRecordedProcessRunning(metadata)) {
      throw new InstanceOperationException(
          "Persistent instance process is still running: " + metadata.instanceId());
    }
  }

  public InstanceMetadata requireCompatibleDefinition(
      InstanceMetadata metadata, InstanceDefinitionIdentity current, boolean currentlyPersistent)
      throws InstanceOperationException {
    InstanceDefinitionIdentity recorded = metadata.definitionIdentity();
    if (recorded == null) {
      if (metadata.persistent() != currentlyPersistent) {
        throw new InstanceOperationException(
            "Persistent instance "
                + metadata.instanceId()
                + " uses legacy metadata, but its blueprint now has "
                + "save: false; restore save: true to migrate it "
                + "without deleting its contents");
      }
      InstanceMetadata migrated = metadata.withDefinitionIdentity(current);
      try {
        store.write(instancesRoot.resolve(metadata.instanceId()), migrated);
      } catch (IOException exception) {
        throw new InstanceOperationException(
            "Unable to migrate legacy metadata for persistent instance " + metadata.instanceId(),
            exception);
      }
      logger.warn(
          "Migrated legacy metadata for persistent instance {} using "
              + "the current {}/{} definition; reset it explicitly if "
              + "its template contents are no longer compatible",
          metadata.instanceId(),
          current.softwareId(),
          current.softwareVersion());
      return migrated;
    }
    if (!recorded.equals(current)) {
      throw new InstanceOperationException(
          "Persistent instance "
              + metadata.instanceId()
              + " was created from a different software, configuration, "
              + "or volume definition; reset it before restarting");
    }
    return metadata;
  }

  public void write(ManagedInstance instance, InstanceState state, SupervisedProcess process)
      throws InstancePreparationException {
    Long processId = process == null ? null : process.processId();
    java.time.Instant processStartedAt =
        process == null ? null : process.processStartedAt().orElse(null);
    InstanceMetadata metadata =
        new InstanceMetadata(
            instance.id(),
            instance.blueprint().id(),
            instance.definitionIdentity(),
            instance.blueprint().save(),
            state,
            instance.createdAt(),
            processId,
            processStartedAt,
            instance.launchOverrides());
    try {
      store.write(instance.directory(), metadata);
    } catch (IOException exception) {
      throw new InstancePreparationException(
          "Unable to write instance metadata for " + instance.id(), exception);
    }
  }

  public void write(Path directory, InstanceMetadata metadata) throws IOException {
    store.write(directory, metadata);
  }

  public void writeBestEffort(
      ManagedInstance instance, InstanceState state, SupervisedProcess process) {
    if (!Files.isDirectory(instance.directory())) {
      return;
    }
    try {
      write(instance, state, process);
    } catch (InstancePreparationException exception) {
      logger.warn(
          "Unable to update instance metadata for {}: {}", instance.id(), exception.getMessage());
    }
  }

  private static boolean isRecordedProcessRunning(InstanceMetadata metadata) {
    if (metadata.processId() == null) {
      return false;
    }
    return ProcessHandle.of(metadata.processId())
        .filter(ProcessHandle::isAlive)
        .filter(
            handle ->
                metadata.processStartedAt() == null
                    || handle.info().startInstant().isEmpty()
                    || handle
                        .info()
                        .startInstant()
                        .orElseThrow()
                        .equals(metadata.processStartedAt()))
        .isPresent();
  }
}
