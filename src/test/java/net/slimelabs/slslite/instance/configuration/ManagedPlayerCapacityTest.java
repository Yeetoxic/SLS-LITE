package net.slimelabs.slslite.instance.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ManagedPlayerCapacityTest {

  @Test
  void preservesPublicLimitAndProvidesBoundedForceHeadroom() {
    assertEquals(13, ManagedPlayerCapacity.backendLimit(12, 0));
    assertEquals(500, ManagedPlayerCapacity.backendLimit(12, 500));
    assertEquals(601, ManagedPlayerCapacity.backendLimit(600, 500));
    assertEquals(Integer.MAX_VALUE, ManagedPlayerCapacity.backendLimit(Integer.MAX_VALUE, 500));
  }

  @Test
  void rejectsInvalidLimits() {
    assertThrows(IllegalArgumentException.class, () -> ManagedPlayerCapacity.backendLimit(0, 10));
    assertThrows(IllegalArgumentException.class, () -> ManagedPlayerCapacity.backendLimit(10, -1));
  }
}
