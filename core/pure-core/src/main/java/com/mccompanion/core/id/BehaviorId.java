package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record BehaviorId(UUID value) implements UuidIdentifier {
    public BehaviorId {
        Objects.requireNonNull(value, "value");
    }

    public static BehaviorId random() {
        return new BehaviorId(UUID.randomUUID());
    }

    public static BehaviorId parse(String value) {
        return new BehaviorId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
