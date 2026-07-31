package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StorageStrategyTest {

  @Test
  void parsesEveryDocumentedValue() {
    for (StorageStrategy strategy : StorageStrategy.values()) {
      assertEquals(strategy, StorageStrategy.parse(strategy.configValue()));
    }
  }

  @Test
  void rejectsUnknownValue() {
    assertThrows(IllegalArgumentException.class, () -> StorageStrategy.parse("hardlink"));
  }

  @Test
  void namesPortableCopyDistinctlyFromConfigurationValue() {
    assertEquals("copy", StorageStrategy.COPY.configValue());
    assertEquals("portable-copy", StorageStrategy.COPY.selectedName());
  }
}
