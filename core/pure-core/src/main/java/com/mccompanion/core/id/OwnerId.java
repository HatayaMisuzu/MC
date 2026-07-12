package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record OwnerId(UUID value) implements UuidIdentifier {
    public OwnerId {
        Objects.requireNonNull(value, "value");
    }

    public static OwnerId random() {
        return new OwnerId(UUID.randomUUID());
    }

    public static OwnerId parse(String value) {
        return new OwnerId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
