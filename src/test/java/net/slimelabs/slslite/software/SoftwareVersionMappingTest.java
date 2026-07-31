package net.slimelabs.slslite.software;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoftwareVersionMappingTest {

  @Test
  void matchesInclusiveModernSlsVersionRange() {
    SoftwareVersionMapping mapping = new SoftwareVersionMapping("java_21", ">=1.20.5 <=1.21.11");

    assertTrue(mapping.matches("1.20.5"));
    assertTrue(mapping.matches("1.21.11"));
    assertTrue(mapping.matches("1.21.9-pre2"));
    assertFalse(mapping.matches("1.20.4"));
    assertFalse(mapping.matches("1.21.12"));
  }

  @Test
  void treatsMissingVersionSegmentsAsZero() {
    SoftwareVersionMapping mapping = new SoftwareVersionMapping("java_17", "=1.18");

    assertTrue(mapping.matches("1.18.0"));
    assertFalse(mapping.matches("1.18.1"));
  }

  @Test
  void rejectsInvalidConstraints() {
    assertThrows(
        IllegalArgumentException.class, () -> new SoftwareVersionMapping("java_21", ">=release"));
  }
}
