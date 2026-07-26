package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.util.Set;

/** Structured external-Brain completion claim linked to a verified observation or an explicit gap. */
public record BrainCompletionClaim(
        String claim,
        Certainty certainty,
        String observationCallId,
        String taskId,
        String explanation) {
    private static final Set<String> FIELDS = Set.of(
            "claim", "certainty", "observationCallId", "taskId", "explanation");

    public BrainCompletionClaim {
        claim = bounded(claim, "claim", 2_048);
        if (claim.isBlank()) throw invalid("claim is required");
        if (certainty == null) throw invalid("certainty is required");
        observationCallId = bounded(observationCallId, "observationCallId", 256);
        taskId = bounded(taskId, "taskId", 256);
        explanation = bounded(explanation, "explanation", 1_024);
        if (certainty == Certainty.VERIFIED && observationCallId.isBlank()) {
            throw invalid("VERIFIED requires observationCallId");
        }
        if (certainty == Certainty.UNVERIFIED && explanation.isBlank()) {
            throw invalid("UNVERIFIED requires explanation");
        }
        if (certainty == Certainty.UNVERIFIED && !observationCallId.isBlank()) {
            throw invalid("UNVERIFIED cannot reference an observation");
        }
        if (certainty == Certainty.NOT_APPLICABLE && (!observationCallId.isBlank() || !taskId.isBlank())) {
            throw invalid("NOT_APPLICABLE cannot reference an observation or task");
        }
    }

    public static BrainCompletionClaim parse(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid("completionClaim must be an object");
        value.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw invalid("unknown completionClaim field: " + field);
        });
        try {
            return new BrainCompletionClaim(requiredText(value, "claim", 2_048),
                    Certainty.valueOf(requiredText(value, "certainty", 32)),
                    optionalText(value, "observationCallId", 256),
                    optionalText(value, "taskId", 256),
                    optionalText(value, "explanation", 1_024));
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("BRAIN_INVALID_COMPLETION_CLAIM")) {
                throw failure;
            }
            throw invalid("unsupported certainty");
        }
    }

    public ObjectNode toJson() {
        return Json.object().put("claim", claim).put("certainty", certainty.name())
                .put("observationCallId", observationCallId).put("taskId", taskId)
                .put("explanation", explanation);
    }

    public enum Certainty { VERIFIED, UNVERIFIED, NOT_APPLICABLE }

    private static String requiredText(JsonNode value, String field, int maximum) {
        JsonNode result = value.get(field);
        if (result == null || !result.isTextual()) throw invalid(field + " is required and must be a string");
        return bounded(result.asText(), field, maximum);
    }

    private static String optionalText(JsonNode value, String field, int maximum) {
        JsonNode result = value.get(field);
        if (result == null || result.isNull()) return "";
        if (!result.isTextual()) throw invalid(field + " must be a string");
        return bounded(result.asText(), field, maximum);
    }

    private static String bounded(String value, String field, int maximum) {
        String text = value == null ? "" : value.strip();
        if (text.length() > maximum) throw invalid(field + " exceeds " + maximum + " characters");
        return text;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("BRAIN_INVALID_COMPLETION_CLAIM: " + detail);
    }
}
