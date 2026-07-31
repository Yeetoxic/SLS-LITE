package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import java.util.OptionalInt;

public interface BackendProtocolSynchronizer {

  void synchronize(
      String name,
      RegisteredServer server,
      OptionalInt knownProtocol,
      Optional<String> knownMinecraftVersion);

  void remove(String name);

  static BackendProtocolSynchronizer disabled() {
    return DisabledHolder.INSTANCE;
  }

  final class DisabledHolder {

    private static final BackendProtocolSynchronizer INSTANCE =
        new BackendProtocolSynchronizer() {
          @Override
          public void synchronize(
              String name,
              RegisteredServer server,
              OptionalInt knownProtocol,
              Optional<String> knownMinecraftVersion) {}

          @Override
          public void remove(String name) {}
        };

    private DisabledHolder() {}
  }
}
