package com.mccompanion.runtime.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.agent.PlanStep;
import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.json.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class StructuredDecisionCodec {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "kind", "understoodGoal", "constraints", "assumptions", "steps", "reply", "reason");
    private static final Set<String> STEP_FIELDS = Set.of(
            "goalState", "capability", "parameters", "expectedResult", "completionCriteria",
            "failurePolicy", "opportunistic", "risk");

    AgentDecision decode(String content) throws ProviderException {
        JsonNode root = parseObject(content, "decision");
        rejectUnknown(root, ROOT_FIELDS, "decision");
        DecisionKind kind = enumValue(DecisionKind.class, root.path("kind").asText(), "kind");
        List<PlanStep> steps = new ArrayList<>();
        JsonNode stepValues = root.path("steps");
        if (!stepValues.isMissingNode() && !stepValues.isArray()) invalid("steps must be an array");
        if (stepValues.isArray()) {
            if (stepValues.size() > 8) invalid("steps exceeds the absolute limit");
            for (JsonNode step : stepValues) {
                if (!step.isObject()) invalid("each step must be an object");
                rejectUnknown(step, STEP_FIELDS, "step");
                JsonNode parameters = step.path("parameters");
                JsonNode completion = step.path("completionCriteria");
                if (!parameters.isObject() || !completion.isObject()) invalid("step parameters and completionCriteria must be objects");
                steps.add(new PlanStep(required(step, "goalState"), required(step, "capability"), parameters,
                        required(step, "expectedResult"), completion, required(step, "failurePolicy"),
                        step.path("opportunistic").asBoolean(false),
                        enumValue(RiskLevel.class, step.path("risk").asText("LOW"), "risk")));
            }
        }
        return new AgentDecision(kind, root.path("understoodGoal").asText(""), strings(root.path("constraints")),
                strings(root.path("assumptions")), steps, root.path("reply").asText(""), root.path("reason").asText(""));
    }

    private static JsonNode parseObject(String content, String label) throws ProviderException {
        try {
            JsonNode value = Json.parse(content);
            if (!value.isObject()) invalid(label + " must be a JSON object");
            return value;
        } catch (IllegalArgumentException malformed) {
            throw new ProviderException("PROVIDER_INVALID_OUTPUT", "Provider " + label + " was not valid JSON", malformed);
        }
    }

    private static void rejectUnknown(JsonNode value, Set<String> allowed, String label) throws ProviderException {
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) invalid(label + " contained an unknown field");
    }

    private static List<String> strings(JsonNode value) throws ProviderException {
        if (value.isMissingNode()) return List.of();
        if (!value.isArray() || value.size() > 32) invalid("string list was invalid");
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 300) invalid("string list item was invalid");
            if (unique.add(item.asText())) result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static String required(JsonNode value, String field) throws ProviderException {
        String result = value.path(field).asText("").strip();
        if (result.isEmpty() || result.length() > 500) invalid(field + " is required or too long");
        return result;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) throws ProviderException {
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new ProviderException("PROVIDER_INVALID_OUTPUT", field + " was invalid"); }
    }

    private static void invalid(String message) throws ProviderException { throw new ProviderException("PROVIDER_INVALID_OUTPUT", message); }
}
