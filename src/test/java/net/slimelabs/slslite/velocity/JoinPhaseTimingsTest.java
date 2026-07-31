package net.slimelabs.slslite.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class JoinPhaseTimingsTest {

  @Test
  void recordsQueueAndTransferUsingMonotonicTime() {
    AtomicLong ticker = new AtomicLong();
    JoinPhaseTimings timings = new JoinPhaseTimings(ticker::get);

    ticker.addAndGet(125_000_000);
    timings.backendReady();
    ticker.addAndGet(25_000_000);
    timings.transferStarted();
    ticker.addAndGet(50_000_000);

    assertEquals(
        "outcome=success total=200.000ms queue=150.000ms " + "transfer=50.000ms",
        timings.complete("SUCCESS").orElseThrow());
    assertTrue(timings.complete("success").isEmpty());
  }

  @Test
  void reportsCancelledQueueWithoutAStartedTransfer() {
    AtomicLong ticker = new AtomicLong(10_000_000);
    JoinPhaseTimings timings = new JoinPhaseTimings(ticker::get);
    ticker.addAndGet(75_000_000);

    assertEquals(
        "outcome=cancelled total=75.000ms queue=75.000ms",
        timings.complete("cancelled").orElseThrow());
  }

  @Test
  void clampsRegressingTicker() {
    AtomicLong ticker = new AtomicLong(20_000_000);
    JoinPhaseTimings timings = new JoinPhaseTimings(ticker::get);
    timings.transferStarted();
    ticker.set(10_000_000);

    assertEquals(
        "outcome=failed total=0.000ms queue=0.000ms transfer=0.000ms",
        timings.complete("failed").orElseThrow());
  }

  @Test
  void directTransferOmitsQueuePhase() {
    AtomicLong ticker = new AtomicLong();
    JoinPhaseTimings timings = new JoinPhaseTimings(ticker::get, false);
    timings.transferStarted();
    ticker.addAndGet(40_000_000);

    assertEquals(
        "outcome=success total=40.000ms transfer=40.000ms",
        timings.complete("success").orElseThrow());
  }
}
