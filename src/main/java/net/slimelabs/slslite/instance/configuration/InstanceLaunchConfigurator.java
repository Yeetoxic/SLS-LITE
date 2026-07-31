package net.slimelabs.slslite.instance.configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.slimelabs.slslite.blueprint.Blueprint;
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

  public InstanceLaunchConfigurator(
      ForwardingConfig forwarding, JavaJarProcessSpecFactory processSpecs) {
    this.forwarding = java.util.Objects.requireNonNull(forwarding, "forwarding");
    this.processSpecs = java.util.Objects.requireNonNull(processSpecs, "processSpecs");
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
    TextFileConfigEditor.apply(instanceDirectory, blueprint.textFileConfigs());
    ServerPropertiesEditor.applyManagedNetworkSettings(
        instanceDirectory, port, blueprint.maxPlayers(), configuredProperties);
    YamlConfigEditor.apply(instanceDirectory, blueprint.yamlConfigs());
    if (profile.configurator() == SoftwareConfigurator.PAPER) {
      PaperForwardingEditor.apply(instanceDirectory, forwarding);
    }
    return processSpecs.create(profile, blueprint, instanceId, instanceDirectory, port);
  }
}
