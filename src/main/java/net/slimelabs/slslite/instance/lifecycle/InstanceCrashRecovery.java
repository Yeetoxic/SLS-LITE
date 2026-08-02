package net.slimelabs.slslite.instance.lifecycle;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.slimelabs.slslite.blueprint.BlueprintCrashRecoveryPolicy;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import org.slf4j.Logger;

/** Serializes bounded restart scheduling for explicitly recoverable persistent instances. */
public final class InstanceCrashRecovery implements AutoCloseable {

  @FunctionalInterface
  public interface Restarter {
    ManagedInstance restart(String instanceId) throws InstanceOperationException;
  }

  private final ScheduledExecutorService scheduler;
  private final Restarter restarter;
  private final Logger logger;
  private final Map<String, RecoveryState> states = new HashMap<>();
  private boolean closed;

  public InstanceCrashRecovery(Restarter restarter, Logger logger) {
    this(
        restarter,
        logger,
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "sls-lite-instance-recovery");
              thread.setDaemon(true);
              return thread;
            }));
  }

  InstanceCrashRecovery(Restarter restarter, Logger logger, ScheduledExecutorService scheduler) {
    this.restarter = java.util.Objects.requireNonNull(restarter, "restarter");
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
    this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
  }

  public synchronized void ready(ManagedInstance instance) {
    BlueprintCrashRecoveryPolicy policy = policy(instance);
    if (!recoverable(instance, policy) || closed) {
      return;
    }
    RecoveryState state = states.computeIfAbsent(instance.id(), ignored -> new RecoveryState());
    cancel(state.stableTask);
    ManagedInstance expected = instance;
    state.stableTask =
        scheduler.schedule(
            () -> resetIfCurrent(expected), policy.stableAfter().toNanos(), TimeUnit.NANOSECONDS);
  }

  public synchronized void exited(ManagedInstance instance, boolean unexpected) {
    RecoveryState state = states.computeIfAbsent(instance.id(), ignored -> new RecoveryState());
    cancel(state.stableTask);
    state.stableTask = null;
    if (!unexpected || closed) {
      cancelState(instance.id());
      return;
    }
    BlueprintCrashRecoveryPolicy policy = policy(instance);
    if (!recoverable(instance, policy)) {
      states.remove(instance.id());
      return;
    }
    schedule(instance.id(), policy, state);
  }

  public synchronized void cancelRecovery(String instanceId) {
    cancelState(instanceId);
  }

  @Override
  public void close() {
    close(Duration.ofSeconds(5));
  }

  public void close(Duration timeout) {
    java.util.Objects.requireNonNull(timeout, "timeout");
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      states.values().forEach(RecoveryState::cancel);
      states.clear();
    }
    scheduler.shutdownNow();
    try {
      if (!scheduler.awaitTermination(Math.max(0L, timeout.toNanos()), TimeUnit.NANOSECONDS)) {
        logger.warn("Timed out waiting for automatic instance recovery tasks to stop");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      logger.warn("Interrupted while waiting for automatic instance recovery tasks to stop");
    }
  }

  private void schedule(
      String instanceId, BlueprintCrashRecoveryPolicy policy, RecoveryState state) {
    if (policy.exhausted(state.attempts)) {
      logger.error(
          "Automatic instance recovery exhausted for {} after {} attempt(s)",
          instanceId,
          state.attempts);
      states.remove(instanceId);
      return;
    }
    int attempt = ++state.attempts;
    Duration delay = policy.backoff(attempt);
    cancel(state.retryTask);
    logger.warn(
        "Scheduling automatic recovery for {} in {} second(s), attempt {}/{}",
        instanceId,
        delay.toSeconds(),
        attempt,
        policy.maxAttempts());
    state.retryTask =
        scheduler.schedule(
            () -> attempt(instanceId, policy), delay.toNanos(), TimeUnit.NANOSECONDS);
  }

  private void attempt(String instanceId, BlueprintCrashRecoveryPolicy policy) {
    synchronized (this) {
      if (closed || !states.containsKey(instanceId)) {
        return;
      }
      states.get(instanceId).retryTask = null;
    }
    try {
      restarter.restart(instanceId);
      logger.info("Automatic instance recovery started for {}", instanceId);
    } catch (InstanceOperationException exception) {
      failedAttempt(instanceId, policy, exception);
    }
  }

  private synchronized void failedAttempt(
      String instanceId, BlueprintCrashRecoveryPolicy policy, Throwable failure) {
    RecoveryState state = states.get(instanceId);
    if (closed || state == null || state.retryTask != null) {
      return;
    }
    logger.warn(
        "Automatic instance recovery attempt failed for {}: {}", instanceId, rootMessage(failure));
    schedule(instanceId, policy, state);
  }

  private synchronized void resetIfCurrent(ManagedInstance instance) {
    RecoveryState state = states.get(instance.id());
    if (closed || state == null || instance.stoppedFuture().isDone()) {
      return;
    }
    state.attempts = 0;
    state.stableTask = null;
    logger.info("Automatic recovery budget reset after stable runtime for {}", instance.id());
  }

  private static BlueprintCrashRecoveryPolicy policy(ManagedInstance instance) {
    return BlueprintCrashRecoveryPolicy.from(instance.blueprint());
  }

  private static boolean recoverable(
      ManagedInstance instance, BlueprintCrashRecoveryPolicy policy) {
    return instance.blueprint().save() && policy.enabled() && policy.maxAttempts() > 0;
  }

  private void cancelState(String instanceId) {
    RecoveryState removed = states.remove(instanceId);
    if (removed != null) {
      removed.cancel();
    }
  }

  private static void cancel(ScheduledFuture<?> future) {
    if (future != null) {
      future.cancel(false);
    }
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private static final class RecoveryState {
    private int attempts;
    private ScheduledFuture<?> retryTask;
    private ScheduledFuture<?> stableTask;

    private void cancel() {
      InstanceCrashRecovery.cancel(retryTask);
      InstanceCrashRecovery.cancel(stableTask);
    }
  }
}
