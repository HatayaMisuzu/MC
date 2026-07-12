package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record CompanionId(UUID value) implements UuidIdentifier {
    public CompanionId {
        Objects.requireNonNull(value, "value");
    }

    public static CompanionId random() {
        return new CompanionId(UUID.randomUUID());
    }

    public static CompanionId parse(String value) {
        return new CompanionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
