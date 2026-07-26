package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.time.Instant;

/**
 * Local-user-owned behavior settings. They constrain presentation and initiative only and never
 * modify Tool definitions, permissions, safety policy, budgets, or Memory policy.
 */
public record BrainBehaviorSettings(
        String companionId,
        BrainSemanticState.InitiativeMode initiativeMode,
        BrainSemanticState.PersonalityMode personalityMode,
        long revision,
        String updatedBy,
        Instant updatedAt) {
    public BrainBehaviorSettings {
        companionId = companionId == null ? "" : companionId.strip();
        if (companionId.isBlank() || companionId.length() > 256) {
            throw new IllegalArgumentException("companionId is required and bounded");
        }
        if (initiativeMode == null) throw new IllegalArgumentException("initiativeMode is required");
        if (personalityMode == null) throw new IllegalArgumentException("personalityMode is required");
        if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
        updatedBy = updatedBy == null ? "" : updatedBy.strip();
    }

    public static BrainBehaviorSettings defaults(String companionId) {
        return new BrainBehaviorSettings(companionId, BrainSemanticState.InitiativeMode.NORMAL,
                BrainSemanticState.PersonalityMode.COMPANION, 0, "DEFAULT", null);
    }

    public ObjectNode toJson() {
        return Json.object().put("companionId", companionId)
                .put("initiativeMode", initiativeMode.name())
                .put("personalityMode", personalityMode.name())
                .put("revision", revision)
                .put("updatedBy", updatedBy)
                .put("updatedAt", updatedAt == null ? "" : updatedAt.toString())
                .put("changesToolPermissions", false)
                .put("changesSafetyPolicy", false)
                .put("changesBudgets", false)
                .put("changesMemoryPolicy", false);
    }
}
