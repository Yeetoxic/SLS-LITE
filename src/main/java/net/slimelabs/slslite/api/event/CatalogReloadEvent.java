package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/** Terminal observation of one atomic catalog reload attempt. */
public record CatalogReloadEvent(
    long sequence,
    Instant occurredAt,
    String correlationId,
    CatalogReloadScope scope,
    CatalogReloadStatus status,
    CatalogReloadFailureCategory failureCategory,
    CatalogDelta blueprints,
    CatalogDelta software)
    implements SLSLiteEvent {

  public CatalogReloadEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(failureCategory, "failureCategory");
    Objects.requireNonNull(blueprints, "blueprints");
    Objects.requireNonNull(software, "software");
    if ((status == CatalogReloadStatus.COMMITTED)
        != (failureCategory == CatalogReloadFailureCategory.NONE)) {
      throw new IllegalArgumentException(
          "committed reloads require NONE; rejected reloads require a failure category");
    }
    if (status == CatalogReloadStatus.REJECTED
        && (blueprints.changed() != 0 || software.changed() != 0)) {
      throw new IllegalArgumentException("rejected reloads must not report committed changes");
    }
  }
}
