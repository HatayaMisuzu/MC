package com.mccompanion.runtime.workspace;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record SkillVersion(String requestId, String profileId, String companionId, String skillId,
                           long version, String format, String document, String sha256,
                           JsonNode permissions, JsonNode provenance, JsonNode validation,
                           String status, String statusReason, String controllerId,
                           String brainSessionId, String approvedBy, Instant approvedAt,
                           Instant createdAt, Instant updatedAt) {
    public SkillVersion {
        permissions = permissions.deepCopy();
        provenance = provenance.deepCopy();
        validation = validation.deepCopy();
    }
}
