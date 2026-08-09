package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.config.DefinitionReloadReport;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.ForwardingSecretFile;
import net.slimelabs.slslite.config.SLSConfig;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareSource;

/** Collects bounded, non-mutating startup facts for {@link StartupSetupChecklist}. */
public final class StartupSetupInspector {

  private static final int MAXIMUM_EULA_BYTES = 16 * 1024;

  public StartupSetupChecklist.Report inspect(
      SLSConfig config,
      DefinitionReloadReport definitions,
      Iterable<Blueprint> blueprints,
      Iterable<SoftwareProfile> availableProfiles,
      JavaJarProcessSpecFactory processSpecFactory,
      HostCapabilityReport capabilities) {
    String routing =
        "lobby="
            + config.lobby().mode().name().toLowerCase(java.util.Locale.ROOT)
            + ", SLS-Limbo="
            + (config.limbo().enabled() ? "enabled" : "disabled");
    int hostFailures =
        (int)
            capabilities.capabilities().stream()
                .filter(capability -> capability.status() == HostCapabilityStatus.FAILURE)
                .count();
    return StartupSetupChecklist.assess(
        new StartupSetupChecklist.Input(
            hostFailures,
            config.forwarding().mode() == ForwardingMode.NONE,
            forwardingSecretProblem(config),
            routing,
            definitions.acceptedBlueprints(),
            definitions.rejectedBlueprints().size(),
            countActiveEulaGates(config, blueprints, availableProfiles, processSpecFactory),
            config.maxManagedProcesses(),
            config.totalMemoryMiB(),
            config.portRangeEnd() - config.portRangeStart() + 1,
            capabilities
                .selectedStorageStrategy()
                .map(strategy -> strategy.selectedName())
                .orElse("unavailable")));
  }

  private static String forwardingSecretProblem(SLSConfig config) {
    if (config.forwarding().mode() != ForwardingMode.MODERN) {
      return null;
    }
    try {
      ForwardingSecretFile.read(config.forwarding().secretFile());
      return null;
    } catch (IOException exception) {
      return "modern forwarding secret " + exception.getMessage();
    }
  }

  private static int countActiveEulaGates(
      SLSConfig config,
      Iterable<Blueprint> blueprints,
      Iterable<SoftwareProfile> availableProfiles,
      JavaJarProcessSpecFactory processSpecFactory) {
    if (config.software().autoAcceptEula()) {
      return 0;
    }
    Map<String, SoftwareProfile> profiles = new HashMap<>();
    availableProfiles.forEach(profile -> profiles.put(profile.id(), profile));
    Set<String> gated = new HashSet<>();
    for (Blueprint blueprint : blueprints) {
      SoftwareProfile profile = profiles.get(blueprint.software());
      if (profile == null
          || profile.source() == SoftwareSource.MANUAL
          || profile.acceptEula()
          || blueprint.softwarePath() != null) {
        continue;
      }
      if (!installedProviderArtifactExists(profile, blueprint, processSpecFactory)) {
        gated.add(profile.id() + "@" + blueprint.version());
      }
    }
    return gated.size();
  }

  private static boolean installedProviderArtifactExists(
      SoftwareProfile profile, Blueprint blueprint, JavaJarProcessSpecFactory processSpecFactory) {
    try {
      Path base = processSpecFactory.resolveBaseDirectory(profile, blueprint.version());
      Path jar = base.resolve(profile.serverJar()).normalize();
      Path metadata = base.resolve(".sls-install.properties").normalize();
      Path eula = base.resolve("eula.txt").normalize();
      return jar.startsWith(base)
          && Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)
          && Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
          && eulaAccepted(eula);
    } catch (ProcessSpecificationException exception) {
      return false;
    }
  }

  private static boolean eulaAccepted(Path path) {
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      return BoundedFileReader.readStringNoFollow(path, StandardCharsets.UTF_8, MAXIMUM_EULA_BYTES)
          .lines()
          .map(String::trim)
          .anyMatch("eula=true"::equalsIgnoreCase);
    } catch (IOException exception) {
      return false;
    }
  }
}
