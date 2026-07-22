package com.mccompanion.runtime.brain;

import java.time.Duration;

/** Aggregate limits for one externally controlled Brain validation/session run. */
public record LiveBrainBudget(int maxRequests, int maxInputTokens, int maxOutputTokens,
                              Duration maxWallClock, int maxRetries) {
    public LiveBrainBudget {
        if (maxRequests < 1 || maxRequests > 1_000) throw new IllegalArgumentException("maxRequests must be 1..1000");
        if (maxInputTokens < 128 || maxInputTokens > 2_000_000) {
            throw new IllegalArgumentException("maxInputTokens must be 128..2000000");
        }
        if (maxOutputTokens < 128 || maxOutputTokens > 500_000) {
            throw new IllegalArgumentException("maxOutputTokens must be 128..500000");
        }
        if (maxWallClock == null || maxWallClock.isNegative() || maxWallClock.isZero()
                || maxWallClock.compareTo(Duration.ofHours(8)) > 0) {
            throw new IllegalArgumentException("maxWallClock must be positive and at most 8 hours");
        }
        if (maxRetries < 0 || maxRetries > 5) throw new IllegalArgumentException("maxRetries must be 0..5");
    }
}
