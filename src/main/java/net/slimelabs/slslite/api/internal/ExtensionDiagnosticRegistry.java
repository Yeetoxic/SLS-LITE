package net.slimelabs.slslite.api.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.slimelabs.slslite.api.ExtensionDiagnosticContributor;
import net.slimelabs.slslite.api.ExtensionDiagnosticFinding;
import net.slimelabs.slslite.api.ExtensionDiagnosticSeverity;
import net.slimelabs.slslite.api.ExtensionDiagnosticView;
import net.slimelabs.slslite.api.event.Subscription;
import net.slimelabs.slslite.log.DiagnosticMessages;
import net.slimelabs.slslite.log.DiagnosticRedactor;
import org.slf4j.Logger;

/** Bounded execution and redaction boundary for extension-owned operational diagnostics. */
final class ExtensionDiagnosticRegistry implements AutoCloseable {

  private static final int MAXIMUM_FINDINGS = 16;
  private static final int MAXIMUM_PENDING = 128;
  private static final Duration DEADLINE = Duration.ofSeconds(2);

  private final Logger logger;
  private final DiagnosticRedactor redactor = new DiagnosticRedactor(null, null, true);
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean refreshPending = new AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicLong generation =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.ConcurrentHashMap<String, ExtensionDiagnosticContributor>
      contributors = new java.util.concurrent.ConcurrentHashMap<>();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          4,
          4,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(MAXIMUM_PENDING),
          runnable -> {
            Thread thread = new Thread(runnable, "sls-lite-extension-diagnostics");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final java.util.concurrent.ExecutorService refreshExecutor =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "sls-lite-extension-diagnostic-refresh");
            thread.setDaemon(true);
            return thread;
          });
  private volatile List<ExtensionDiagnosticView> latest = List.of();

  ExtensionDiagnosticRegistry(Logger logger) {
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
  }

  Subscription register(String namespace, ExtensionDiagnosticContributor contributor) {
    java.util.Objects.requireNonNull(namespace, "namespace");
    java.util.Objects.requireNonNull(contributor, "contributor");
    if (closed.get()) {
      throw new IllegalStateException("Extension diagnostic registry is closed");
    }
    if (contributors.putIfAbsent(namespace, contributor) != null) {
      throw new IllegalStateException(
          "An extension diagnostic contributor is already registered for " + namespace);
    }
    if (closed.get()) {
      contributors.remove(namespace, contributor);
      throw new IllegalStateException("Extension diagnostic registry is closed");
    }
    generation.incrementAndGet();
    refresh();
    AtomicBoolean active = new AtomicBoolean(true);
    return () -> {
      if (active.compareAndSet(true, false)) {
        contributors.remove(namespace, contributor);
        latest = latest.stream().filter(view -> !view.namespace().equals(namespace)).toList();
        generation.incrementAndGet();
        refresh();
      }
    };
  }

  List<ExtensionDiagnosticView> snapshot() {
    if (closed.get()) {
      return List.of();
    }
    refresh();
    return latest;
  }

  private void refresh() {
    if (closed.get() || !refreshPending.compareAndSet(false, true)) {
      return;
    }
    try {
      long expectedGeneration = generation.get();
      refreshExecutor.execute(
          () -> {
            try {
              List<ExtensionDiagnosticView> refreshed = inspectSnapshot();
              if (!closed.get() && generation.get() == expectedGeneration) {
                latest = refreshed;
              }
            } finally {
              refreshPending.set(false);
              if (generation.get() != expectedGeneration) {
                refresh();
              }
            }
          });
    } catch (RejectedExecutionException exception) {
      refreshPending.set(false);
    }
  }

  private List<ExtensionDiagnosticView> inspectSnapshot() {
    if (closed.get() || contributors.isEmpty()) {
      return List.of();
    }
    List<Map.Entry<String, ExtensionDiagnosticContributor>> configured =
        contributors.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    Map<String, Future<ExtensionDiagnosticView>> pending = new LinkedHashMap<>();
    for (Map.Entry<String, ExtensionDiagnosticContributor> entry : configured) {
      try {
        pending.put(
            entry.getKey(),
            executor.submit(() -> inspect(entry.getKey(), entry.getValue(), Instant.now())));
      } catch (RejectedExecutionException exception) {
        logger.warn("Extension diagnostic queue is saturated");
      }
    }
    long deadline = System.nanoTime() + DEADLINE.toNanos();
    List<ExtensionDiagnosticView> views = new ArrayList<>(configured.size());
    for (Map.Entry<String, ExtensionDiagnosticContributor> entry : configured) {
      Future<ExtensionDiagnosticView> future = pending.get(entry.getKey());
      if (future == null) {
        views.add(failure(entry.getKey(), "diagnostic execution queue is full"));
        continue;
      }
      try {
        views.add(future.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      } catch (java.util.concurrent.TimeoutException exception) {
        future.cancel(true);
        views.add(failure(entry.getKey(), "diagnostic contributor timed out"));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        views.add(failure(entry.getKey(), "diagnostic inspection was interrupted"));
      } catch (java.util.concurrent.ExecutionException exception) {
        views.add(failure(entry.getKey(), "diagnostic contributor failed unexpectedly"));
      }
    }
    executor.purge();
    return List.copyOf(views);
  }

  private ExtensionDiagnosticView inspect(
      String namespace, ExtensionDiagnosticContributor contributor, Instant inspectedAt) {
    List<ExtensionDiagnosticFinding> reported =
        List.copyOf(java.util.Objects.requireNonNull(contributor.inspect(), "diagnostic findings"));
    if (reported.size() > MAXIMUM_FINDINGS) {
      return failure(namespace, "diagnostic contributor returned excessive findings");
    }
    List<ExtensionDiagnosticFinding> sanitized =
        reported.stream()
            .map(
                finding ->
                    new ExtensionDiagnosticFinding(
                        finding.code(),
                        finding.severity(),
                        DiagnosticMessages.safe(redactor.redact(finding.message()))))
            .toList();
    return new ExtensionDiagnosticView(namespace, inspectedAt, sanitized);
  }

  private static ExtensionDiagnosticView failure(String namespace, String message) {
    return new ExtensionDiagnosticView(
        namespace,
        Instant.now(),
        List.of(
            new ExtensionDiagnosticFinding(
                "contributor-error", ExtensionDiagnosticSeverity.ERROR, message)));
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    contributors.clear();
    latest = List.of();
    refreshExecutor.shutdownNow();
    executor.shutdownNow();
  }
}
