package com.mccompanion.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record MemoryHistory(String historyId, String memoryId, String companionId, MemoryKind kind,
                            String key, JsonNode value, boolean verified, double confidence,
                            String source, Instant expiresAt, String changeKind,
                            String changedBy, Instant changedAt) { }
