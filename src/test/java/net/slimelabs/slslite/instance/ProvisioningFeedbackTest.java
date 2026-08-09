package net.slimelabs.slslite.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.slimelabs.slslite.instance.diagnostics.FailurePhase;
import org.junit.jupiter.api.Test;

class ProvisioningFeedbackTest {

  @Test
  void mapsEveryFailurePhaseToABoundedSafeLabel() {
    Map<FailurePhase, String> expected =
        Map.ofEntries(
            Map.entry(FailurePhase.INSTALLATION, "software installation"),
            Map.entry(FailurePhase.PREPARATION, "instance assembly"),
            Map.entry(FailurePhase.CONFIGURATION, "instance assembly"),
            Map.entry(FailurePhase.STARTUP, "process startup"),
            Map.entry(FailurePhase.READINESS, "backend readiness"),
            Map.entry(FailurePhase.REGISTRATION, "backend readiness"),
            Map.entry(FailurePhase.CONNECTION, "transfer"),
            Map.entry(FailurePhase.RUNTIME, "runtime"),
            Map.entry(FailurePhase.SHUTDOWN, "shutdown"),
            Map.entry(FailurePhase.CLEANUP, "shutdown"));

    expected.forEach((phase, label) -> assertEquals(label, ProvisioningFeedback.failure(phase)));
  }
}
