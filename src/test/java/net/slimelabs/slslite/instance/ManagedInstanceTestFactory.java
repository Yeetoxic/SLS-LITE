package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.Blueprint;

import java.nio.file.Path;

public final class ManagedInstanceTestFactory {

    private ManagedInstanceTestFactory() {
    }

    public static ManagedInstance preparing(
            String instanceId,
            Blueprint blueprint,
            int port,
            Path directory
    ) {
        InstanceLifecycle lifecycle = new InstanceLifecycle(instanceId);
        lifecycle.transitionTo(InstanceState.PREPARING);
        return new ManagedInstance(instanceId, blueprint, port, directory, lifecycle);
    }
}
