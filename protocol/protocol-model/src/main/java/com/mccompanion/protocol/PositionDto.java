package com.mccompanion.protocol;

public record PositionDto(double x, double y, double z) {
    public PositionDto {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("position coordinates must be finite");
        }
    }
}
