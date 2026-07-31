package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ServerController;
import org.slf4j.Logger;

final class JoinTimingReporter {

  private final ServerController instances;
  private final Logger logger;

  JoinTimingReporter(ServerController instances, Logger logger) {
    this.instances = java.util.Objects.requireNonNull(instances, "instances");
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
  }

  void connected(RegisteredServer server) {
    String instanceId = server.getServerInfo().getName();
    try {
      instances
          .get(instanceId)
          .recordFirstPlayerConnected()
          .ifPresent(
              elapsed ->
                  logger.info(
                      "First-player timing: instance={} elapsed={}",
                      instanceId,
                      formatMillis(elapsed.toNanos())));
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
            summary -> logger.info("Player join timings: instance={} {}", instanceId, summary));
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

  static String formatMillis(long nanos) {
    return String.format(java.util.Locale.ROOT, "%.3fms", Math.max(0L, nanos) / 1_000_000.0d);
  }
}
