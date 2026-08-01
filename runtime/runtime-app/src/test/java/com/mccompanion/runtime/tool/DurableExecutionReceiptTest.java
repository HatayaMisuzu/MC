package com.mccompanion.runtime.tool;

import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableExecutionReceiptTest {
    @Test
    void describesOrdinaryTaskRecoveryWithoutClaimingCompletion() {
        ToolResult accepted = new ToolResult("call-1", "movement.follow", true, "COMMAND_DISPATCHED",
                Json.object().put("taskId", "task-1").put("state", "CREATED"), false);

        ToolResult receipt = DurableExecutionReceipt.fromAccepted(accepted).orElseThrow();

        assertTrue(receipt.terminal());
        assertTrue(receipt.observation().path("accepted").asBoolean());
        assertEquals("ASYNCHRONOUS", receipt.observation().path("executionMode").asText());
        assertFalse(receipt.observation().path("completionVerified").asBoolean(true));
        assertEquals("task.inspect", receipt.observation().path("statusTool").asText());
        assertEquals("task.cancel", receipt.observation().path("cancelTool").asText());
        assertEquals("TASK", receipt.observation().path("executionHandle").path("kind").asText());
        assertEquals("taskId", receipt.observation().path("executionHandle").path("field").asText());
    }

    @Test
    void givesTaskGraphsAndSkillsTheSameDurableReceiptContract() {
        ToolResult accepted = new ToolResult("graph-1", "skill.execute", true, "TASK_GRAPH_ACCEPTED",
                Json.object().put("executionId", "graph-1").put("state", "ACCEPTED"), false);

        ToolResult receipt = DurableExecutionReceipt.fromAccepted(accepted).orElseThrow();

        assertEquals("task_graph.inspect", receipt.observation().path("statusTool").asText());
        assertEquals("task_graph.cancel", receipt.observation().path("cancelTool").asText());
        assertEquals("TASK_GRAPH", receipt.observation().path("executionHandle").path("kind").asText());
        assertEquals("executionId", receipt.observation().path("executionHandle").path("field").asText());
    }

    @Test
    void rejectsAmbiguousDurableHandlesInsteadOfGuessingAuthority() {
        ToolResult accepted = new ToolResult("call-1", "future.execute", true, "ACCEPTED",
                Json.object().put("taskId", "task-1").put("executionId", "graph-1"), false);

        ToolResult uncertain = DurableExecutionReceipt.fromAccepted(accepted).orElseThrow();

        assertEquals("DURABLE_EXECUTION_HANDLE_AMBIGUOUS", uncertain.code());
        assertFalse(uncertain.success());
        assertTrue(uncertain.observation().path("reconciliationRequired").asBoolean());
        assertFalse(uncertain.observation().path("completionVerified").asBoolean(true));
    }
}
