package net.slimelabs.slslite.messaging;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.slimelabs.slslite.config.BackendMessagingConfig;

final class BackendMessageGuard {

  private static final int MAX_RATE_IDENTITIES = 4096;
  private static final int MAX_REQUEST_IDS = 8192;
  private static final long REQUEST_TTL_NANOS = Duration.ofMinutes(2).toNanos();

  private final int requestsPerWindow;
  private final long windowNanos;
  private final Map<RateKey, Window> windows = new LinkedHashMap<>();
  private final Map<UUID, Long> requests = new LinkedHashMap<>();

  BackendMessageGuard(BackendMessagingConfig config) {
    requestsPerWindow = config.requestsPerWindow();
    windowNanos = Duration.ofSeconds(config.windowSeconds()).toNanos();
  }

  synchronized boolean allowRate(String sourceId, UUID playerId, long nowNanos) {
    removeExpiredWindows(nowNanos);
    RateKey key = new RateKey(sourceId, playerId);
    Window current = windows.get(key);
    if (current == null || nowNanos - current.startedNanos() >= windowNanos) {
      putBounded(windows, key, new Window(nowNanos, 1), MAX_RATE_IDENTITIES);
      return true;
    }
    if (current.requests() >= requestsPerWindow) {
      return false;
    }
    windows.put(key, new Window(current.startedNanos(), current.requests() + 1));
    return true;
  }

  synchronized boolean firstRequest(UUID requestId, long nowNanos) {
    removeExpiredRequests(nowNanos);
    if (requests.containsKey(requestId)) {
      return false;
    }
    putBounded(requests, requestId, nowNanos, MAX_REQUEST_IDS);
    return true;
  }

  synchronized void clear() {
    windows.clear();
    requests.clear();
  }

  private void removeExpiredWindows(long nowNanos) {
    Iterator<Map.Entry<RateKey, Window>> iterator = windows.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<RateKey, Window> entry = iterator.next();
      if (nowNanos - entry.getValue().startedNanos() < windowNanos) {
        break;
      }
      iterator.remove();
    }
  }

  private void removeExpiredRequests(long nowNanos) {
    Iterator<Map.Entry<UUID, Long>> iterator = requests.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Long> entry = iterator.next();
      if (nowNanos - entry.getValue() < REQUEST_TTL_NANOS) {
        break;
      }
      iterator.remove();
    }
  }

  private static <K, V> void putBounded(Map<K, V> values, K key, V value, int maximum) {
    if (values.size() >= maximum && !values.containsKey(key)) {
      Iterator<K> iterator = values.keySet().iterator();
      if (iterator.hasNext()) {
        iterator.next();
        iterator.remove();
      }
    }
    values.put(key, value);
  }

  private record RateKey(String sourceId, UUID playerId) {}

  private record Window(long startedNanos, int requests) {}
}
