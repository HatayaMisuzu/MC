package com.mccompanion.runtime.workspace;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record SkillTrialLease(String leaseId, String profileId, String companionId,
                              String controllerId, String brainSessionId, String skillId,
                              String format, String document, String sha256, JsonNode tools,
                              JsonNode permissions, JsonNode limits, String status,
                              int remainingUses, Instant expiresAt, String executionId,
                              JsonNode evidence, String revokedBy, Instant createdAt,
                              Instant updatedAt) {
    public SkillTrialLease {
        tools = tools.deepCopy();
        permissions = permissions.deepCopy();
        limits = limits.deepCopy();
        evidence = evidence.deepCopy();
    }
}
