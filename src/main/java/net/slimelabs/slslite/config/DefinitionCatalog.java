package net.slimelabs.slslite.config;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.software.SoftwareProfile;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class DefinitionCatalog {

    private static final int DEFAULT_BLUEPRINT_MEMORY_MIB = 1024;

    private final AtomicReference<Snapshot> active =
            new AtomicReference<>(new Snapshot(Map.of(), Map.of()));

    public Snapshot snapshot() {
        return active.get();
    }

    public void installBlueprints(Map<String, Blueprint> blueprints) {
        active.updateAndGet(current -> new Snapshot(
                resolveBlueprints(blueprints, current.softwareProfiles()),
                current.softwareProfiles()
        ));
    }

    public void installSoftwareProfiles(Map<String, SoftwareProfile> softwareProfiles) {
        active.updateAndGet(current -> new Snapshot(
                resolveBlueprints(current.blueprints(), softwareProfiles),
                softwareProfiles
        ));
    }

    public void install(
            Map<String, Blueprint> blueprints,
            Map<String, SoftwareProfile> softwareProfiles
    ) {
        active.set(new Snapshot(
                resolveBlueprints(blueprints, softwareProfiles),
                softwareProfiles
        ));
    }

    public static Map<String, Blueprint> resolveBlueprints(
            Map<String, Blueprint> blueprints,
            Map<String, SoftwareProfile> softwareProfiles
    ) {
        java.util.LinkedHashMap<String, Blueprint> resolved =
                new java.util.LinkedHashMap<>();
        blueprints.forEach((id, blueprint) -> {
            SoftwareProfile profile = softwareProfiles.get(blueprint.software());
            int memory = blueprint.memoryLimitMiB();
            String image = blueprint.image();
            if (profile != null) {
                if (blueprint.inheritsSoftwareMemory()) {
                    memory = profile.defaultMemoryLimitMiB() > 0
                            ? profile.defaultMemoryLimitMiB()
                            : DEFAULT_BLUEPRINT_MEMORY_MIB;
                }
                if (blueprint.inheritsSoftwareImage()) {
                    image = profile.imageForVersion(blueprint.version()).orElse(null);
                }
            }
            resolved.put(id, blueprint.withSoftwareDefaults(memory, image));
        });
        return Map.copyOf(resolved);
    }

    public record Snapshot(
            Map<String, Blueprint> blueprints,
            Map<String, SoftwareProfile> softwareProfiles
    ) {
        public Snapshot {
            blueprints = Map.copyOf(blueprints);
            softwareProfiles = Map.copyOf(softwareProfiles);
        }
    }
}
