package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A bounded snapshot authored by the active external Brain.
 * Runtime validates and stores this data but never derives goals or strategy from it.
 */
public record BrainSemanticState(
        String conversationContext,
        String immediateInstruction,
        String currentTask,
        String longTermGoal,
        String pauseReason,
        boolean userTakeover,
        InitiativeMode initiativeMode,
        PersonalityMode personalityMode,
        PermissionPreset permissionPreset,
        boolean playerExplicitlyAway,
        Instant latestRealWorldObservationAt,
        List<String> staleAssumptions) {
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "conversationContext", "immediateInstruction", "currentTask",
            "longTermGoal", "pauseReason", "userTakeover", "initiativeMode", "personalityMode",
            "permissionPreset", "playerExplicitlyAway", "latestRealWorldObservationAt",
            "staleAssumptions");

    public BrainSemanticState {
        conversationContext = bounded(conversationContext, "conversationContext", 4_096);
        immediateInstruction = bounded(immediateInstruction, "immediateInstruction", 1_024);
        currentTask = bounded(currentTask, "currentTask", 2_048);
        longTermGoal = bounded(longTermGoal, "longTermGoal", 2_048);
        pauseReason = bounded(pauseReason, "pauseReason", 1_024);
        if (initiativeMode == null) throw invalid("initiativeMode is required");
        if (personalityMode == null) throw invalid("personalityMode is required");
        if (permissionPreset == null) throw invalid("permissionPreset is required");
        staleAssumptions = boundedList(staleAssumptions);
    }

    public static BrainSemanticState parse(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid("semanticState must be an object");
        Set<String> unknown = new HashSet<>();
        value.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) unknown.add(field);
        });
        if (!unknown.isEmpty()) throw invalid("unknown semanticState fields: " + unknown);
        if (requiredInt(value, "schemaVersion") != SCHEMA_VERSION) {
            throw invalid("unsupported semanticState schemaVersion");
        }
        JsonNode assumptions = required(value, "staleAssumptions");
        if (!assumptions.isArray()) throw invalid("staleAssumptions must be an array");
        var stale = new java.util.ArrayList<String>();
        assumptions.forEach(item -> {
            if (!item.isTextual()) throw invalid("staleAssumptions must contain strings");
            stale.add(item.asText());
        });
        String observationAt = requiredText(value, "latestRealWorldObservationAt", 64);
        Instant observation = null;
        if (!observationAt.isBlank()) {
            try {
                observation = Instant.parse(observationAt);
            } catch (DateTimeParseException failure) {
                throw invalid("latestRealWorldObservationAt must be ISO-8601 or empty");
            }
            if (observation.isAfter(Instant.now().plusSeconds(300))) {
                throw invalid("latestRealWorldObservationAt cannot be in the future");
            }
        }
        try {
            return new BrainSemanticState(
                    requiredText(value, "conversationContext", 4_096),
                    requiredText(value, "immediateInstruction", 1_024),
                    requiredText(value, "currentTask", 2_048),
                    requiredText(value, "longTermGoal", 2_048),
                    requiredText(value, "pauseReason", 1_024),
                    requiredBoolean(value, "userTakeover"),
                    InitiativeMode.valueOf(requiredText(value, "initiativeMode", 16)),
                    PersonalityMode.valueOf(requiredText(value, "personalityMode", 32)),
                    PermissionPreset.valueOf(requiredText(value, "permissionPreset", 32)),
                    requiredBoolean(value, "playerExplicitlyAway"),
                    observation,
                    stale);
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("BRAIN_INVALID_SEMANTIC_STATE")) {
                throw failure;
            }
            throw invalid("semanticState contains an unsupported enum value");
        }
    }

    public ObjectNode toJson() {
        ObjectNode value = Json.object().put("schemaVersion", SCHEMA_VERSION)
                .put("conversationContext", conversationContext)
                .put("immediateInstruction", immediateInstruction)
                .put("currentTask", currentTask)
                .put("longTermGoal", longTermGoal)
                .put("pauseReason", pauseReason)
                .put("userTakeover", userTakeover)
                .put("initiativeMode", initiativeMode.name())
                .put("personalityMode", personalityMode.name())
                .put("permissionPreset", permissionPreset.name())
                .put("playerExplicitlyAway", playerExplicitlyAway)
                .put("latestRealWorldObservationAt",
                        latestRealWorldObservationAt == null ? "" : latestRealWorldObservationAt.toString());
        ArrayNode assumptions = value.putArray("staleAssumptions");
        staleAssumptions.forEach(assumptions::add);
        return value;
    }

    public enum InitiativeMode { QUIET, NORMAL, ACTIVE }
    public enum PersonalityMode { COMPANION, IMMERSIVE_ROLEPLAY }

    /**
     * This is descriptive state only. ToolGateway permissions remain authoritative.
     */
    public enum PermissionPreset { READ_ONLY, ASK_FOR_EFFECTS, BOUNDED_AUTONOMY }

    private static JsonNode required(JsonNode value, String field) {
        JsonNode result = value.get(field);
        if (result == null || result.isNull()) throw invalid(field + " is required");
        return result;
    }

    private static String requiredText(JsonNode value, String field, int maximum) {
        JsonNode result = required(value, field);
        if (!result.isTextual()) throw invalid(field + " must be a string");
        return bounded(result.asText(), field, maximum);
    }

    private static boolean requiredBoolean(JsonNode value, String field) {
        JsonNode result = required(value, field);
        if (!result.isBoolean()) throw invalid(field + " must be a boolean");
        return result.asBoolean();
    }

    private static int requiredInt(JsonNode value, String field) {
        JsonNode result = required(value, field);
        if (!result.isIntegralNumber()) throw invalid(field + " must be an integer");
        return result.asInt();
    }

    private static String bounded(String value, String field, int maximum) {
        String text = value == null ? "" : value.strip();
        if (text.length() > maximum) throw invalid(field + " exceeds " + maximum + " characters");
        return text;
    }

    private static List<String> boundedList(List<String> values) {
        if (values == null) throw invalid("staleAssumptions is required");
        if (values.size() > 32) throw invalid("staleAssumptions exceeds 32 entries");
        return values.stream().map(value -> bounded(value, "staleAssumptions", 512)).toList();
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("BRAIN_INVALID_SEMANTIC_STATE: " + detail);
    }
}
