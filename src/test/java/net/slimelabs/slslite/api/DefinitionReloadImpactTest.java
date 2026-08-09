package net.slimelabs.slslite.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DefinitionReloadImpactTest {

  @Test
  void validatesBoundedPublicGuidance() {
    DefinitionReloadImpact impact =
        new DefinitionReloadImpact(2, 1, 1, " Review restart or reset. ");

    assertEquals("Review restart or reset.", impact.nextAction());
    assertThrows(
        IllegalArgumentException.class, () -> new DefinitionReloadImpact(0, 0, 0, "bad\nline"));
    assertThrows(
        IllegalArgumentException.class, () -> new DefinitionReloadImpact(0, 0, 0, "x".repeat(513)));
  }
}
