package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record LeaseId(UUID value) implements UuidIdentifier {
    public LeaseId {
        Objects.requireNonNull(value, "value");
    }

    public static LeaseId random() {
        return new LeaseId(UUID.randomUUID());
    }

    public static LeaseId parse(String value) {
        return new LeaseId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
