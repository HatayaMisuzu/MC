package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.util.List;

public record TaskGraphValidationResult(boolean valid, String graphId, int nodeCount, int depth,
                                        TaskGraphLimits limits, List<TaskGraphValidationIssue> issues) {
    public TaskGraphValidationResult {
        issues = List.copyOf(issues);
    }

    public ObjectNode toJson() {
        ObjectNode result = Json.object().put("valid", valid).put("graphId", graphId == null ? "" : graphId)
                .put("nodeCount", nodeCount).put("depth", depth);
        result.set("limits", limits.toJson());
        ArrayNode values = result.putArray("issues");
        issues.forEach(issue -> values.addObject().put("path", issue.path()).put("code", issue.code())
                .put("message", issue.message()));
        return result;
    }
}
