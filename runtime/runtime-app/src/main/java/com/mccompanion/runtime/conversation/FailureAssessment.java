package com.mccompanion.runtime.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

public record FailureAssessment(FailureCategory category, JsonNode observedFacts,
                                boolean autonomousReplanAllowed, boolean requiresUserChoice,
                                String reason) {
    public FailureAssessment {
        if (category == null) throw new IllegalArgumentException("category is required");
        observedFacts = observedFacts == null ? Json.object() : observedFacts.deepCopy();
        reason = reason == null ? "" : reason.strip();
    }
}
