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
        return handleFromObservation(result.observation());
    }

    /** Extracts a durable identity from either an acceptance receipt or a later observation. */
    public static Optional<Handle> handleFromObservation(com.fasterxml.jackson.databind.JsonNode observation) {
        if (observation == null || !observation.isObject()) return Optional.empty();
        String taskId = observation.path("taskId").asText("").trim();
        String executionId = observation.path("executionId").asText("").trim();
        if (taskId.isEmpty() && executionId.isEmpty()) {
            ObjectNode executionHandle = observation.path("executionHandle").isObject()
                    ? (ObjectNode) observation.path("executionHandle") : null;
            if (executionHandle != null) {
                String kind = executionHandle.path("kind").asText("").trim();
                String id = executionHandle.path("id").asText("").trim();
                if ("TASK".equals(kind)) taskId = id;
                else if ("TASK_GRAPH".equals(kind)) executionId = id;
            }
        }
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

    /** The execution states that no longer need durable lifecycle control. */
    public static boolean isTerminalObservation(com.fasterxml.jackson.databind.JsonNode observation) {
        if (observation == null || !observation.isObject()) return false;
        return switch (observation.path("state").asText("").trim().toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETED", "SUCCEEDED", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }

    /** True for the terminal-looking protocol receipt that still represents live durable work. */
    public static boolean isAcceptedReceipt(ToolResult result) {
        return result != null && result.success() && result.terminal()
                && result.observation().path("accepted").asBoolean(false)
                && "ASYNCHRONOUS".equals(result.observation().path("executionMode").asText(""))
                && !result.observation().path("completionVerified").asBoolean(true);
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
