package net.slimelabs.slslite.host;

import net.slimelabs.slslite.config.StorageStrategy;

import java.util.Optional;

record StorageStrategySelection(
        StorageStrategy requested,
        Optional<StorageStrategy> selected,
        String detail
) {

    StorageStrategySelection {
        if (requested == null || selected == null || detail == null
                || detail.isBlank()) {
            throw new IllegalArgumentException(
                    "Storage strategy selection fields are required"
            );
        }
    }

    boolean available() {
        return selected.isPresent();
    }
}
