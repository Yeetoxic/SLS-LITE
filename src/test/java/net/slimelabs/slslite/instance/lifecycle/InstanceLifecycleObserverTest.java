package net.slimelabs.slslite.instance.lifecycle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.slimelabs.slslite.instance.model.InstanceState;
import org.junit.jupiter.api.Test;

class InstanceLifecycleObserverTest {

  @Test
  void reportsAcceptedTransitionsInOrder() {
    List<InstanceLifecycle.Transition> transitions = new ArrayList<>();
    InstanceLifecycle lifecycle = new InstanceLifecycle("arena.123", transitions::add);

    lifecycle.transitionTo(InstanceState.PREPARING);
    lifecycle.transitionTo(InstanceState.STARTING);
    lifecycle.transitionTo(InstanceState.READY);

    assertEquals(
        List.of(InstanceState.PREPARING, InstanceState.STARTING, InstanceState.READY),
        transitions.stream().map(InstanceLifecycle.Transition::current).toList());
    assertEquals(InstanceState.CREATED, transitions.getFirst().previous());
    assertEquals("arena.123", transitions.getFirst().instanceId());
  }

  @Test
  void observerFailureCannotRollBackLifecycle() {
    InstanceLifecycle lifecycle =
        new InstanceLifecycle(
            "arena.456",
            ignored -> {
              throw new IllegalStateException("extension failed");
            });

    assertDoesNotThrow(() -> lifecycle.transitionTo(InstanceState.PREPARING));
    assertEquals(InstanceState.PREPARING, lifecycle.state());
  }
}
