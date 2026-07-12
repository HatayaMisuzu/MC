package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record ActionId(UUID value) implements UuidIdentifier {
    public ActionId {
        Objects.requireNonNull(value, "value");
    }

    public static ActionId random() {
        return new ActionId(UUID.randomUUID());
    }

    public static ActionId parse(String value) {
        return new ActionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
