package net.slimelabs.slslite.velocity;

import java.net.InetSocketAddress;
import java.util.List;

public interface BackendRegistry extends AutoCloseable {

  void register(String name, InetSocketAddress address);

  default void register(String name, InetSocketAddress address, int protocol) {
    register(name, address);
  }

  default void register(String name, InetSocketAddress address, String minecraftVersion) {
    register(name, address);
  }

  void unregister(String name);

  default ReconciliationReport reconcile() {
    return new ReconciliationReport(0, 0, List.of());
  }

  @Override
  default void close() {}

  record ReconciliationReport(int healthy, int restored, List<String> conflicts) {
    public ReconciliationReport {
      if (healthy < 0 || restored < 0) {
        throw new IllegalArgumentException("registration counts must not be negative");
      }
      conflicts = List.copyOf(conflicts);
    }
  }
}
