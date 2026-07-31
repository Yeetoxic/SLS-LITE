package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.slimelabs.slslite.config.StorageStrategy;

final class StorageProbeCache {

  private final int maximumEntries;
  private final LinkedHashMap<Identity, Map<StorageStrategy, HostCapability>> entries =
      new LinkedHashMap<>(16, 0.75f, true);

  StorageProbeCache(int maximumEntries) {
    if (maximumEntries < 1) {
      throw new IllegalArgumentException("maximumEntries must be positive");
    }
    this.maximumEntries = maximumEntries;
  }

  Identity identify(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path path = directory.toRealPath();
    FileStore store = Files.getFileStore(path);
    return new Identity(path, value(store.name()), value(store.type()), deviceIdentity(path));
  }

  synchronized Map<StorageStrategy, HostCapability> get(Identity identity) {
    invalidateChangedIdentity(identity);
    Map<StorageStrategy, HostCapability> cached = entries.get(identity);
    return cached == null ? Map.of() : Map.copyOf(cached);
  }

  synchronized void put(Identity identity, Map<StorageStrategy, HostCapability> successful) {
    invalidateChangedIdentity(identity);
    if (successful.isEmpty()) {
      return;
    }
    entries.put(identity, Map.copyOf(successful));
    while (entries.size() > maximumEntries) {
      Iterator<Identity> iterator = entries.keySet().iterator();
      iterator.next();
      iterator.remove();
    }
  }

  synchronized int size() {
    return entries.size();
  }

  private void invalidateChangedIdentity(Identity current) {
    entries
        .keySet()
        .removeIf(identity -> identity.path().equals(current.path()) && !identity.equals(current));
  }

  private static String deviceIdentity(Path path) {
    try {
      Object device = Files.getAttribute(path, "unix:dev");
      return String.valueOf(device);
    } catch (IOException | RuntimeException ignored) {
      return "unavailable";
    }
  }

  private static String value(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  static Map<StorageStrategy, HostCapability> successfulCapabilities(
      Map<StorageStrategy, HostCapability> candidates) {
    EnumMap<StorageStrategy, HostCapability> successful = new EnumMap<>(StorageStrategy.class);
    candidates.forEach(
        (strategy, capability) -> {
          if (capability.status() == HostCapabilityStatus.PASS) {
            successful.put(strategy, capability);
          }
        });
    return Map.copyOf(successful);
  }

  record Identity(Path path, String fileStoreName, String fileStoreType, String device) {}
}
