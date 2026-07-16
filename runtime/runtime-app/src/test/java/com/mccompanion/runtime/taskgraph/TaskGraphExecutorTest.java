package com.mccompanion.runtime.taskgraph;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskGraphExecutorTest {
    @Test
    void executesGenericSequenceWithStableToolCallsAndEvidence() {
        FakeGateway tools = new FakeGateway(false);
        TaskGraphExecutor executor = new TaskGraphExecutor(tools);
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"generic","permissions":["READ_WORLD"],
                 "root":{"id":"root","type":"sequence","nodes":[
                   {"id":"start","type":"emit_progress","message":"starting"},
                   {"id":"observe","type":"call_tool","tool":"world.observe","arguments":{}},
                   {"id":"saved","type":"checkpoint","label":"observed"},
                   {"id":"done","type":"return","value":{"result":"verified"}}
                 ]}}
                """);

        TaskGraphExecutionResult result = executor.execute("exec-1",
                new ToolContext("hermes", "brain-1", "companion-1"), graph);

        assertTrue(result.success(), result.toJson().toString());
        assertEquals(1, result.toolCalls());
        assertEquals(List.of("exec-1:observe:1"), tools.callIds);
        assertEquals(20, result.outputs().get("observe").path("health").asInt());
        assertEquals("verified", result.value().path("result").asText());
        assertTrue(result.completedNodes().containsAll(List.of("start", "observe", "saved", "done")));
    }

    @Test
    void retryAndFallbackAreBoundedAndDoNotDuplicateSuccessfulEffects() {
        FakeGateway tools = new FakeGateway(true);
        TaskGraphExecutor executor = new TaskGraphExecutor(tools);
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"recovery","permissions":["READ_WORLD"],
                 "limits":{"maxRetriesPerNode":3,"maxToolCalls":4},
                 "root":{"id":"fallback","type":"fallback","nodes":[
                   {"id":"retry","type":"retry","maxAttempts":2,"backoffMillis":0,
                    "node":{"id":"observe","type":"call_tool","tool":"world.observe"}},
                   {"id":"failed","type":"fail","code":"NO_OBSERVATION","message":"unavailable"}
                 ]}}
                """);

        TaskGraphExecutionResult result = executor.execute("exec-2",
                new ToolContext("hermes", "brain-1", "companion-1"), graph);

        assertTrue(result.success(), result.toJson().toString());
        assertEquals(List.of("exec-2:observe:1", "exec-2:observe:2"), tools.callIds);
        assertEquals(2, result.toolCalls());
    }

    @Test
    void unsupportedValidatedNodeFailsHonestlyWithoutCallingTools() {
        FakeGateway tools = new FakeGateway(false);
        TaskGraphExecutor executor = new TaskGraphExecutor(tools);
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"ask","permissions":[],
                 "root":{"id":"ask","type":"ask_user","prompt":"Continue?"}}
                """);

        TaskGraphExecutionResult result = executor.execute("exec-3",
                new ToolContext("hermes", "brain-1", "companion-1"), graph);

        assertFalse(result.success());
        assertEquals("TASK_GRAPH_INVALID", result.code());
        assertEquals(0, result.toolCalls());
        assertTrue(result.evidence().toString().contains("NODE_NOT_EXECUTABLE"));
    }

    private static final class FakeGateway implements ToolGateway {
        private final boolean failFirst;
        private final List<String> callIds = new ArrayList<>();

        private FakeGateway(boolean failFirst) { this.failFirst = failFirst; }

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("world.observe", "1.0", "observe",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD",
                    Duration.ofSeconds(1), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            callIds.add(call.callId());
            if (failFirst && callIds.size() == 1) {
                return ToolResult.rejected(call, "TEMPORARY_FAILURE", "retry");
            }
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("health", 20), true);
        }
    }
}
