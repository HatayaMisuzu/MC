package com.mccompanion.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Untrusted memory candidate. It is never returned as a verified MemoryFact. */
public record MemorySuggestion(String suggestionId, String companionId, MemoryKind kind, String key,
                               JsonNode value, double confidence, String status, String source,
                               String brainSessionId, Instant expiresAt,
                               Instant createdAt, Instant updatedAt,
                               String reviewedBy, String reviewReason, Instant reviewedAt,
                               String capsuleId, boolean conflictsWithVerified,
                               String conflictingMemoryId) {
}
