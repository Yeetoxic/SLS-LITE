package net.slimelabs.slslite.security;

import java.util.UUID;

public record Administrator(UUID uniqueId, String lastKnownName) {

    public Administrator {
        if (uniqueId == null) {
            throw new IllegalArgumentException("administrator UUID is required");
        }
        if (lastKnownName == null || lastKnownName.isBlank()) {
            throw new IllegalArgumentException("administrator name is required");
        }
    }
}
