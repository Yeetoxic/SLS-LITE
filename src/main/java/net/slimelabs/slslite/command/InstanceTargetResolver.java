package net.slimelabs.slslite.command;

import java.util.Optional;
import java.util.function.Predicate;

final class InstanceTargetResolver {

  private InstanceTargetResolver() {}

  static Resolution resolve(
      String requestedId,
      boolean playerSender,
      Optional<String> currentServer,
      Predicate<String> managedServer) {
    if (!"this".equalsIgnoreCase(requestedId)) {
      return new Resolution(requestedId, null);
    }
    if (!playerSender) {
      return new Resolution(null, "Console must specify a server id.");
    }
    String instanceId = currentServer.orElse(null);
    if (instanceId == null || !managedServer.test(instanceId)) {
      return new Resolution(
          null, "Server " + (instanceId == null ? "none" : instanceId) + " is not an SLS server");
    }
    return new Resolution(instanceId, null);
  }

  record Resolution(String instanceId, String error) {}
}
