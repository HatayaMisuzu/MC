package com.mccompanion.core.lease;

public record LeaseEpoch(long value) implements Comparable<LeaseEpoch> {
    public static final LeaseEpoch NONE = new LeaseEpoch(0);

    public LeaseEpoch {
        if (value < 0) {
            throw new IllegalArgumentException("lease epoch must be non-negative");
        }
    }

    public LeaseEpoch next() {
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException("lease epoch overflow");
        }
        return new LeaseEpoch(value + 1);
    }

    @Override
    public int compareTo(LeaseEpoch other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(value);
    }
}
