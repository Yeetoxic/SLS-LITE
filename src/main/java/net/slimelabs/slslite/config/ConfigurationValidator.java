package net.slimelabs.slslite.config;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintLifecyclePolicy;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.software.SoftwareProfile;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ConfigurationValidator {

    private ConfigurationValidator() {
    }

    public static void validate(
            SLSConfig config,
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles
    ) throws ConfigurationException {
        validate(config, blueprints.getAll(), softwareProfiles.getAll());
    }

    public static void validate(
            SLSConfig config,
            Collection<Blueprint> blueprints,
            Collection<SoftwareProfile> softwareProfiles
    ) throws ConfigurationException {
        Map<String, SoftwareProfile> profilesById = softwareProfiles.stream()
                .collect(Collectors.toUnmodifiableMap(
                        SoftwareProfile::id,
                        Function.identity()
                ));
        Map<String, Blueprint> blueprintsById = blueprints.stream()
                .collect(Collectors.toUnmodifiableMap(
                        Blueprint::id,
                        Function.identity()
                ));
        for (Blueprint blueprint : blueprints) {
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
            if (!profilesById.containsKey(blueprint.software())) {
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
                && java.util.Optional.ofNullable(
                        blueprintsById.get(config.lobby().server())
                ).filter(blueprint -> blueprint.type().equals(
                        config.lobby().registry()
                )).isEmpty()) {
            throw new ConfigurationException(
                    "Managed lobby blueprint not found: "
                            + config.lobby().registry() + "/" + config.lobby().server()
            );
        }
    }
}
