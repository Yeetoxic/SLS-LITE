package net.slimelabs.slslite.api;

import net.slimelabs.slslite.api.event.CatalogDelta;
import net.slimelabs.slslite.api.event.CatalogReloadScope;

/** Bounded result of one committed atomic definition reload. */
public record DefinitionReloadResult(
    String correlationId,
    CatalogReloadScope scope,
    CatalogDelta blueprints,
    CatalogDelta software,
    int acceptedBlueprints,
    int rejectedBlueprints,
    DefinitionReloadImpact impact) {

  public DefinitionReloadResult {
    if (correlationId == null
        || correlationId.isBlank()
        || correlationId.length() > 64
        || correlationId.indexOf('\n') >= 0
        || correlationId.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("correlationId must be one line of 1 to 64 characters");
    }
    scope = java.util.Objects.requireNonNull(scope, "scope");
    blueprints = java.util.Objects.requireNonNull(blueprints, "blueprints");
    software = java.util.Objects.requireNonNull(software, "software");
    impact = java.util.Objects.requireNonNull(impact, "impact");
    if (acceptedBlueprints < 0 || rejectedBlueprints < 0) {
      throw new IllegalArgumentException("blueprint counts must not be negative");
    }
  }
}
