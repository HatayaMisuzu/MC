package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.util.List;
import java.util.Map;

public record TaskGraphExecutionResult(String executionId, String state, String code, int toolCalls,
                                       List<String> completedNodes, Map<String, JsonNode> outputs,
                                       List<JsonNode> evidence, JsonNode value) {
    public TaskGraphExecutionResult {
        completedNodes = List.copyOf(completedNodes);
        outputs = Map.copyOf(outputs);
        evidence = List.copyOf(evidence);
        value = value == null ? Json.object() : value.deepCopy();
    }

    public boolean success() { return state.equals("SUCCEEDED"); }

    public ObjectNode toJson() {
        ObjectNode result = Json.object().put("executionId", executionId).put("state", state)
                .put("code", code).put("toolCalls", toolCalls);
        ArrayNode completed = result.putArray("completedNodes");
        completedNodes.forEach(completed::add);
        ObjectNode outputJson = result.putObject("outputs");
        outputs.forEach((key, output) -> outputJson.set(key, output));
        ArrayNode evidenceJson = result.putArray("evidence");
        evidence.forEach(evidenceJson::add);
        result.set("value", value);
        return result;
    }
}
