package net.slimelabs.slslite.instance.diagnostics;

import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;
import org.slf4j.Logger;

/**
 * Keeps lifecycle timing presentation out of instance orchestration.
 */
public final class InstanceTimingReporter {

    private final Logger logger;

    public InstanceTimingReporter(Logger logger) {
        this.logger = logger;
    }

    public void logProvisioning(
            String instanceId,
            InstancePhaseTimings timings,
            String outcome
    ) {
        timings.provisioningSummary(outcome).ifPresent(summary ->
                logger.info(
                        "Instance provisioning timings: {} {}",
                        instanceId,
                        summary
                )
        );
    }

    public void logTermination(
            String instanceId,
            InstancePhaseTimings timings,
            String outcome
    ) {
        timings.terminationSummary(outcome).ifPresent(summary ->
                logger.info(
                        "Instance termination timings: {} {}",
                        instanceId,
                        summary
                )
        );
    }
}
