package com.mccompanion.runtime.brain;

import java.time.Instant;

public record BrainHealth(String status, String adapter, String detail, Instant checkedAt) {
    public static BrainHealth ready(String adapter) {
        return new BrainHealth("READY", adapter, "", Instant.now());
    }
}
