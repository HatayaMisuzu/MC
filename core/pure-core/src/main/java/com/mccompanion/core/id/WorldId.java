package com.mccompanion.core.id;

import java.util.Objects;
import java.util.regex.Pattern;

public record WorldId(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");

    public WorldId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("world id must be a non-blank stable identifier of at most 256 characters");
        }
    }

    public static WorldId parse(String value) {
        return new WorldId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
