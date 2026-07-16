package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;

public record TaskGraphExecutionSnapshot(String state, String currentNodeId, JsonNode completedNodes,
                                         JsonNode toolResults, JsonNode variables, JsonNode checkpoints,
                                         JsonNode evidence, JsonNode result, String resultCode) {
}
