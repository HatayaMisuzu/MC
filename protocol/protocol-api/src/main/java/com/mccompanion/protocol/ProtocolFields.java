package com.mccompanion.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class ProtocolFields {
    static final int MAX_IDENTIFIER_LENGTH = 128;
    static final int MAX_TEXT_LENGTH = 4_096;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    private ProtocolFields() {
    }

    static String identifier(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a non-blank wire identifier of at most "
                    + MAX_IDENTIFIER_LENGTH + " characters");
        }
        return value;
    }

    static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + MAX_TEXT_LENGTH
                    + " characters");
        }
        return value;
    }

    static String nullableText(String value, String field) {
        return value == null ? null : text(value, field);
    }

    static <K, V> Map<K, V> immutableMap(Map<K, V> input, String field) {
        Objects.requireNonNull(input, field);
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, field + " key"),
                Objects.requireNonNull(value, field + " value")));
        return Map.copyOf(copy);
    }
}
