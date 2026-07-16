package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record TaskGraphExecutionRecord(String executionId, String controllerId, String brainSessionId,
                                       String companionId, String graphId, String graphVersion, String graphHash,
                                       JsonNode graph, String state, String currentNodeId, JsonNode completedNodes,
                                       JsonNode toolResults, JsonNode variables, JsonNode checkpoints,
                                       JsonNode waitingQuestion, JsonNode permissions, JsonNode limits,
                                       JsonNode provenance, long revision, String resultCode,
                                       Instant createdAt, Instant updatedAt) {
}
