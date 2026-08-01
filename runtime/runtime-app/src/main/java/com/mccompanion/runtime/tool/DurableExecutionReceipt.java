package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/** Defines the one public receipt contract for accepted durable executions. */
public final class DurableExecutionReceipt {
    private DurableExecutionReceipt() { }

    public static Optional<ToolResult> fromAccepted(ToolResult accepted) {
        if (!accepted.success() || accepted.terminal() || !accepted.observation().isObject()) {
            return Optional.empty();
        }
        String taskId = accepted.observation().path("taskId").asText("").trim();
        String executionId = accepted.observation().path("executionId").asText("").trim();
        if (!taskId.isEmpty() && !executionId.isEmpty()) {
            ObjectNode uncertain = (ObjectNode) accepted.observation().deepCopy();
            uncertain.put("accepted", true)
                    .put("completionVerified", false)
                    .put("reconciliationRequired", true)
                    .put("message", "Accepted execution exposed conflicting durable handles");
            return Optional.of(new ToolResult(accepted.callId(), accepted.toolName(), false,
                    "DURABLE_EXECUTION_HANDLE_AMBIGUOUS", uncertain, true));
        }
        return handle(accepted).map(handle -> handle.receipt(accepted));
    }

    public static Optional<Handle> handle(ToolResult result) {
        if (!result.success() || result.terminal() || !result.observation().isObject()) {
            return Optional.empty();
        }
        String taskId = result.observation().path("taskId").asText("").trim();
        String executionId = result.observation().path("executionId").asText("").trim();
        if (!taskId.isEmpty() && !executionId.isEmpty()) return Optional.empty();
        if (!taskId.isEmpty()) {
            return Optional.of(new Handle("TASK", "taskId", taskId,
                    "task.inspect", "task.cancel", "task.pause", "task.resume"));
        }
        if (!executionId.isEmpty()) {
            return Optional.of(new Handle("TASK_GRAPH", "executionId", executionId,
                    "task_graph.inspect", "task_graph.cancel", "task_graph.pause", "task_graph.resume"));
        }
        return Optional.empty();
    }

    public record Handle(String kind, String field, String id, String statusTool,
                         String cancelTool, String pauseTool, String resumeTool) {
        private ToolResult receipt(ToolResult accepted) {
            ObjectNode receipt = (ObjectNode) accepted.observation().deepCopy();
            receipt.put("accepted", true)
                    .put("executionMode", "ASYNCHRONOUS")
                    .put("completionVerified", false)
                    .put("statusTool", statusTool)
                    .put("cancelTool", cancelTool)
                    .put("pauseTool", pauseTool)
                    .put("resumeTool", resumeTool)
                    .put("replaySafe", true);
            receipt.putObject("executionHandle")
                    .put("kind", kind)
                    .put("field", field)
                    .put("id", id);
            return new ToolResult(accepted.callId(), accepted.toolName(), accepted.success(),
                    accepted.code(), receipt, true);
        }
    }
}
