package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record SessionId(UUID value) implements UuidIdentifier {
    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionId random() {
        return new SessionId(UUID.randomUUID());
    }

    public static SessionId parse(String value) {
        return new SessionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
