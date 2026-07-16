package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.util.ArrayList;
import java.util.List;

/** Bounded execution limits. A graph may lower defaults, but never exceed hard product limits. */
public record TaskGraphLimits(int maxNodes, int maxDepth, int maxLoopIterations, int maxRetriesPerNode,
                              int maxParallelNodes, int maxToolCalls, int maxWallTimeSeconds,
                              int maxSerializedStateBytes, int maxEvidenceEntries) {
    public static final TaskGraphLimits DEFAULTS =
            new TaskGraphLimits(256, 16, 64, 5, 4, 128, 1_800, 2 * 1024 * 1024, 1_024);
    public static final TaskGraphLimits HARD_LIMITS = DEFAULTS;

    public static TaskGraphLimits parse(JsonNode value, List<TaskGraphValidationIssue> issues) {
        if (value == null || value.isMissingNode() || value.isNull()) return DEFAULTS;
        if (!value.isObject()) {
            issues.add(new TaskGraphValidationIssue("$.limits", "INVALID_TYPE", "limits must be an object"));
            return DEFAULTS;
        }
        List<String> allowed = List.of("maxNodes", "maxDepth", "maxLoopIterations", "maxRetriesPerNode",
                "maxParallelNodes", "maxToolCalls", "maxWallTimeSeconds", "maxSerializedStateBytes",
                "maxEvidenceEntries");
        value.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                issues.add(new TaskGraphValidationIssue("$.limits." + name, "UNKNOWN_FIELD",
                        "unknown limit field"));
            }
        });
        return new TaskGraphLimits(
                bounded(value, "maxNodes", DEFAULTS.maxNodes, HARD_LIMITS.maxNodes, issues),
                bounded(value, "maxDepth", DEFAULTS.maxDepth, HARD_LIMITS.maxDepth, issues),
                bounded(value, "maxLoopIterations", DEFAULTS.maxLoopIterations,
                        HARD_LIMITS.maxLoopIterations, issues),
                bounded(value, "maxRetriesPerNode", DEFAULTS.maxRetriesPerNode,
                        HARD_LIMITS.maxRetriesPerNode, issues),
                bounded(value, "maxParallelNodes", DEFAULTS.maxParallelNodes,
                        HARD_LIMITS.maxParallelNodes, issues),
                bounded(value, "maxToolCalls", DEFAULTS.maxToolCalls, HARD_LIMITS.maxToolCalls, issues),
                bounded(value, "maxWallTimeSeconds", DEFAULTS.maxWallTimeSeconds,
                        HARD_LIMITS.maxWallTimeSeconds, issues),
                bounded(value, "maxSerializedStateBytes", DEFAULTS.maxSerializedStateBytes,
                        HARD_LIMITS.maxSerializedStateBytes, issues),
                bounded(value, "maxEvidenceEntries", DEFAULTS.maxEvidenceEntries,
                        HARD_LIMITS.maxEvidenceEntries, issues));
    }

    private static int bounded(JsonNode value, String name, int fallback, int maximum,
                               List<TaskGraphValidationIssue> issues) {
        if (!value.has(name)) return fallback;
        JsonNode field = value.path(name);
        if (!field.isIntegralNumber() || !field.canConvertToInt()) {
            issues.add(new TaskGraphValidationIssue("$.limits." + name, "INVALID_TYPE",
                    name + " must be an integer"));
            return fallback;
        }
        int result = field.asInt();
        if (result < 1 || result > maximum) {
            issues.add(new TaskGraphValidationIssue("$.limits." + name, "LIMIT_OUT_OF_RANGE",
                    name + " must be 1.." + maximum));
            return fallback;
        }
        return result;
    }

    public ObjectNode toJson() {
        return Json.object().put("maxNodes", maxNodes).put("maxDepth", maxDepth)
                .put("maxLoopIterations", maxLoopIterations).put("maxRetriesPerNode", maxRetriesPerNode)
                .put("maxParallelNodes", maxParallelNodes).put("maxToolCalls", maxToolCalls)
                .put("maxWallTimeSeconds", maxWallTimeSeconds)
                .put("maxSerializedStateBytes", maxSerializedStateBytes)
                .put("maxEvidenceEntries", maxEvidenceEntries);
    }
}
