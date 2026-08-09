package net.slimelabs.slslite.instance;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.slimelabs.slslite.config.DefinitionReloadReport;

/** Immutable operator guidance for changed definitions used by managed instances. */
public record DefinitionChangeImpact(
    int affectedBlueprints, int runningInstances, int persistentInstances, String nextAction) {

  public DefinitionChangeImpact {
    if (affectedBlueprints < 0 || runningInstances < 0 || persistentInstances < 0) {
      throw new IllegalArgumentException("definition impact counts must not be negative");
    }
    nextAction = java.util.Objects.requireNonNull(nextAction, "nextAction");
  }

  public static DefinitionChangeImpact assess(
      DefinitionReloadReport report, ServerController instances) {
    java.util.Objects.requireNonNull(report, "report");
    java.util.Objects.requireNonNull(instances, "instances");
    Map<String, String> runningBlueprints =
        instances.getAll().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    ManagedInstance::id, instance -> instance.blueprint().id()));
    return assess(report, runningBlueprints, instances::persistentInstanceIds);
  }

  static DefinitionChangeImpact assess(
      DefinitionReloadReport report,
      Map<String, String> runningBlueprints,
      Function<String, java.util.Collection<String>> persistentInstanceIds) {
    java.util.Objects.requireNonNull(report, "report");
    java.util.Objects.requireNonNull(runningBlueprints, "runningBlueprints");
    java.util.Objects.requireNonNull(persistentInstanceIds, "persistentInstanceIds");
    Set<String> affected = Set.copyOf(report.affectedBlueprints());
    Set<String> running = new HashSet<>();
    runningBlueprints.entrySet().stream()
        .filter(entry -> affected.contains(entry.getValue()))
        .map(Map.Entry::getKey)
        .forEach(running::add);
    Set<String> persistent = new HashSet<>();
    affected.forEach(blueprintId -> persistent.addAll(persistentInstanceIds.apply(blueprintId)));
    boolean changed = report.blueprints().changedCount() + report.software().changedCount() > 0;
    return new DefinitionChangeImpact(
        affected.size(),
        running.size(),
        persistent.size(),
        guidance(changed, running.size(), persistent.size()));
  }

  private static String guidance(boolean changed, int running, int persistent) {
    if (!changed) {
      return "No committed definition changes require instance action.";
    }
    if (running == 0 && persistent == 0) {
      return "Changes apply to future instance assembly; no existing managed instances are affected.";
    }
    return running
        + " running and "
        + persistent
        + " persistent instance(s) use changed definitions and were not modified. "
        + "Restart reuses existing files and may reject structural drift; reset rebuilds from "
        + "current definitions and sources.";
  }
}
