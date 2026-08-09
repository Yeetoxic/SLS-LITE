package net.slimelabs.slslite.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StartupSetupChecklistTest {

  @Test
  void reportsAReadyProductionInstallation() {
    StartupSetupChecklist.Report report = StartupSetupChecklist.assess(input(2, 0, 0, false, null));

    assertEquals(0, report.count(StartupSetupChecklist.Level.ACTION));
    assertTrue(report.consoleSummary().startsWith("READY."));
  }

  @Test
  void identifiesAnIsolatedDevelopmentChoiceWithoutCallingItBroken() {
    StartupSetupChecklist.Report report = StartupSetupChecklist.assess(input(1, 0, 0, true, null));

    assertEquals(1, report.count(StartupSetupChecklist.Level.DEVELOPMENT));
    assertTrue(report.consoleSummary().startsWith("READY (1 development choice(s))"));
  }

  @Test
  void givesAConcreteFirstRunActionWhenNoBlueprintsExist() {
    StartupSetupChecklist.Report report = StartupSetupChecklist.assess(input(0, 0, 0, false, null));

    assertTrue(report.consoleSummary().contains("ACTION NEEDED (1)"));
    assertTrue(report.consoleSummary().contains("template.yml.example"));
  }

  @Test
  void keepsValidSiblingsAvailableWhenOneDefinitionIsMalformed() {
    StartupSetupChecklist.Report report = StartupSetupChecklist.assess(input(3, 1, 0, false, null));

    assertEquals(1, report.count(StartupSetupChecklist.Level.ACTION));
    assertTrue(report.consoleSummary().contains("valid siblings remain available"));
  }

  @Test
  void doesNotInventAnEulaGateForAnOfflineCachedProvider() {
    StartupSetupChecklist.Report report = StartupSetupChecklist.assess(input(1, 0, 0, false, null));

    assertFalse(report.consoleSummary().contains("EULA"));
    assertEquals(0, report.count(StartupSetupChecklist.Level.ACTION));
  }

  @Test
  void acceptsPortableCopyOnARestrictedPterodactylHost() {
    StartupSetupChecklist.Input input = input(1, 0, 0, false, null);
    input =
        new StartupSetupChecklist.Input(
            input.hostFailures(),
            input.developmentForwarding(),
            input.forwardingSecretProblem(),
            input.routingDescription(),
            input.loadedBlueprints(),
            input.rejectedBlueprints(),
            input.eulaGates(),
            input.maxManagedProcesses(),
            input.managedMemoryMiB(),
            input.portCount(),
            "portable-copy");

    StartupSetupChecklist.Report report = StartupSetupChecklist.assess(input);

    assertEquals(0, report.count(StartupSetupChecklist.Level.ACTION));
    assertTrue(
        report.findings().stream()
            .anyMatch(finding -> finding.message().equals("selected portable-copy")));
  }

  @Test
  void boundsConsoleActionsAndAlwaysLinksOneCanonicalGuide() {
    StartupSetupChecklist.Report report =
        StartupSetupChecklist.assess(input(0, 2, 3, false, "is empty"));

    assertTrue(report.consoleSummary().contains("see details"));
    assertEquals(
        1, report.consoleSummary().split(StartupSetupChecklist.SETUP_GUIDE, -1).length - 1);
  }

  private static StartupSetupChecklist.Input input(
      int blueprints, int rejected, int eula, boolean development, String secretProblem) {
    return new StartupSetupChecklist.Input(
        0,
        development,
        secretProblem,
        "lobby=managed, SLS-Limbo=enabled",
        blueprints,
        rejected,
        eula,
        4,
        2048,
        20,
        "reflink");
  }
}
