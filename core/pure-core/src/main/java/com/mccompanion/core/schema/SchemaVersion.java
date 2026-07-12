package com.mccompanion.core.schema;

public record SchemaVersion(int value) implements Comparable<SchemaVersion> {
    public static final SchemaVersion INITIAL = new SchemaVersion(1);

    public SchemaVersion {
        if (value < 1) {
            throw new IllegalArgumentException("schema version must be positive");
        }
    }

    public static SchemaVersion parse(String value) {
        return new SchemaVersion(Integer.parseInt(value));
    }

    public SchemaVersion next() {
        if (value == Integer.MAX_VALUE) {
            throw new ArithmeticException("schema version overflow");
        }
        return new SchemaVersion(value + 1);
    }

    public boolean canRead(SchemaVersion stored) {
        return stored != null && stored.value <= value;
    }

    @Override
    public int compareTo(SchemaVersion other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
