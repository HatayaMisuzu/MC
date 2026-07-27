package com.mccompanion.runtime.memory;

import java.time.Instant;

public record MemorySettings(String companionId, boolean autoSaveEnabled, long revision,
                             String updatedBy, Instant updatedAt) { }
