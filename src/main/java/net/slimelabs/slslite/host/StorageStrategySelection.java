package net.slimelabs.slslite.host;

import java.util.Optional;
import net.slimelabs.slslite.config.StorageStrategy;

record StorageStrategySelection(
    StorageStrategy requested, Optional<StorageStrategy> selected, String detail) {

  StorageStrategySelection {
    if (requested == null || selected == null || detail == null || detail.isBlank()) {
      throw new IllegalArgumentException("Storage strategy selection fields are required");
    }
  }

  boolean available() {
    return selected.isPresent();
  }
}
