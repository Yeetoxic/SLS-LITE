package net.slimelabs.slslite.config;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.software.SoftwareProfile;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class DefinitionCatalog {

    private final AtomicReference<Snapshot> active =
            new AtomicReference<>(new Snapshot(Map.of(), Map.of()));

    public Snapshot snapshot() {
        return active.get();
    }

    public void installBlueprints(Map<String, Blueprint> blueprints) {
        active.updateAndGet(current -> new Snapshot(blueprints, current.softwareProfiles()));
    }

    public void installSoftwareProfiles(Map<String, SoftwareProfile> softwareProfiles) {
        active.updateAndGet(current -> new Snapshot(current.blueprints(), softwareProfiles));
    }

    public void install(
            Map<String, Blueprint> blueprints,
            Map<String, SoftwareProfile> softwareProfiles
    ) {
        active.set(new Snapshot(blueprints, softwareProfiles));
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
