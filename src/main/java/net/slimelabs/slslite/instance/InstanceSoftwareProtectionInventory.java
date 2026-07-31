package net.slimelabs.slslite.instance;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.install.InstallationKey;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataService;
import net.slimelabs.slslite.instance.model.InstanceMetadata;

final class InstanceSoftwareProtectionInventory {

  private final InstanceMetadataService metadata;
  private final BlueprintRepository blueprints;

  InstanceSoftwareProtectionInventory(
      InstanceMetadataService metadata, BlueprintRepository blueprints) {
    this.metadata = metadata;
    this.blueprints = blueprints;
  }

  Set<InstallationKey> collect(Collection<ManagedInstance> active)
      throws InstanceOperationException {
    Set<InstallationKey> protectedVersions = new HashSet<>();
    active.stream()
        .map(ManagedInstance::blueprint)
        .map(InstanceSoftwareProtectionInventory::key)
        .forEach(protectedVersions::add);
    for (String instanceId : metadata.persistentInstanceIds(null)) {
      InstanceMetadata persistent = metadata.readPersistent(instanceId);
      if (persistent.definitionIdentity() != null) {
        protectedVersions.add(
            new InstallationKey(
                persistent.definitionIdentity().softwareId(),
                persistent.definitionIdentity().softwareVersion()));
        continue;
      }
      blueprints
          .get(persistent.blueprintId())
          .map(InstanceSoftwareProtectionInventory::key)
          .ifPresent(protectedVersions::add);
    }
    return Set.copyOf(protectedVersions);
  }

  private static InstallationKey key(Blueprint blueprint) {
    return new InstallationKey(blueprint.software(), blueprint.version());
  }
}
