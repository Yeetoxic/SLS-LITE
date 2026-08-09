package net.slimelabs.slslite.blueprint.readiness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.slimelabs.slslite.api.BlueprintReadinessChecker;
import net.slimelabs.slslite.api.BlueprintReadinessFinding;
import net.slimelabs.slslite.api.BlueprintReadinessStatus;
import net.slimelabs.slslite.api.BlueprintView;
import net.slimelabs.slslite.api.NamespacedAnnotations;
import net.slimelabs.slslite.api.VolumeView;
import net.slimelabs.slslite.api.event.Subscription;
import net.slimelabs.slslite.blueprint.Blueprint;
import org.slf4j.Logger;

/** Bounded execution and immutable snapshots for extension-owned readiness checks. */
public final class ExtensionBlueprintReadinessRegistry implements AutoCloseable {

  private static final int MAXIMUM_FINDINGS_PER_BLUEPRINT = 8;
  private static final Duration REFRESH_DEADLINE = Duration.ofSeconds(2);
  private static final int WORKER_COUNT = 4;
  private static final int MAXIMUM_PENDING_CHECKS = 256;

  private final Logger logger;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final java.util.concurrent.ConcurrentHashMap<String, BlueprintReadinessChecker> checkers =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final AtomicLong generation = new AtomicLong();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          WORKER_COUNT,
          WORKER_COUNT,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(MAXIMUM_PENDING_CHECKS),
          runnable -> {
            Thread thread = new Thread(runnable, "sls-lite-extension-readiness");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private volatile List<BlueprintView> blueprints = List.of();
  private volatile Map<String, List<Contribution>> findings = Map.of();

  public ExtensionBlueprintReadinessRegistry(Logger logger) {
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
  }

  public Subscription register(String namespace, BlueprintReadinessChecker checker) {
    java.util.Objects.requireNonNull(namespace, "namespace");
    java.util.Objects.requireNonNull(checker, "checker");
    if (closed.get()) {
      throw new IllegalStateException("Extension blueprint readiness registry is closed");
    }
    if (checkers.putIfAbsent(namespace, checker) != null) {
      throw new IllegalStateException(
          "A blueprint readiness checker is already registered for " + namespace);
    }
    if (closed.get()) {
      checkers.remove(namespace, checker);
      throw new IllegalStateException("Extension blueprint readiness registry is closed");
    }
    generation.incrementAndGet();
    recompute();
    AtomicBoolean active = new AtomicBoolean(true);
    return () -> {
      if (active.compareAndSet(true, false) && checkers.remove(namespace, checker)) {
        generation.incrementAndGet();
        recompute();
      }
    };
  }

  public void refresh(Collection<Blueprint> configured) {
    refreshViews(configured.stream().map(ExtensionBlueprintReadinessRegistry::view).toList());
  }

  public void refreshViews(Collection<BlueprintView> configured) {
    if (closed.get()) {
      return;
    }
    blueprints = List.copyOf(configured);
    generation.incrementAndGet();
    recompute();
  }

  public List<Contribution> findings(String blueprintId) {
    return findings.getOrDefault(blueprintId, List.of());
  }

  private void recompute() {
    long expectedGeneration = generation.get();
    List<BlueprintView> blueprintSnapshot = blueprints;
    List<Map.Entry<String, BlueprintReadinessChecker>> checkerSnapshot =
        checkers.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    if (blueprintSnapshot.isEmpty() || checkerSnapshot.isEmpty()) {
      if (generation.get() == expectedGeneration) {
        findings = Map.of();
      }
      return;
    }

    Map<String, Future<Map<String, List<Contribution>>>> pending = new LinkedHashMap<>();
    for (Map.Entry<String, BlueprintReadinessChecker> entry : checkerSnapshot) {
      try {
        pending.put(
            entry.getKey(),
            executor.submit(() -> inspect(entry.getKey(), entry.getValue(), blueprintSnapshot)));
      } catch (RejectedExecutionException exception) {
        logger.warn("Extension blueprint readiness queue is saturated");
      }
    }

    long deadline = System.nanoTime() + REFRESH_DEADLINE.toNanos();
    Map<String, List<Contribution>> merged = new LinkedHashMap<>();
    for (Map.Entry<String, BlueprintReadinessChecker> checker : checkerSnapshot) {
      Future<Map<String, List<Contribution>>> future = pending.get(checker.getKey());
      Map<String, List<Contribution>> result;
      if (future == null) {
        result = failure(checker.getKey(), blueprintSnapshot, "checker execution queue is full");
      } else {
        try {
          long remaining = Math.max(1L, deadline - System.nanoTime());
          result = future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
          future.cancel(true);
          result = failure(checker.getKey(), blueprintSnapshot, "checker timed out");
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          future.cancel(true);
          result = failure(checker.getKey(), blueprintSnapshot, "checker was interrupted");
        } catch (java.util.concurrent.ExecutionException exception) {
          result = failure(checker.getKey(), blueprintSnapshot, "checker failed unexpectedly");
        }
      }
      result.forEach(
          (blueprintId, contributions) ->
              append(merged, blueprintId, contributions, MAXIMUM_FINDINGS_PER_BLUEPRINT));
    }
    executor.purge();
    if (generation.get() == expectedGeneration) {
      Map<String, List<Contribution>> immutable = new LinkedHashMap<>();
      merged.forEach((id, values) -> immutable.put(id, List.copyOf(values)));
      findings = Map.copyOf(immutable);
    }
  }

  private static Map<String, List<Contribution>> inspect(
      String namespace, BlueprintReadinessChecker checker, List<BlueprintView> blueprints) {
    Map<String, List<Contribution>> inspected = new LinkedHashMap<>();
    for (BlueprintView blueprint : blueprints) {
      if (!blueprint.annotations().containsKey(namespace)) {
        continue;
      }
      try {
        NamespacedAnnotations annotations = annotations(namespace, blueprint);
        List<BlueprintReadinessFinding> reported =
            List.copyOf(
                java.util.Objects.requireNonNull(
                    checker.check(blueprint, annotations), "checker findings"));
        if (reported.size() > MAXIMUM_FINDINGS_PER_BLUEPRINT) {
          throw new IllegalArgumentException("checker returned invalid or excessive findings");
        }
        inspected.put(
            blueprint.id(),
            reported.stream().map(finding -> new Contribution(namespace, finding)).toList());
      } catch (RuntimeException exception) {
        inspected.put(
            blueprint.id(),
            List.of(
                temporaryFailure(
                    namespace, "checker failed: " + exception.getClass().getSimpleName())));
      }
    }
    return inspected;
  }

  private static NamespacedAnnotations annotations(String namespace, BlueprintView blueprint) {
    Object value = blueprint.annotations().get(namespace);
    if (!(value instanceof Map<?, ?> values)) {
      throw new IllegalArgumentException("annotation namespace must be an object");
    }
    Map<String, Object> copied = new LinkedHashMap<>();
    values.forEach(
        (key, item) -> {
          if (!(key instanceof String text)) {
            throw new IllegalArgumentException("annotation key must be a string");
          }
          copied.put(text, item);
        });
    return new NamespacedAnnotations(namespace, copied);
  }

  private static Map<String, List<Contribution>> failure(
      String namespace, List<BlueprintView> blueprints, String reason) {
    Map<String, List<Contribution>> failures = new LinkedHashMap<>();
    blueprints.stream()
        .filter(blueprint -> blueprint.annotations().containsKey(namespace))
        .forEach(
            blueprint ->
                failures.put(blueprint.id(), List.of(temporaryFailure(namespace, reason))));
    return failures;
  }

  private static Contribution temporaryFailure(String namespace, String reason) {
    return new Contribution(
        namespace,
        new BlueprintReadinessFinding(
            "checker-error", BlueprintReadinessStatus.TEMPORARILY_UNAVAILABLE, reason));
  }

  private static void append(
      Map<String, List<Contribution>> target,
      String blueprintId,
      List<Contribution> additions,
      int maximum) {
    List<Contribution> values = target.computeIfAbsent(blueprintId, ignored -> new ArrayList<>());
    for (Contribution addition : additions) {
      if (values.size() >= maximum) {
        break;
      }
      values.add(addition);
    }
  }

  private static BlueprintView view(Blueprint blueprint) {
    return new BlueprintView(
        blueprint.id(),
        blueprint.name(),
        blueprint.type(),
        blueprint.software(),
        blueprint.version(),
        blueprint.memoryLimitMiB(),
        blueprint.maxPlayers(),
        blueprint.maxInstances(),
        blueprint.save(),
        blueprint.volumes().stream()
            .map(
                volume ->
                    new VolumeView(
                        volume.name(),
                        volume.source(),
                        volume.target(),
                        volume.mode().name().toLowerCase(java.util.Locale.ROOT)))
            .toList(),
        !blueprint.copies().isEmpty(),
        blueprint.environment().keySet(),
        blueprint.annotations());
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    generation.incrementAndGet();
    checkers.clear();
    findings = Map.of();
    executor.shutdownNow();
  }

  public record Contribution(String namespace, BlueprintReadinessFinding finding) {
    public Contribution {
      java.util.Objects.requireNonNull(namespace, "namespace");
      java.util.Objects.requireNonNull(finding, "finding");
    }
  }
}
