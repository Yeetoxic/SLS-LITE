package net.slimelabs.slslite.config;

import net.slimelabs.slslite.blueprint.BlueprintException;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.software.SoftwareProfileRepository;

import java.io.IOException;

public final class DefinitionReloader {

    private DefinitionReloader() {
    }

    public static void reload(
            SLSConfig config,
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            boolean reloadBlueprints,
            boolean reloadSoftware
    ) throws IOException, BlueprintException, ConfigurationException {
        BlueprintRepository.Snapshot blueprintCandidate = reloadBlueprints
                ? blueprints.loadSnapshot()
                : blueprints.snapshot();
        SoftwareProfileRepository.Snapshot softwareCandidate = reloadSoftware
                ? softwareProfiles.loadSnapshot()
                : softwareProfiles.snapshot();

        ConfigurationValidator.validate(
                config,
                blueprintCandidate.getAll(),
                softwareCandidate.getAll()
        );

        // Install software first so newly referenced profiles exist before blueprints.
        if (reloadSoftware) {
            softwareProfiles.install(softwareCandidate);
        }
        if (reloadBlueprints) {
            blueprints.install(blueprintCandidate);
        }
    }
}
