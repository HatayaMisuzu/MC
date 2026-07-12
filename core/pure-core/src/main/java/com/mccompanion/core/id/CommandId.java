package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record CommandId(UUID value) implements UuidIdentifier {
    public CommandId {
        Objects.requireNonNull(value, "value");
    }

    public static CommandId random() {
        return new CommandId(UUID.randomUUID());
    }

    public static CommandId parse(String value) {
        return new CommandId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
