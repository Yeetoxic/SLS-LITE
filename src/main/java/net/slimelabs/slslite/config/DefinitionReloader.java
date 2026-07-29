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

        java.util.Map<String, net.slimelabs.slslite.blueprint.Blueprint>
                resolvedBlueprints = DefinitionCatalog.resolveBlueprints(
                        blueprintCandidate.values(),
                        softwareCandidate.values()
                );

        ConfigurationValidator.validate(
                config,
                resolvedBlueprints.values(),
                softwareCandidate.getAll()
        );

        if (blueprints.catalog() != softwareProfiles.catalog()) {
            throw new ConfigurationException(
                    "Blueprint and software repositories do not share a definition catalog"
            );
        }
        blueprints.catalog().install(
                resolvedBlueprints,
                softwareCandidate.values()
        );
    }
}
