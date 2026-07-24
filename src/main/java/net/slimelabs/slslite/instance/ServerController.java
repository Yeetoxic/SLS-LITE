package net.slimelabs.slslite.instance;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public interface ServerController {

    ManagedInstance start(String blueprintId) throws InstanceOperationException;

    Collection<ManagedInstance> getAll();

    ManagedInstance get(String instanceId) throws InstanceOperationException;

    default void sendCommand(String instanceId, String command)
            throws InstanceOperationException {
        throw new InstanceOperationException(
                "Console commands are unavailable for " + instanceId
        );
    }

    CompletableFuture<Integer> stop(String instanceId) throws InstanceOperationException;

    void shutdown(Duration timeout);
}
