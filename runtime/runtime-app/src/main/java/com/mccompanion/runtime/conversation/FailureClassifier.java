package com.mccompanion.runtime.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.agent.PlanStep;
import com.mccompanion.runtime.json.Json;

import java.util.Locale;

/** Deterministic trust boundary applied before a model may broaden or retry a failed task. */
public final class FailureClassifier {
    public FailureAssessment classify(String failureCode, String originalRequest,
                                      JsonNode observation, PlanStep failedStep) {
        JsonNode facts = observation == null ? Json.object() : observation;
        String code = effectiveCode(failureCode, facts);
        String request = originalRequest == null ? "" : originalRequest;
        if (code.contains("INSUFFICIENT") || code.contains("RESOURCE_SHORTAGE")) {
            boolean tellFirst = explicitlyRequiresConsultation(request);
            boolean authorized = explicitlyAllowsAutomaticCompletion(request);
            boolean requiresChoice = tellFirst || !authorized;
            return new FailureAssessment(FailureCategory.RESOURCE_SHORTAGE, facts, authorized, requiresChoice,
                    tellFirst ? "USER_REQUIRED_SHORTAGE_REPORT" : authorized
                            ? "USER_AUTHORIZED_AUTOMATIC_COMPLETION" : "SHORTAGE_POLICY_UNSPECIFIED");
        }
        if (code.contains("CAPABILITY_UNAVAILABLE") || code.contains("UNSUPPORTED")) return value(FailureCategory.UNSUPPORTED_CAPABILITY, facts, false, false, code);
        if (code.contains("PROVIDER") || code.contains("RUNTIME_OFFLINE") || code.contains("SERVICE_UNAVAILABLE")) return value(FailureCategory.EXTERNAL_SERVICE_UNAVAILABLE, facts, false, false, code);
        if (code.contains("NO_PROGRESS") || code.contains("LOOP") || code.contains("BUDGET_EXHAUSTED")) return value(FailureCategory.NO_PROGRESS, facts, false, true, code);
        if (code.contains("AUTH") || code.contains("PERMISSION") || code.contains("PROTECTED")) return value(FailureCategory.AUTHORIZATION_REQUIRED, facts, false, true, code);
        if (code.contains("TARGET_MISSING") || code.contains("INVALID_TARGET") || code.contains("AMBIGUOUS")) return value(FailureCategory.INFORMATION_MISSING, facts, false, true, code);
        if (code.contains("TOOL_MISSING") || code.contains("FUEL_MISSING") || code.contains("INVENTORY_FULL") || code.contains("WORKBENCH_MISSING") || code.contains("FOOD_MISSING")) return value(FailureCategory.PREREQUISITE_MISSING, facts, true, false, code);
        if (code.contains("WORLD_CHANGED") || code.contains("OWNER_OFFLINE") || code.contains("DANGER") || code.contains("LAVA") || code.contains("DROWNING") || code.contains("LOW_HEALTH")) return value(FailureCategory.SAFETY_BLOCKED, facts, false, false, code);
        if (code.contains("STUCK") || code.contains("UNREACHABLE") || code.contains("PATH")) return value(FailureCategory.ALTERNATIVE_STRATEGY_AVAILABLE, facts, true, false, code);
        return value(FailureCategory.USER_CHOICE_REQUIRED, facts, false, true, code);
    }

    private static String effectiveCode(String direct, JsonNode facts) {
        String nested = facts.path("snapshot").path("failureCode").asText("");
        String observed = facts.path("code").asText("");
        String value = direct == null ? "" : direct;
        if (isGeneric(value) && !nested.isBlank()) value = nested;
        if (isGeneric(value) && !observed.isBlank()) value = observed;
        return value.isBlank() ? "ACTION_BLOCKED" : value.toUpperCase(Locale.ROOT);
    }

    private static boolean isGeneric(String code) {
        return code == null || code.isBlank() || code.equalsIgnoreCase("BLOCKED")
                || code.equalsIgnoreCase("FAILED") || code.equalsIgnoreCase("ACTION_BLOCKED");
    }

    private static FailureAssessment value(FailureCategory category, JsonNode facts,
                                           boolean autonomous, boolean choice, String reason) {
        return new FailureAssessment(category, facts, autonomous, choice, reason);
    }

    private static boolean explicitlyRequiresConsultation(String text) {
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("不够就告诉我") || compact.contains("不够就说")
                || compact.contains("不足就告诉我") || compact.contains("不够不要去挖")
                || compact.toLowerCase(Locale.ROOT).contains("tellmeifnotenough");
    }

    private static boolean explicitlyAllowsAutomaticCompletion(String text) {
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("不够就去找") || compact.contains("不够就去挖")
                || compact.contains("不足就补齐") || compact.contains("不够就补齐");
    }
}
