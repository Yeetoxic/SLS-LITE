package net.slimelabs.slslite.instance.diagnostics;

import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;
import net.slimelabs.slslite.log.SLSDetailLog;
import org.slf4j.Logger;

/**
 * Keeps lifecycle timing presentation out of instance orchestration.
 */
public final class InstanceTimingReporter {

  private final Logger logger;
  private final SLSDetailLog detailLog;

  public InstanceTimingReporter(Logger logger) {
    this(logger, SLSDetailLog.disabled());
  }

  public InstanceTimingReporter(Logger logger, SLSDetailLog detailLog) {
    this.logger = logger;
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
  }

  public void logProvisioning(String instanceId, InstancePhaseTimings timings, String outcome) {
    logProvisioning(instanceId, instanceId, timings, outcome);
  }

  public void logProvisioning(
      String correlationId, String instanceId, InstancePhaseTimings timings, String outcome) {
    timings
        .provisioningSummary(outcome)
        .ifPresent(
            summary -> {
              detailLog.detailed(
                  correlationId,
                  "timing",
                  "Instance provisioning: instance={} {}",
                  instanceId,
                  summary);
              if (detailLog == SLSDetailLog.disabled()) {
                logger.info("Instance provisioning timings: {} {}", instanceId, summary);
              }
            });
  }

  public void logTermination(String instanceId, InstancePhaseTimings timings, String outcome) {
    logTermination(instanceId, instanceId, timings, outcome);
  }

  public void logTermination(
      String correlationId, String instanceId, InstancePhaseTimings timings, String outcome) {
    timings
        .terminationSummary(outcome)
        .ifPresent(
            summary -> {
              detailLog.detailed(
                  correlationId,
                  "timing",
                  "Instance termination: instance={} {}",
                  instanceId,
                  summary);
              if (detailLog == SLSDetailLog.disabled()) {
                logger.info("Instance termination timings: {} {}", instanceId, summary);
              }
            });
  }
}
