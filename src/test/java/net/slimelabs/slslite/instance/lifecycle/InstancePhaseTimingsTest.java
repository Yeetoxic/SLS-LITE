package net.slimelabs.slslite.instance.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancePhaseTimingsTest {

    @Test
    void recordsMonotonicProvisioningPhasesAndFormatsBoundedSummary() {
        AtomicLong ticker = new AtomicLong();
        InstancePhaseTimings timings = new InstancePhaseTimings(ticker::get);

        ticker.addAndGet(2_000_000);
        timings.finish(InstancePhaseTimings.Phase.DISPATCH_QUEUE);
        timings.begin(InstancePhaseTimings.Phase.SOFTWARE_RESOLUTION);
        ticker.addAndGet(1_159_000_000);
        timings.finish(InstancePhaseTimings.Phase.SOFTWARE_RESOLUTION);
        timings.begin(InstancePhaseTimings.Phase.FILE_PREPARATION);
        ticker.addAndGet(60_189_000_000L);
        timings.finish(InstancePhaseTimings.Phase.FILE_PREPARATION);
        timings.provisioned();

        String summary = timings.provisioningSummary("ready").orElseThrow();

        assertEquals(
                "outcome=ready total=61350.000ms dispatch=2.000ms "
                        + "software=1159.000ms files=60189.000ms",
                summary
        );
        assertTrue(timings.provisioningSummary("ready").isEmpty());
    }

    @Test
    void ignoresDuplicateCompletionAndClampsARegressingTicker() {
        AtomicLong ticker = new AtomicLong(10_000_000);
        InstancePhaseTimings timings = new InstancePhaseTimings(ticker::get);

        timings.begin(InstancePhaseTimings.Phase.PROCESS_LAUNCH);
        ticker.set(9_000_000);
        timings.finish(InstancePhaseTimings.Phase.PROCESS_LAUNCH);
        ticker.set(20_000_000);
        timings.finish(InstancePhaseTimings.Phase.PROCESS_LAUNCH);

        assertEquals(
                0L,
                timings.elapsedNanos(
                        InstancePhaseTimings.Phase.PROCESS_LAUNCH
                ).orElseThrow()
        );
    }

    @Test
    void reportsShutdownAndCleanupOnce() {
        AtomicLong ticker = new AtomicLong();
        InstancePhaseTimings timings = new InstancePhaseTimings(ticker::get);

        ticker.addAndGet(5_000_000);
        timings.begin(InstancePhaseTimings.Phase.SHUTDOWN);
        ticker.addAndGet(7_000_000);
        timings.finish(InstancePhaseTimings.Phase.SHUTDOWN);
        timings.begin(InstancePhaseTimings.Phase.CLEANUP);
        ticker.addAndGet(3_000_000);
        timings.finish(InstancePhaseTimings.Phase.CLEANUP);

        assertEquals(
                "outcome=stopped total=15.000ms shutdown=7.000ms cleanup=3.000ms",
                timings.terminationSummary("stopped").orElseThrow()
        );
        assertTrue(timings.terminationSummary("stopped").isEmpty());
    }

    @Test
    void recordsFirstPlayerFromProvisioningCompletionOnce() {
        AtomicLong ticker = new AtomicLong();
        InstancePhaseTimings timings = new InstancePhaseTimings(ticker::get);
        ticker.addAndGet(10_000_000);
        timings.provisioned();
        ticker.addAndGet(250_000_000);

        assertEquals(
                250_000_000L,
                timings.firstPlayerConnected().orElseThrow()
        );
        assertTrue(timings.firstPlayerConnected().isEmpty());
    }
}
