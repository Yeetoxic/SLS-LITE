package net.slimelabs.slslite.host;

import java.io.IOException;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessSummary;
import net.slimelabs.slslite.config.DefinitionReloadReport;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.ForwardingSecretFile;
import net.slimelabs.slslite.config.SLSConfig;

/** Collects bounded, non-mutating startup facts for {@link StartupSetupChecklist}. */
public final class StartupSetupInspector {

  public StartupSetupChecklist.Report inspect(
      SLSConfig config,
      DefinitionReloadReport definitions,
      HostCapabilityReport capabilities,
      BlueprintReadinessSummary readiness) {
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
            readiness.ready(),
            readiness.actionNeeded(),
            readiness.temporarilyUnavailable(),
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
}
