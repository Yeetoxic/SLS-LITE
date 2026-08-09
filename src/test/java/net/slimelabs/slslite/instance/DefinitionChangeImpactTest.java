package net.slimelabs.slslite.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.slimelabs.slslite.config.DefinitionReloadReport;
import org.junit.jupiter.api.Test;

class DefinitionChangeImpactTest {

  @Test
  void reportsRunningAndPersistentInstancesWithoutMutatingThem() {
    DefinitionReloadReport report =
        new DefinitionReloadReport(
            new DefinitionReloadReport.CatalogDelta(List.of(), List.of("arena"), List.of()),
            new DefinitionReloadReport.CatalogDelta(List.of(), List.of(), List.of()),
            1,
            List.of(),
            List.of("arena"));

    DefinitionChangeImpact impact =
        DefinitionChangeImpact.assess(
            report,
            Map.of("arena.running", "arena"),
            blueprintId -> List.of("arena.running", "arena.stopped"));

    assertEquals(1, impact.affectedBlueprints());
    assertEquals(1, impact.runningInstances());
    assertEquals(2, impact.persistentInstances());
    org.junit.jupiter.api.Assertions.assertTrue(impact.nextAction().contains("were not modified"));
    org.junit.jupiter.api.Assertions.assertTrue(impact.nextAction().contains("reset rebuilds"));
  }

  @Test
  void reportsNoActionForNoOpReload() {
    DefinitionReloadReport report =
        new DefinitionReloadReport(
            new DefinitionReloadReport.CatalogDelta(List.of(), List.of(), List.of()),
            new DefinitionReloadReport.CatalogDelta(List.of(), List.of(), List.of()),
            1,
            List.of(),
            List.of());

    DefinitionChangeImpact impact =
        DefinitionChangeImpact.assess(report, Map.of(), blueprintId -> List.of());

    assertEquals("No committed definition changes require instance action.", impact.nextAction());
  }
}
