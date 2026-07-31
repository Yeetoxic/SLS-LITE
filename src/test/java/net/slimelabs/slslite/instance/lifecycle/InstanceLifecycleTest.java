package net.slimelabs.slslite.instance.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.slimelabs.slslite.instance.model.InstanceState;
import org.junit.jupiter.api.Test;

class InstanceLifecycleTest {

  @Test
  void followsSuccessfulLifecycle() {
    InstanceLifecycle lifecycle = new InstanceLifecycle("game.123456");

    lifecycle.transitionTo(InstanceState.PREPARING);
    lifecycle.transitionTo(InstanceState.STARTING);
    lifecycle.transitionTo(InstanceState.READY);
    lifecycle.transitionTo(InstanceState.STOPPING);
    lifecycle.transitionTo(InstanceState.STOPPED);

    assertEquals(InstanceState.STOPPED, lifecycle.state());
  }

  @Test
  void rejectsInvalidTransition() {
    InstanceLifecycle lifecycle = new InstanceLifecycle("game.123456");

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> lifecycle.transitionTo(InstanceState.READY));

    assertTrue(exception.getMessage().contains("CREATED -> READY"));
  }

  @Test
  void definesTerminalStoppedState() {
    assertFalse(InstanceLifecycle.canTransition(InstanceState.STOPPED, InstanceState.STARTING));
  }

  @Test
  void allowsPreparationToBeCancelled() {
    InstanceLifecycle lifecycle = new InstanceLifecycle("game.123456");
    lifecycle.transitionTo(InstanceState.PREPARING);

    lifecycle.transitionTo(InstanceState.STOPPING);
    lifecycle.transitionTo(InstanceState.STOPPED);

    assertEquals(InstanceState.STOPPED, lifecycle.state());
  }
}
