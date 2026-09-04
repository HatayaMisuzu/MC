package com.mccompanion.core.body.daily.navigation;

/** Immutable version-neutral position/vector used by the daily navigation controller. */
public record Vec(double x, double y, double z) {
    public Vec subtract(Vec other) {
        return new Vec(x - other.x, y - other.y, z - other.z);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double horizontalLengthSquared() {
        return x * x + z * z;
    }

    public double distanceSquared(Vec other) {
        return subtract(other).lengthSquared();
    }

    public Vec normalizedHorizontal() {
        double length = Math.sqrt(horizontalLengthSquared());
        return length < 1.0e-9 ? new Vec(0.0, 0.0, 0.0) : new Vec(x / length, y, z / length);
    }
}
