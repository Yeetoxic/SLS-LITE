package net.slimelabs.slslite.config;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintCrashRecoveryPolicy;
import net.slimelabs.slslite.blueprint.BlueprintLifecyclePolicy;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareProfileRepository;

public final class ConfigurationValidator {

  private ConfigurationValidator() {}

  public static void validate(
      SLSConfig config, BlueprintRepository blueprints, SoftwareProfileRepository softwareProfiles)
      throws ConfigurationException {
    validate(config, blueprints.getAll(), softwareProfiles.getAll());
  }

  public static void validate(
      SLSConfig config,
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      boolean velocityOnlineMode)
      throws ConfigurationException {
    validate(config, blueprints.getAll(), softwareProfiles.getAll());
    validateForwarding(config, velocityOnlineMode);
  }

  public static void validateFaultIsolated(
      SLSConfig config,
      Collection<Blueprint> blueprints,
      Collection<SoftwareProfile> softwareProfiles,
      boolean velocityOnlineMode)
      throws ConfigurationException {
    Map<String, SoftwareProfile> profilesById =
        softwareProfiles.stream()
            .collect(Collectors.toUnmodifiableMap(SoftwareProfile::id, Function.identity()));
    Map<String, Blueprint> blueprintsById =
        blueprints.stream()
            .collect(Collectors.toUnmodifiableMap(Blueprint::id, Function.identity()));
    for (Blueprint blueprint : blueprints) {
      validateBlueprint(config, blueprint, profilesById);
    }
    validateHost(config, blueprintsById, false);
    validateForwarding(config, velocityOnlineMode);
  }

  public static void validate(
      SLSConfig config,
      Collection<Blueprint> blueprints,
      Collection<SoftwareProfile> softwareProfiles)
      throws ConfigurationException {
    Map<String, SoftwareProfile> profilesById =
        softwareProfiles.stream()
            .collect(Collectors.toUnmodifiableMap(SoftwareProfile::id, Function.identity()));
    Map<String, Blueprint> blueprintsById =
        blueprints.stream()
            .collect(Collectors.toUnmodifiableMap(Blueprint::id, Function.identity()));
    for (Blueprint blueprint : blueprints) {
      validateBlueprint(config, blueprint, profilesById);
    }
    validateHost(config, blueprintsById, true);
  }

  static void validateBlueprint(
      SLSConfig config, Blueprint blueprint, Map<String, SoftwareProfile> profilesById)
      throws ConfigurationException {
    BlueprintCrashRecoveryPolicy crashRecovery;
    try {
      BlueprintLifecyclePolicy.from(blueprint, config.idleShutdownSeconds());
      crashRecovery = BlueprintCrashRecoveryPolicy.from(blueprint);
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException(
          "Blueprint '" + blueprint.id() + "': " + exception.getMessage());
    }
    if (crashRecovery.enabled() && !blueprint.save()) {
      throw new ConfigurationException(
          "Blueprint '"
              + blueprint.id()
              + "' enables restart-on-crash but is not persistent (save: true)");
    }
    SoftwareProfile profile = profilesById.get(blueprint.software());
    if (profile == null) {
      throw new ConfigurationException(
          "Blueprint '"
              + blueprint.id()
              + "' references missing software profile '"
              + blueprint.software()
              + "'");
    }
    if (config.forwarding().mode() == ForwardingMode.MODERN
        && profile.configurator() == SoftwareConfigurator.VANILLA) {
      throw new ConfigurationException(
          "Blueprint '"
              + blueprint.id()
              + "' uses vanilla software, which does not support "
              + "Velocity modern forwarding");
    }
    if (blueprint.memoryLimitMiB() > config.totalMemoryMiB()) {
      throw new ConfigurationException(
          "Blueprint '"
              + blueprint.id()
              + "' requests "
              + blueprint.memoryLimitMiB()
              + " MiB, exceeding the "
              + config.totalMemoryMiB()
              + " MiB host budget");
    }
  }

  static void validateHost(
      SLSConfig config, Map<String, Blueprint> blueprintsById, boolean requireManagedLobby)
      throws ConfigurationException {
    int limboMemory = config.limbo().enabled() ? config.limbo().memoryMiB() : 0;
    if (limboMemory > config.totalMemoryMiB()) {
      throw new ConfigurationException(
          "SLS-Limbo requests "
              + limboMemory
              + " MiB, exceeding the "
              + config.totalMemoryMiB()
              + " MiB host budget");
    }
    if (config.lobby().mode() == LobbyMode.MANAGED) {
      if (!config.lobby().autoStart() && !config.limbo().enabled()) {
        throw new ConfigurationException(
            "lobby.auto_start=false requires lobby.limbo.enabled=true so players have a safe "
                + "routing destination");
      }
      Blueprint lobbyBlueprint = blueprintsById.get(config.lobby().server());
      if (lobbyBlueprint == null || !lobbyBlueprint.type().equals(config.lobby().registry())) {
        if (requireManagedLobby) {
          throw new ConfigurationException(
              "Managed lobby blueprint not found: "
                  + config.lobby().registry()
                  + "/"
                  + config.lobby().server());
        }
        return;
      }
      if (BlueprintCrashRecoveryPolicy.from(lobbyBlueprint).enabled()) {
        throw new ConfigurationException(
            "Managed lobby blueprint '"
                + lobbyBlueprint.id()
                + "' must use lobby.recovery instead of restart-on-crash");
      }
      int lobbyMemory = config.lobby().autoStart() ? lobbyBlueprint.memoryLimitMiB() : 0;
      long requiredMemory = (long) limboMemory + lobbyMemory;
      if (requiredMemory > config.totalMemoryMiB()) {
        throw new ConfigurationException(
            "Managed lobby and SLS-Limbo require "
                + requiredMemory
                + " MiB ("
                + lobbyMemory
                + " + "
                + limboMemory
                + "), exceeding the "
                + config.totalMemoryMiB()
                + " MiB host budget");
      }
      int requiredProcesses =
          (config.lobby().autoStart() ? 1 : 0) + (config.limbo().enabled() ? 1 : 0);
      if (config.maxManagedProcesses() < requiredProcesses) {
        throw new ConfigurationException(
            "Managed lobby and SLS-Limbo require "
                + requiredProcesses
                + " managed process slots, but resources.max_managed_processes is "
                + config.maxManagedProcesses());
      }
    }
  }

  private static void validateForwarding(SLSConfig config, boolean velocityOnlineMode)
      throws ConfigurationException {
    if (config.forwarding().mode() == ForwardingMode.MODERN
        && config.forwarding().onlineMode() != velocityOnlineMode) {
      throw new ConfigurationException(
          "forwarding.online_mode is "
              + config.forwarding().onlineMode()
              + " but Velocity online-mode is "
              + velocityOnlineMode
              + "; these values must match when forwarding.mode is modern");
    }
  }
}
