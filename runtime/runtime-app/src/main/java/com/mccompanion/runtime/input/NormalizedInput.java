package com.mccompanion.runtime.input;

import java.util.Optional;

public record NormalizedInput(String original, String normalized, Optional<Integer> quantity, Optional<Coordinates> coordinates) {
    public record Coordinates(int x, int y, int z) { }
}
