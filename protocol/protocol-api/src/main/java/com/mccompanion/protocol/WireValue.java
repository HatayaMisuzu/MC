package com.mccompanion.protocol;

import java.util.Locale;

final class WireValue {
    private WireValue() {
    }

    static String of(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " cannot be null");
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value: " + value, invalid);
        }
    }
}
