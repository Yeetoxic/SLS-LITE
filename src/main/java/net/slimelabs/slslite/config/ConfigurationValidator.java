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
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            boolean velocityOnlineMode
    ) throws ConfigurationException {
        validate(config, blueprints.getAll(), softwareProfiles.getAll());
        validateForwarding(config, velocityOnlineMode);
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
        int limboMemory = config.limbo().enabled() ? config.limbo().memoryMiB() : 0;
        if (limboMemory > config.totalMemoryMiB()) {
            throw new ConfigurationException(
                    "SLS-Limbo requests " + limboMemory + " MiB, exceeding the "
                            + config.totalMemoryMiB() + " MiB host budget"
            );
        }
        if (config.lobby().mode() == LobbyMode.MANAGED) {
            Blueprint lobbyBlueprint = java.util.Optional.ofNullable(
                    blueprintsById.get(config.lobby().server())
            ).filter(blueprint -> blueprint.type().equals(
                    config.lobby().registry()
            )).orElseThrow(() -> new ConfigurationException(
                    "Managed lobby blueprint not found: "
                            + config.lobby().registry() + "/" + config.lobby().server()
            ));
            int requiredMemory = limboMemory + lobbyBlueprint.memoryLimitMiB();
            if (requiredMemory > config.totalMemoryMiB()) {
                throw new ConfigurationException(
                        "Managed lobby and SLS-Limbo require " + requiredMemory
                                + " MiB (" + lobbyBlueprint.memoryLimitMiB()
                                + " + " + limboMemory + "), exceeding the "
                                + config.totalMemoryMiB() + " MiB host budget"
                );
            }
            int requiredProcesses = config.limbo().enabled() ? 2 : 1;
            if (config.maxManagedProcesses() < requiredProcesses) {
                throw new ConfigurationException(
                        "Managed lobby and SLS-Limbo require " + requiredProcesses
                                + " managed process slots, but resources.max_managed_processes is "
                                + config.maxManagedProcesses()
                );
            }
        }
    }

    private static void validateForwarding(SLSConfig config, boolean velocityOnlineMode)
            throws ConfigurationException {
        if (config.forwarding().mode() == ForwardingMode.MODERN
                && config.forwarding().onlineMode() != velocityOnlineMode) {
            throw new ConfigurationException(
                    "forwarding.online_mode is " + config.forwarding().onlineMode()
                            + " but Velocity online-mode is " + velocityOnlineMode
                            + "; these values must match when forwarding.mode is modern"
            );
        }
    }
}
