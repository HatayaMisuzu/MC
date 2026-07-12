package com.mccompanion.core.action;

import com.mccompanion.core.id.WorldId;

import java.util.Objects;

public record PositionSnapshot(
        WorldId worldId,
        String dimension,
        double x,
        double y,
        double z) {

    public PositionSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank() || dimension.length() > 256) {
            throw new IllegalArgumentException("dimension must be non-blank and at most 256 characters");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("position coordinates must be finite");
        }
    }
}
