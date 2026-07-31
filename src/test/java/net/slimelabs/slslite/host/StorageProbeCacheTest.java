package net.slimelabs.slslite.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import net.slimelabs.slslite.config.StorageStrategy;
import org.junit.jupiter.api.Test;

class StorageProbeCacheTest {

  @Test
  void invalidatesSamePathWhenFilesystemIdentityChanges() {
    StorageProbeCache cache = new StorageProbeCache(4);
    Path path = Path.of("instances").toAbsolutePath().normalize();
    StorageProbeCache.Identity first = new StorageProbeCache.Identity(path, "first", "xfs", "1");
    StorageProbeCache.Identity replacement =
        new StorageProbeCache.Identity(path, "second", "btrfs", "2");
    HostCapability capability =
        new HostCapability("Reflink COW", HostCapabilityStatus.PASS, "supported");

    cache.put(first, Map.of(StorageStrategy.REFLINK, capability));
    assertEquals(capability, cache.get(first).get(StorageStrategy.REFLINK));

    assertTrue(cache.get(replacement).isEmpty());
    assertEquals(0, cache.size());
  }

  @Test
  void boundsEntriesAndCachesOnlyPasses() {
    StorageProbeCache cache = new StorageProbeCache(1);
    HostCapability pass = new HostCapability("Reflink COW", HostCapabilityStatus.PASS, "supported");
    HostCapability warning =
        new HostCapability("OverlayFS COW", HostCapabilityStatus.WARNING, "unavailable");
    Map<StorageStrategy, HostCapability> successful =
        StorageProbeCache.successfulCapabilities(
            Map.of(
                StorageStrategy.REFLINK, pass,
                StorageStrategy.OVERLAY, warning));
    assertEquals(Map.of(StorageStrategy.REFLINK, pass), successful);

    cache.put(identity("one"), successful);
    cache.put(identity("two"), successful);
    assertEquals(1, cache.size());
    assertTrue(cache.get(identity("one")).isEmpty());
    assertEquals(pass, cache.get(identity("two")).get(StorageStrategy.REFLINK));
  }

  private static StorageProbeCache.Identity identity(String value) {
    return new StorageProbeCache.Identity(
        Path.of(value).toAbsolutePath().normalize(), value, "ext4", value);
  }
}
