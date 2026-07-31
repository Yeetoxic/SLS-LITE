package net.slimelabs.slslite.host;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.slimelabs.slslite.config.StorageStrategy;

final class StorageStrategySelector {

  private static final List<StorageStrategy> AUTO_PRIORITY =
      List.of(
          StorageStrategy.REFLINK,
          StorageStrategy.BTRFS,
          StorageStrategy.OVERLAY,
          StorageStrategy.FUSE_OVERLAY,
          StorageStrategy.COPY);

  StorageStrategySelection select(
      StorageStrategy requested, Set<StorageStrategy> detected, Set<StorageStrategy> implemented) {
    if (requested == null || detected == null || implemented == null) {
      throw new IllegalArgumentException(
          "Requested, detected, and implemented strategies are required");
    }
    if (requested == StorageStrategy.AUTO) {
      StorageStrategy selected =
          AUTO_PRIORITY.stream()
              .filter(detected::contains)
              .filter(implemented::contains)
              .findFirst()
              .orElse(StorageStrategy.COPY);
      return new StorageStrategySelection(
          requested,
          Optional.of(selected),
          "automatic selection chose the fastest safe implemented "
              + "strategy detected for this storage location");
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
