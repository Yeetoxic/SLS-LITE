package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.log.SLSDetailLog;
import org.slf4j.Logger;

final class JoinTimingReporter {

  private final ServerController instances;
  private final Logger logger;
  private final SLSDetailLog detailLog;

  JoinTimingReporter(ServerController instances, Logger logger) {
    this(instances, logger, SLSDetailLog.disabled());
  }

  JoinTimingReporter(ServerController instances, Logger logger, SLSDetailLog detailLog) {
    this.instances = java.util.Objects.requireNonNull(instances, "instances");
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
  }

  void connected(RegisteredServer server) {
    String instanceId = server.getServerInfo().getName();
    try {
      instances
          .get(instanceId)
          .recordFirstPlayerConnected()
          .ifPresent(
              elapsed -> {
                String formatted = formatMillis(elapsed.toNanos());
                detailLog.detailed(
                    correlation(instanceId),
                    "timing",
                    "First player connected: instance={} elapsed={}",
                    instanceId,
                    formatted);
                if (detailLog == SLSDetailLog.disabled()) {
                  logger.info("First-player timing: instance={} elapsed={}", instanceId, formatted);
                }
              });
    } catch (InstanceOperationException ignored) {
      // External and SLS-Limbo backends are not managed instances.
    }
  }

  void connection(
      String instanceId,
      JoinPhaseTimings timings,
      ConnectionRequestBuilder.Result result,
      Throwable failure) {
    complete(instanceId, timings, connectionOutcome(result, failure));
  }

  void complete(String instanceId, JoinPhaseTimings timings, String outcome) {
    timings
        .complete(outcome)
        .ifPresent(
            summary -> {
              detailLog.detailed(
                  correlation(instanceId),
                  "timing",
                  "phase=connection instance={} {}",
                  instanceId,
                  summary);
              if (detailLog == SLSDetailLog.disabled()) {
                logger.info("Player join timings: instance={} {}", instanceId, summary);
              }
            });
  }

  static String connectionOutcome(ConnectionRequestBuilder.Result result, Throwable failure) {
    if (failure != null) {
      return "failed";
    }
    if (result == null || result.getStatus() == null) {
      return "unknown";
    }
    return result.getStatus().name();
  }

  private String correlation(String instanceId) {
    try {
      return instances.get(instanceId).correlationId();
    } catch (InstanceOperationException ignored) {
      return instanceId;
    }
  }

  static String formatMillis(long nanos) {
    return String.format(java.util.Locale.ROOT, "%.3fms", Math.max(0L, nanos) / 1_000_000.0d);
  }
}
