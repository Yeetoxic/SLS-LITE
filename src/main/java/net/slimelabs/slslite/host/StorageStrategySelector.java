package net.slimelabs.slslite.host;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.slimelabs.slslite.config.StorageStrategy;

final class StorageStrategySelector {

  StorageStrategySelection select(
      StorageStrategy requested, Set<StorageStrategy> detected, Set<StorageStrategy> implemented) {
    return select(
        requested,
        net.slimelabs.slslite.config.StorageConfig.DEFAULT_AUTO_PRIORITY,
        detected,
        implemented);
  }

  StorageStrategySelection select(
      StorageStrategy requested,
      List<StorageStrategy> autoPriority,
      Set<StorageStrategy> detected,
      Set<StorageStrategy> implemented) {
    if (requested == null || autoPriority == null || detected == null || implemented == null) {
      throw new IllegalArgumentException(
          "Requested, automatic priority, detected, and implemented strategies are required");
    }
    if (requested == StorageStrategy.AUTO) {
      Optional<StorageStrategy> selected =
          autoPriority.stream()
              .filter(detected::contains)
              .filter(implemented::contains)
              .findFirst();
      return new StorageStrategySelection(
          requested,
          selected,
          selected.isPresent()
              ? "automatic selection chose the first safe implemented strategy in the configured order"
              : "none of the strategies in storage.auto_priority are available for this storage location");
    }
    if (!implemented.contains(requested)) {
      return unavailable(requested, "the strategy is not implemented in this build");
    }
    if (!detected.contains(requested)) {
      return unavailable(
          requested, "the required capability was not detected for this storage " + "location");
    }
    return new StorageStrategySelection(
        requested,
        Optional.of(requested),
        "the explicitly requested strategy is implemented and available "
            + "for this storage location");
  }

  private static StorageStrategySelection unavailable(StorageStrategy requested, String detail) {
    return new StorageStrategySelection(requested, Optional.empty(), detail);
  }
}
