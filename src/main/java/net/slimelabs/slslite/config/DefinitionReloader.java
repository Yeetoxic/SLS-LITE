package net.slimelabs.slslite.config;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.slimelabs.slslite.blueprint.BlueprintException;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.software.SoftwareProfileRepository;

public final class DefinitionReloader {

  private DefinitionReloader() {}

  public static DefinitionReloadReport reload(
      SLSConfig config,
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      boolean reloadBlueprints,
      boolean reloadSoftware)
      throws IOException, BlueprintException, ConfigurationException {
    return reload(
        config,
        blueprints,
        softwareProfiles,
        reloadBlueprints,
        reloadSoftware,
        "reload-untracked",
        ignored -> {});
  }

  public static DefinitionReloadReport reload(
      SLSConfig config,
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      boolean reloadBlueprints,
      boolean reloadSoftware,
      String correlationId,
      Consumer<DefinitionReloadTransition> observer)
      throws IOException, BlueprintException, ConfigurationException {
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(observer, "observer");
    ReloadScope scope = scope(reloadBlueprints, reloadSoftware);
    synchronized (blueprints.catalog()) {
      try {
        DefinitionReloadReport report =
            reloadTransaction(
                config, blueprints, softwareProfiles, reloadBlueprints, reloadSoftware);
        observe(
            observer,
            new DefinitionReloadTransition(
                correlationId,
                scope,
                ReloadStatus.COMMITTED,
                ReloadFailureCategory.NONE,
                report.blueprints().added().size(),
                report.blueprints().updated().size(),
                report.blueprints().removed().size(),
                report.software().added().size(),
                report.software().updated().size(),
                report.software().removed().size(),
                Instant.now()));
        return report;
      } catch (IOException exception) {
        observeRejected(observer, correlationId, scope, ReloadFailureCategory.IO);
        throw exception;
      } catch (BlueprintException | ConfigurationException exception) {
        observeRejected(observer, correlationId, scope, ReloadFailureCategory.VALIDATION);
        throw exception;
      } catch (RuntimeException exception) {
        observeRejected(observer, correlationId, scope, ReloadFailureCategory.INTERNAL);
        throw exception;
      }
    }
  }

  private static DefinitionReloadReport reloadTransaction(
      SLSConfig config,
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      boolean reloadBlueprints,
      boolean reloadSoftware)
      throws IOException, BlueprintException, ConfigurationException {
    if (blueprints.catalog() != softwareProfiles.catalog()) {
      throw new ConfigurationException(
          "Blueprint and software repositories do not share a definition catalog");
    }
    DefinitionCatalog catalog = blueprints.catalog();
    synchronized (catalog) {
      BlueprintRepository.Snapshot blueprintBefore = blueprints.snapshot();
      SoftwareProfileRepository.Snapshot softwareBefore = softwareProfiles.snapshot();
      SoftwareProfileRepository.Snapshot softwareCandidate =
          reloadSoftware ? softwareProfiles.loadSnapshot() : softwareBefore;
      BlueprintCandidate blueprintCandidate =
          reloadBlueprints
              ? loadBlueprintCandidate(config, blueprints, softwareCandidate)
              : new BlueprintCandidate(blueprintBefore.values(), List.of());

      Map<String, net.slimelabs.slslite.blueprint.Blueprint> resolvedBlueprints =
          blueprintCandidate.values();
      if (reloadBlueprints) {
        ConfigurationValidator.validateHost(config, resolvedBlueprints, false);
      } else {
        ConfigurationValidator.validate(
            config, resolvedBlueprints.values(), softwareCandidate.getAll());
      }

      catalog.install(resolvedBlueprints, softwareCandidate.values());
      DefinitionReloadReport.CatalogDelta blueprintDelta =
          DefinitionReloadReport.delta(blueprintBefore.values(), resolvedBlueprints);
      DefinitionReloadReport.CatalogDelta softwareDelta =
          DefinitionReloadReport.delta(softwareBefore.values(), softwareCandidate.values());
      return new DefinitionReloadReport(
          blueprintDelta,
          softwareDelta,
          resolvedBlueprints.size(),
          blueprintCandidate.rejections(),
          affectedBlueprints(
              blueprintBefore.values(), resolvedBlueprints, blueprintDelta, softwareDelta));
    }
  }

  private static List<String> affectedBlueprints(
      Map<String, net.slimelabs.slslite.blueprint.Blueprint> before,
      Map<String, net.slimelabs.slslite.blueprint.Blueprint> after,
      DefinitionReloadReport.CatalogDelta blueprintDelta,
      DefinitionReloadReport.CatalogDelta softwareDelta) {
    java.util.Set<String> changedSoftware = new java.util.HashSet<>();
    changedSoftware.addAll(softwareDelta.added());
    changedSoftware.addAll(softwareDelta.updated());
    changedSoftware.addAll(softwareDelta.removed());
    java.util.Set<String> affected = new java.util.TreeSet<>();
    affected.addAll(blueprintDelta.added());
    affected.addAll(blueprintDelta.updated());
    affected.addAll(blueprintDelta.removed());
    before.values().stream()
        .filter(blueprint -> changedSoftware.contains(blueprint.software()))
        .map(net.slimelabs.slslite.blueprint.Blueprint::id)
        .forEach(affected::add);
    after.values().stream()
        .filter(blueprint -> changedSoftware.contains(blueprint.software()))
        .map(net.slimelabs.slslite.blueprint.Blueprint::id)
        .forEach(affected::add);
    return List.copyOf(affected);
  }

  private static BlueprintCandidate loadBlueprintCandidate(
      SLSConfig config,
      BlueprintRepository blueprints,
      SoftwareProfileRepository.Snapshot softwareCandidate)
      throws IOException {
    BlueprintRepository.LoadResult loaded = blueprints.loadIsolated();
    Map<String, net.slimelabs.slslite.blueprint.Blueprint> parsed = new LinkedHashMap<>();
    loaded.accepted().forEach((id, candidate) -> parsed.put(id, candidate.blueprint()));
    Map<String, net.slimelabs.slslite.blueprint.Blueprint> resolved =
        DefinitionCatalog.resolveBlueprints(parsed, softwareCandidate.values());
    List<DefinitionReloadReport.BlueprintRejection> rejections = new ArrayList<>();
    loaded.rejections().stream()
        .map(
            rejection ->
                new DefinitionReloadReport.BlueprintRejection(rejection.path(), rejection.error()))
        .forEach(rejections::add);

    Map<String, net.slimelabs.slslite.blueprint.Blueprint> accepted = new LinkedHashMap<>();
    for (Map.Entry<String, net.slimelabs.slslite.blueprint.Blueprint> entry : resolved.entrySet()) {
      try {
        ConfigurationValidator.validateBlueprint(
            config, entry.getValue(), softwareCandidate.values());
        accepted.put(entry.getKey(), entry.getValue());
      } catch (ConfigurationException exception) {
        String path = loaded.accepted().get(entry.getKey()).path();
        rejections.add(new DefinitionReloadReport.BlueprintRejection(path, exception.getMessage()));
      }
    }
    rejections.sort(
        java.util.Comparator.comparing(DefinitionReloadReport.BlueprintRejection::path));
    return new BlueprintCandidate(Map.copyOf(accepted), rejections);
  }

  private record BlueprintCandidate(
      Map<String, net.slimelabs.slslite.blueprint.Blueprint> values,
      List<DefinitionReloadReport.BlueprintRejection> rejections) {

    private BlueprintCandidate {
      values = Map.copyOf(values);
      rejections = List.copyOf(rejections);
    }
  }

  private static ReloadScope scope(boolean reloadBlueprints, boolean reloadSoftware) {
    if (reloadBlueprints && reloadSoftware) {
      return ReloadScope.ALL;
    }
    if (reloadBlueprints) {
      return ReloadScope.BLUEPRINTS;
    }
    if (reloadSoftware) {
      return ReloadScope.SOFTWARE;
    }
    throw new IllegalArgumentException("At least one definition family must be reloaded");
  }

  private static void observeRejected(
      Consumer<DefinitionReloadTransition> observer,
      String correlationId,
      ReloadScope scope,
      ReloadFailureCategory category) {
    observe(
        observer,
        new DefinitionReloadTransition(
            correlationId,
            scope,
            ReloadStatus.REJECTED,
            category,
            0,
            0,
            0,
            0,
            0,
            0,
            Instant.now()));
  }

  private static void observe(
      Consumer<DefinitionReloadTransition> observer, DefinitionReloadTransition transition) {
    try {
      observer.accept(transition);
    } catch (RuntimeException ignored) {
      // Observability must never change whether an atomic reload commits or rejects.
    }
  }

  public enum ReloadScope {
    ALL,
    BLUEPRINTS,
    SOFTWARE
  }

  public enum ReloadStatus {
    COMMITTED,
    REJECTED
  }

  public enum ReloadFailureCategory {
    NONE,
    IO,
    VALIDATION,
    INTERNAL
  }

  public record DefinitionReloadTransition(
      String correlationId,
      ReloadScope scope,
      ReloadStatus status,
      ReloadFailureCategory failureCategory,
      int blueprintsAdded,
      int blueprintsUpdated,
      int blueprintsRemoved,
      int softwareAdded,
      int softwareUpdated,
      int softwareRemoved,
      Instant occurredAt) {

    public DefinitionReloadTransition {
      if (correlationId.isBlank()) {
        throw new IllegalArgumentException("correlationId must not be blank");
      }
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(failureCategory, "failureCategory");
      Objects.requireNonNull(occurredAt, "occurredAt");
      if (blueprintsAdded < 0
          || blueprintsUpdated < 0
          || blueprintsRemoved < 0
          || softwareAdded < 0
          || softwareUpdated < 0
          || softwareRemoved < 0) {
        throw new IllegalArgumentException("reload change counts must not be negative");
      }
      if ((status == ReloadStatus.COMMITTED) != (failureCategory == ReloadFailureCategory.NONE)) {
        throw new IllegalArgumentException(
            "committed reloads require NONE; rejected reloads require a failure category");
      }
    }
  }
}
