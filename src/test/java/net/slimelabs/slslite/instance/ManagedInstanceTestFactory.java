package net.slimelabs.slslite.instance;

import java.nio.file.Path;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceState;

public final class ManagedInstanceTestFactory {

  private ManagedInstanceTestFactory() {}

  public static ManagedInstance preparing(
      String instanceId, Blueprint blueprint, int port, Path directory) {
    InstanceLifecycle lifecycle = new InstanceLifecycle(instanceId);
    lifecycle.transitionTo(InstanceState.PREPARING);
    return new ManagedInstance(instanceId, blueprint, port, directory, lifecycle);
  }

  public static ManagedInstance ready(
      String instanceId, Blueprint blueprint, int port, Path directory) {
    InstanceLifecycle lifecycle = new InstanceLifecycle(instanceId);
    lifecycle.transitionTo(InstanceState.PREPARING);
    lifecycle.transitionTo(InstanceState.STARTING);
    lifecycle.transitionTo(InstanceState.READY);
    return new ManagedInstance(instanceId, blueprint, port, directory, lifecycle);
  }

  public static void appendLog(ManagedInstance instance, String line) {
    instance.appendLog(line);
  }
}
