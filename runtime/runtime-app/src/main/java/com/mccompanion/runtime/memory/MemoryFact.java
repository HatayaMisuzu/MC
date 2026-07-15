package com.mccompanion.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record MemoryFact(String memoryId, String companionId, MemoryKind kind, String key, JsonNode value,
                         boolean verified, double confidence, Instant expiresAt, Instant createdAt, Instant updatedAt) { }
