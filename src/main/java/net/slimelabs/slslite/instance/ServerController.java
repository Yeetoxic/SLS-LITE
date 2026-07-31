package net.slimelabs.slslite.instance;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ServerController {

  ManagedInstance start(String blueprintId) throws InstanceOperationException;

  Collection<ManagedInstance> getAll();

  ManagedInstance get(String instanceId) throws InstanceOperationException;

  default Collection<String> persistentInstanceIds() {
    return List.of();
  }

  default Collection<String> persistentInstanceIds(String blueprintId) {
    return List.of();
  }

  default void sendCommand(String instanceId, String command) throws InstanceOperationException {
    throw new InstanceOperationException("Console commands are unavailable for " + instanceId);
  }

  CompletableFuture<Integer> stop(String instanceId) throws InstanceOperationException;

  default CompletableFuture<ManagedInstance> restart(String instanceId)
      throws InstanceOperationException {
    throw new InstanceOperationException("Persistent restart is unavailable for " + instanceId);
  }

  default CompletableFuture<ManagedInstance> reset(String instanceId)
      throws InstanceOperationException {
    throw new InstanceOperationException("Persistent reset is unavailable for " + instanceId);
  }

  void shutdown(Duration timeout);
}
