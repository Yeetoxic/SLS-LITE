package net.slimelabs.slslite.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JoinTimingReporterTest {

  @Test
  void failureTakesPrecedenceOverConnectionResult() {
    assertEquals(
        "failed",
        JoinTimingReporter.connectionOutcome(null, new IllegalStateException("connection failed")));
  }

  @Test
  void missingConnectionResultIsReportedAsUnknown() {
    assertEquals("unknown", JoinTimingReporter.connectionOutcome(null, null));
  }

  @Test
  void durationFormattingIsStableAndNonNegative() {
    assertEquals("1.235ms", JoinTimingReporter.formatMillis(1_234_567));
    assertEquals("0.000ms", JoinTimingReporter.formatMillis(-1));
  }
}
