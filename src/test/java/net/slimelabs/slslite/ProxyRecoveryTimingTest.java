package net.slimelabs.slslite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProxyRecoveryTimingTest {

  @Test
  void reportsPrimaryRecoveryOnceWithMonotonicElapsedTime() {
    AtomicLong ticker = new AtomicLong();
    ProxyRecoveryTiming timing = new ProxyRecoveryTiming(ticker::get);
    ticker.addAndGet(12_345_000_000L);

    assertEquals(
        "outcome=ready total=12345.000ms primary=lobby.abc123",
        timing.complete("READY", "lobby.abc123").orElseThrow());
    assertTrue(timing.complete("ready", "lobby.abc123").isEmpty());
  }

  @Test
  void clampsClockRegressionAndNormalizesMissingValues() {
    AtomicLong ticker = new AtomicLong(20_000_000);
    ProxyRecoveryTiming timing = new ProxyRecoveryTiming(ticker::get);
    ticker.set(10_000_000);

    assertEquals(
        "outcome=unknown total=0.000ms primary=unavailable",
        timing.complete(" ", null).orElseThrow());
  }
}
