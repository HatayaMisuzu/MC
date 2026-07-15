package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

public record PlanStep(
        String goalState,
        String capability,
        JsonNode parameters,
        String expectedResult,
        JsonNode completionCriteria,
        String failurePolicy,
        boolean opportunistic,
        RiskLevel risk) {
    public PlanStep {
        goalState = required(goalState, "goalState");
        capability = required(capability, "capability");
        parameters = parameters == null ? Json.object() : parameters.deepCopy();
        expectedResult = required(expectedResult, "expectedResult");
        completionCriteria = completionCriteria == null ? Json.object() : completionCriteria.deepCopy();
        failurePolicy = required(failurePolicy, "failurePolicy");
        risk = risk == null ? RiskLevel.LOW : risk;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }
}
