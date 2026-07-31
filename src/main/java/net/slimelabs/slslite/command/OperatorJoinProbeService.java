package net.slimelabs.slslite.command;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs bounded Minecraft status probes without creating a synthetic player session.
 */
final class OperatorJoinProbeService implements AutoCloseable {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
  static final int DEFAULT_MAXIMUM_CONCURRENT = 4;

  private final Duration timeout;
  private final Semaphore permits;
  private final ConcurrentMap<String, CompletableFuture<Result>> active = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  OperatorJoinProbeService() {
    this(DEFAULT_TIMEOUT, DEFAULT_MAXIMUM_CONCURRENT);
  }

  OperatorJoinProbeService(Duration timeout, int maximumConcurrent) {
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Join probe timeout must be positive");
    }
    if (maximumConcurrent <= 0) {
      throw new IllegalArgumentException("Maximum concurrent join probes must be positive");
    }
    this.timeout = timeout;
    this.permits = new Semaphore(maximumConcurrent);
  }

  CompletableFuture<Result> probe(String instanceId, RegisteredServer server) {
    if (closed.get()) {
      return CompletableFuture.completedFuture(
          Result.rejected("Join-test service is shutting down."));
    }
    if (!permits.tryAcquire()) {
      return CompletableFuture.completedFuture(
          Result.rejected("Too many join tests are already running."));
    }

    CompletableFuture<Result> result = new CompletableFuture<>();
    CompletableFuture<Result> existing = active.putIfAbsent(instanceId, result);
    if (existing != null) {
      permits.release();
      return CompletableFuture.completedFuture(
          Result.rejected("A join test is already running for " + instanceId + "."));
    }

    long started = System.nanoTime();
    try {
      server
          .ping()
          .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
          .whenComplete(
              (ping, failure) -> {
                if (failure != null) {
                  result.complete(
                      Result.failed(
                          elapsedMillis(started),
                          "Status negotiation failed: " + rootMessage(failure)));
                  return;
                }
                ServerPing.Version version = ping.getVersion();
                result.complete(
                    Result.success(
                        elapsedMillis(started), version.getName(), version.getProtocol()));
              });
    } catch (RuntimeException exception) {
      result.complete(
          Result.failed(
              elapsedMillis(started), "Status negotiation failed: " + rootMessage(exception)));
    }
    result.whenComplete(
        (ignored, failure) -> {
          active.remove(instanceId, result);
          permits.release();
        });
    return result;
  }

  int activeCount() {
    return active.size();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    active.values().forEach(future -> future.cancel(true));
    active.clear();
  }

  private static long elapsedMillis(long started) {
    return Math.max(0L, Duration.ofNanos(System.nanoTime() - started).toMillis());
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    String normalized =
        message == null || message.isBlank()
            ? current.getClass().getSimpleName()
            : message.replace('\r', ' ').replace('\n', ' ').strip();
    if (normalized.length() <= 200) {
      return normalized;
    }
    return normalized.substring(0, 199) + "\u2026";
  }

  record Result(
      Status status, long elapsedMillis, String versionName, int protocol, String detail) {

    Result {
      java.util.Objects.requireNonNull(status, "status");
      versionName = versionName == null ? "" : versionName;
      detail = detail == null ? "" : detail;
    }

    static Result success(long elapsedMillis, String versionName, int protocol) {
      return new Result(Status.SUCCESS, elapsedMillis, versionName, protocol, "");
    }

    static Result failed(long elapsedMillis, String detail) {
      return new Result(Status.FAILED, elapsedMillis, "", -1, detail);
    }

    static Result rejected(String detail) {
      return new Result(Status.REJECTED, 0, "", -1, detail);
    }
  }

  enum Status {
    SUCCESS,
    FAILED,
    REJECTED
  }
}
