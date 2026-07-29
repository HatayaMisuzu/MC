package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Structured external-Brain completion claim linked to a verified observation or an explicit gap. */
public record BrainCompletionClaim(
        String claim,
        Certainty certainty,
        String observationCallId,
        String taskId,
        List<EvidenceCondition> conditions,
        String explanation) {
    private static final Set<String> FIELDS = Set.of(
            "claim", "certainty", "observationCallId", "taskId", "conditions", "explanation");

    public BrainCompletionClaim {
        claim = bounded(claim, "claim", 2_048);
        if (claim.isBlank()) throw invalid("claim is required");
        if (certainty == null) throw invalid("certainty is required");
        observationCallId = bounded(observationCallId, "observationCallId", 256);
        taskId = bounded(taskId, "taskId", 256);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > 16) throw invalid("conditions exceeds 16 entries");
        explanation = bounded(explanation, "explanation", 1_024);
        if (certainty == Certainty.VERIFIED && observationCallId.isBlank()) {
            throw invalid("VERIFIED requires observationCallId");
        }
        if (certainty == Certainty.VERIFIED && conditions.isEmpty()) {
            throw invalid("VERIFIED requires at least one structured evidence condition");
        }
        if (certainty == Certainty.UNVERIFIED && explanation.isBlank()) {
            throw invalid("UNVERIFIED requires explanation");
        }
        if (certainty == Certainty.UNVERIFIED && !observationCallId.isBlank()) {
            throw invalid("UNVERIFIED cannot reference an observation");
        }
        if (certainty != Certainty.VERIFIED && !conditions.isEmpty()) {
            throw invalid(certainty + " cannot contain evidence conditions");
        }
        if (certainty == Certainty.NOT_APPLICABLE
                && (!observationCallId.isBlank() || !taskId.isBlank())) {
            throw invalid("NOT_APPLICABLE cannot reference an observation or task");
        }
    }

    public static BrainCompletionClaim parse(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid("completionClaim must be an object");
        value.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw invalid("unknown completionClaim field: " + field);
        });
        try {
            JsonNode conditionValues = value.path("conditions");
            List<EvidenceCondition> conditions = new ArrayList<>();
            if (!conditionValues.isMissingNode() && !conditionValues.isNull()) {
                if (!conditionValues.isArray()) throw invalid("conditions must be an array");
                conditionValues.forEach(condition -> conditions.add(EvidenceCondition.parse(condition)));
            }
            return new BrainCompletionClaim(requiredText(value, "claim", 2_048),
                    Certainty.valueOf(requiredText(value, "certainty", 32)),
                    optionalText(value, "observationCallId", 256),
                    optionalText(value, "taskId", 256),
                    conditions,
                    optionalText(value, "explanation", 1_024));
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("BRAIN_INVALID_COMPLETION_CLAIM")) {
                throw failure;
            }
            throw invalid("unsupported certainty");
        }
    }

    public ObjectNode toJson() {
        ObjectNode value = Json.object().put("claim", claim).put("certainty", certainty.name())
                .put("observationCallId", observationCallId).put("taskId", taskId)
                .put("explanation", explanation);
        var array = value.putArray("conditions");
        conditions.forEach(condition -> array.add(condition.toJson()));
        return value;
    }

    public enum Certainty { VERIFIED, UNVERIFIED, NOT_APPLICABLE }

    /** Deterministic postcondition over the cited ToolResult observation JSON. */
    public record EvidenceCondition(String pointer, Operator operator, JsonNode expected) {
        private static final Set<String> FIELDS = Set.of("pointer", "operator", "expected");

        public EvidenceCondition {
            pointer = bounded(pointer, "condition.pointer", 256);
            if (!pointer.startsWith("/") || pointer.contains("\r") || pointer.contains("\n")) {
                throw invalid("condition.pointer must be a JSON Pointer");
            }
            if (operator == null) throw invalid("condition.operator is required");
            if (expected == null || expected.isNull() || expected.isContainerNode()) {
                throw invalid("condition.expected must be a string, number, or boolean");
            }
            if (expected.isTextual() && expected.textValue().length() > 512) {
                throw invalid("condition.expected exceeds 512 characters");
            }
            expected = expected.deepCopy();
        }

        static EvidenceCondition parse(JsonNode value) {
            if (value == null || !value.isObject()) throw invalid("condition must be an object");
            value.fieldNames().forEachRemaining(field -> {
                if (!FIELDS.contains(field)) throw invalid("unknown condition field: " + field);
            });
            String pointer = requiredText(value, "pointer", 256);
            Operator operator;
            try {
                operator = Operator.valueOf(requiredText(value, "operator", 32));
            } catch (IllegalArgumentException failure) {
                throw invalid("unsupported condition operator");
            }
            JsonNode expected = value.get("expected");
            return new EvidenceCondition(pointer, operator, expected);
        }

        ObjectNode toJson() {
            ObjectNode value = Json.object().put("pointer", pointer).put("operator", operator.name());
            value.set("expected", expected);
            return value;
        }

        boolean matches(JsonNode observation) {
            JsonNode actual = observation.at(pointer);
            if (actual.isMissingNode() || actual.isNull()) return false;
            return switch (operator) {
                case EQUALS -> actual.equals(expected);
                case AT_LEAST -> numeric(actual).compareTo(numeric(expected)) >= 0;
                case AT_MOST -> numeric(actual).compareTo(numeric(expected)) <= 0;
                case CONTAINS -> actual.isTextual() && expected.isTextual()
                        ? actual.textValue().contains(expected.textValue())
                        : actual.isArray() && java.util.stream.StreamSupport.stream(
                                actual.spliterator(), false).anyMatch(expected::equals);
            };
        }

        private static BigDecimal numeric(JsonNode value) {
            if (!value.isNumber()) throw invalid("numeric condition requires numeric evidence");
            return value.decimalValue();
        }
    }

    public enum Operator { EQUALS, AT_LEAST, AT_MOST, CONTAINS }

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
