package net.slimelabs.slslite.config;

import java.io.IOException;
import java.time.Instant;
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
      BlueprintRepository.Snapshot blueprintCandidate =
          reloadBlueprints ? blueprints.loadSnapshot() : blueprintBefore;
      SoftwareProfileRepository.Snapshot softwareCandidate =
          reloadSoftware ? softwareProfiles.loadSnapshot() : softwareBefore;

      java.util.Map<String, net.slimelabs.slslite.blueprint.Blueprint> resolvedBlueprints =
          DefinitionCatalog.resolveBlueprints(
              blueprintCandidate.values(), softwareCandidate.values());

      ConfigurationValidator.validate(
          config, resolvedBlueprints.values(), softwareCandidate.getAll());

      catalog.install(resolvedBlueprints, softwareCandidate.values());
      return new DefinitionReloadReport(
          DefinitionReloadReport.delta(blueprintBefore.values(), resolvedBlueprints),
          DefinitionReloadReport.delta(softwareBefore.values(), softwareCandidate.values()));
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
