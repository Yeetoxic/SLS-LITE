package net.slimelabs.slslite.instance.configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintProcessTimeouts;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpec;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;

/**
 * Applies blueprint and managed runtime configuration before producing the
 * shell-free child-process specification.
 */
public final class InstanceLaunchConfigurator {

  private final ForwardingConfig forwarding;
  private final JavaJarProcessSpecFactory processSpecs;
  private final int proxyPlayerLimit;

  public InstanceLaunchConfigurator(
      ForwardingConfig forwarding, JavaJarProcessSpecFactory processSpecs) {
    this(forwarding, processSpecs, 0);
  }

  public InstanceLaunchConfigurator(
      ForwardingConfig forwarding, JavaJarProcessSpecFactory processSpecs, int proxyPlayerLimit) {
    this.forwarding = java.util.Objects.requireNonNull(forwarding, "forwarding");
    this.processSpecs = java.util.Objects.requireNonNull(processSpecs, "processSpecs");
    if (proxyPlayerLimit < 0) {
      throw new IllegalArgumentException("proxyPlayerLimit must not be negative");
    }
    this.proxyPlayerLimit = proxyPlayerLimit;
  }

  public ProcessSpec configure(
      SoftwareProfile profile,
      Blueprint blueprint,
      String instanceId,
      Path instanceDirectory,
      int port)
      throws IOException, InstancePreparationException, ProcessSpecificationException {
    Map<String, String> configuredProperties = new LinkedHashMap<>(profile.serverProperties());
    configuredProperties.putAll(blueprint.serverProperties());
    Map<String, String> placeholders =
        Map.of(
            "instance_id", instanceId,
            "blueprint_id", blueprint.id(),
            "version", blueprint.version(),
            "port", Integer.toString(port),
            "max_players", Integer.toString(blueprint.maxPlayers()),
            "memory_mib", Integer.toString(blueprint.memoryLimitMiB()));
    configuredProperties = RuntimeConfigPlaceholders.strings(configuredProperties, placeholders);
    Map<String, Map<String, Object>> yamlConfigs =
        RuntimeConfigPlaceholders.yaml(blueprint.yamlConfigs(), placeholders);
    Map<String, Map<String, String>> textConfigs =
        RuntimeConfigPlaceholders.text(blueprint.textFileConfigs(), placeholders);
    ServerDistancePolicy.validate(blueprint.version(), configuredProperties);
    TextFileConfigEditor.apply(instanceDirectory, textConfigs);
    ServerPropertiesEditor.applyManagedNetworkSettings(
        instanceDirectory,
        port,
        blueprint.maxPlayers(),
        ManagedPlayerCapacity.backendLimit(blueprint.maxPlayers(), proxyPlayerLimit),
        configuredProperties);
    YamlConfigEditor.apply(instanceDirectory, yamlConfigs);
    if (profile.configurator() == SoftwareConfigurator.PAPER) {
      PaperForwardingEditor.apply(instanceDirectory, forwarding);
    }
    ProcessSpec processSpec =
        processSpecs.create(profile, blueprint, instanceId, instanceDirectory, port);
    BlueprintProcessTimeouts timeouts = BlueprintProcessTimeouts.from(blueprint);
    return new ProcessSpec(
        processSpec.command(),
        processSpec.workingDirectory(),
        processSpec.readinessPattern(),
        timeouts.startupTimeout().orElse(processSpec.startupTimeout()),
        processSpec.stopCommand(),
        timeouts.stopTimeout().orElse(processSpec.stopTimeout()),
        processSpec.environment());
  }
}
