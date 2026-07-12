package com.mccompanion.core.behavior;

public record BehaviorRevision(long value) implements Comparable<BehaviorRevision> {
    public static final BehaviorRevision INITIAL = new BehaviorRevision(0);

    public BehaviorRevision {
        if (value < 0) {
            throw new IllegalArgumentException("behavior revision must be non-negative");
        }
    }

    public BehaviorRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException("behavior revision overflow");
        }
        return new BehaviorRevision(value + 1);
    }

    @Override
    public int compareTo(BehaviorRevision other) {
        return Long.compare(value, other.value);
    }
}
