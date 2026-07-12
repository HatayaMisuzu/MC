package com.mccompanion.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record CompanionRecord(
        String companionId,
        String sessionId,
        String worldId,
        String ownerId,
        String displayName,
        JsonNode status,
        Instant lastSeen
) {
}
