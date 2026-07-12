package com.mccompanion.core.id;

import java.util.Objects;
import java.util.UUID;

public record TaskId(UUID value) implements UuidIdentifier {
    public TaskId {
        Objects.requireNonNull(value, "value");
    }

    public static TaskId random() {
        return new TaskId(UUID.randomUUID());
    }

    public static TaskId parse(String value) {
        return new TaskId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
