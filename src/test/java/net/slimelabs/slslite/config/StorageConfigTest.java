package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StorageConfigTest {

  @Test
  void defaultsPreservePriorityAndConservativeAutomaticParallelism() {
    StorageConfig config = new StorageConfig(StorageStrategy.AUTO);

    assertEquals(StorageConfig.DEFAULT_AUTO_PRIORITY, config.autoPriority());
    assertEquals(StorageConfig.AUTO_COPY_PARALLELISM, config.copyParallelism());
    assertTrue(config.resolvedCopyParallelism() >= 1);
    assertTrue(config.resolvedCopyParallelism() <= 4);
    assertTrue(config.permitsPortableFallback());
  }

  @Test
  void operatorCanExcludePortableFallbackAndChooseParallelism() {
    StorageConfig config =
        new StorageConfig(
            StorageStrategy.AUTO,
            null,
            30,
            List.of(StorageStrategy.OVERLAY, StorageStrategy.REFLINK),
            7);

    assertFalse(config.permitsPortableFallback());
    assertEquals(7, config.resolvedCopyParallelism());
  }

  @Test
  void rejectsStructurallyInvalidAutomaticPolicies() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageConfig(StorageStrategy.AUTO, null, 30, List.of(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StorageConfig(
                StorageStrategy.AUTO,
                null,
                30,
                List.of(StorageStrategy.COPY, StorageStrategy.COPY),
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StorageConfig(
                StorageStrategy.AUTO, null, 30, List.of(StorageStrategy.SNAPSHOT_HOOK), 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StorageConfig(
                StorageStrategy.AUTO,
                null,
                30,
                List.of(StorageStrategy.COPY),
                StorageConfig.MAX_COPY_PARALLELISM + 1));
  }
}
