package net.slimelabs.slslite.config;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintLifecyclePolicy;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.software.SoftwareProfileRepository;

public final class ConfigurationValidator {

    private ConfigurationValidator() {
    }

    public static void validate(
            SLSConfig config,
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles
    ) throws ConfigurationException {
        for (Blueprint blueprint : blueprints.getAll()) {
            try {
                BlueprintLifecyclePolicy.from(
                        blueprint,
                        config.idleShutdownSeconds()
                );
            } catch (IllegalArgumentException exception) {
                throw new ConfigurationException(
                        "Blueprint '" + blueprint.id() + "': " + exception.getMessage()
                );
            }
            if (softwareProfiles.get(blueprint.software()).isEmpty()) {
                throw new ConfigurationException(
                        "Blueprint '" + blueprint.id() + "' references missing software profile '"
                                + blueprint.software() + "'"
                );
            }
            if (blueprint.memoryLimitMiB() > config.totalMemoryMiB()) {
                throw new ConfigurationException(
                        "Blueprint '" + blueprint.id() + "' requests "
                                + blueprint.memoryLimitMiB() + " MiB, exceeding the "
                                + config.totalMemoryMiB() + " MiB host budget"
                );
            }
        }
        if (config.lobby().mode() == LobbyMode.MANAGED
                && blueprints.get(
                config.lobby().registry(),
                config.lobby().server()
        ).isEmpty()) {
            throw new ConfigurationException(
                    "Managed lobby blueprint not found: "
                            + config.lobby().registry() + "/" + config.lobby().server()
            );
        }
    }
}
