package net.slimelabs.slslite.blueprint.readiness;

import java.util.List;
import net.slimelabs.slslite.log.SLSDetailLog;

/** Bounded detail-log rendering shared by startup and definition reload. */
public final class BlueprintReadinessDiagnostics {

  private static final int MAXIMUM_DETAILED_REPORTS = 100;

  private BlueprintReadinessDiagnostics() {}

  public static void write(
      BlueprintReadinessCatalog readiness, SLSDetailLog detailLog, String correlationId) {
    List<BlueprintReadinessReport> nonReady =
        readiness.reports().stream()
            .filter(report -> report.state() != BlueprintReadinessState.READY)
            .toList();
    nonReady.stream()
        .limit(MAXIMUM_DETAILED_REPORTS)
        .forEach(
            report ->
                report
                    .issues()
                    .forEach(
                        issue ->
                            detailLog.normal(
                                correlationId,
                                "blueprint-readiness",
                                "{} [{}] {}: {}",
                                report.blueprintId(),
                                report.state(),
                                issue.code(),
                                issue.message())));
    if (nonReady.size() > MAXIMUM_DETAILED_REPORTS) {
      detailLog.normal(
          correlationId,
          "blueprint-readiness",
          "{} additional non-ready blueprint report(s) omitted by the bounded detail limit",
          nonReady.size() - MAXIMUM_DETAILED_REPORTS);
    }
  }
}
