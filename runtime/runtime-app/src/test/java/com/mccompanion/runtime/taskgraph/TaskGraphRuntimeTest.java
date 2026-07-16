package com.mccompanion.runtime.taskgraph;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TaskGraphRuntimeTest {
    @TempDir Path temporary;

    @Test
    void executesAsynchronouslyResolvesInputsAndPersistsBoundedEvidence() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("async.db"))) {
            database.initialize();
            FakeGateway gateway = new FakeGateway();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall call = new ToolCall("execution-1", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"input-output",
                         "inputs":{"item":{"type":"registry_item","required":true}},
                         "permissions":["READ_WORLD"],"limits":{"maxEvidenceEntries":2},
                         "root":{"id":"root","type":"sequence","nodes":[
                           {"id":"p1","type":"emit_progress","message":"one"},
                           {"id":"p2","type":"emit_progress","message":"two"},
                           {"id":"p3","type":"emit_progress","message":"three"},
                           {"id":"observe","type":"call_tool","tool":"test.observe",
                            "arguments":{"item":"${inputs.item}"}},
                           {"id":"done","type":"return","value":"${outputs.observe.item}"}
                         ]}}
                        """);
                ToolResult accepted = runtime.start(context, call, graph,
                        Json.object().put("item", "testmod:unknown_item"), Json.object().put("provider", "replay"));
                assertFalse(accepted.terminal());
                ToolResult terminal = runtime.await(context, call, Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals("testmod:unknown_item", gateway.arguments.getFirst().path("item").asText());
                assertEquals("testmod:unknown_item",
                        terminal.observation().path("outputs").path("observe").path("item").asText());
                assertTrue(terminal.observation().path("evidence").size() <= 2);
            }
        }
    }

    @Test
    void pausesInspectsAndResumesWithoutRepeatingCompletedToolEffects() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("pause.db"))) {
            database.initialize();
            FakeGateway gateway = new FakeGateway();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall execute = new ToolCall("execution-2", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"pausable","permissions":["READ_WORLD"],
                         "root":{"id":"root","type":"sequence","nodes":[
                           {"id":"observe","type":"call_tool","tool":"test.observe","arguments":{"item":"first"}},
                           {"id":"wait","type":"wait","durationMillis":500},
                           {"id":"done","type":"return","value":"ok"}
                         ]}}
                        """);
                ToolResult accepted = runtime.start(context, execute, graph, Json.object(), Json.object());
                waitForState(runtime, context, "execution-2", "RUNNING");
                ToolResult pause = runtime.pause(context,
                        new ToolCall("pause-1", "task_graph.pause", Json.object()), "execution-2");
                assertTrue(pause.success());
                ToolResult paused = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertEquals("PAUSED", paused.observation().path("state").asText());
                assertEquals(1, gateway.arguments.size());

                ToolResult resumed = runtime.resume(context,
                        new ToolCall("resume-1", "task_graph.resume", Json.object()), "execution-2");
                assertTrue(resumed.success());
                ToolResult completed = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertTrue(completed.success(), completed.observation().toString());
                assertEquals(1, gateway.arguments.size(), "completed Tool effect repeated after resume");
            }
        }
    }

    @Test
    void duplicateStartIsIdempotentAndCrossSessionControlIsRejected() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("identity.db"))) {
            database.initialize();
            FakeGateway gateway = new FakeGateway();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext owner = new ToolContext("hermes", "brain-1", "companion-1");
                ToolContext attacker = new ToolContext("hermes", "brain-2", "companion-1");
                ToolCall call = new ToolCall("execution-3", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"identity","permissions":[],
                         "root":{"id":"wait","type":"wait","durationMillis":300}}
                        """);
                ToolResult first = runtime.start(owner, call, graph, Json.object(), Json.object());
                ToolResult duplicate = runtime.start(owner, call, graph, Json.object(), Json.object());
                assertFalse(first.terminal());
                assertFalse(duplicate.terminal());

                ToolResult rejected = runtime.inspect(attacker,
                        new ToolCall("inspect-other", "task_graph.inspect", Json.object()), "execution-3");
                assertFalse(rejected.success());
                assertEquals("TASK_GRAPH_INSPECTION_FAILED", rejected.code());

                ToolResult reused = runtime.start(owner, call,
                        ((com.fasterxml.jackson.databind.node.ObjectNode) graph.deepCopy()).put("id", "changed"),
                        Json.object(), Json.object());
                assertFalse(reused.success());
                assertEquals("TASK_GRAPH_CALL_ID_REUSED", reused.code());

                ToolResult cancelled = runtime.cancel(owner, "execution-3", "test");
                assertTrue(cancelled.success());
                ToolResult terminal = runtime.await(owner, call, Duration.ofSeconds(2), ignored -> { });
                assertEquals("CANCELLED", terminal.observation().path("state").asText());
            }
        }
    }

    private static void waitForState(TaskGraphRuntime runtime, ToolContext context, String executionId,
                                     String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            ToolResult inspected = runtime.inspect(context,
                    new ToolCall("inspect", "task_graph.inspect", Json.object()), executionId);
            if (inspected.observation().path("state").asText().equals(expected)
                    && inspected.observation().path("currentNodeId").asText().equals("wait")) return;
            Thread.sleep(10);
        }
        fail("execution did not reach " + expected);
    }

    private static final class FakeGateway implements ToolGateway {
        private final List<com.fasterxml.jackson.databind.JsonNode> arguments = new CopyOnWriteArrayList<>();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.observe", "1.0", "test observation",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD",
                    Duration.ofSeconds(1), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            arguments.add(call.arguments());
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("item", call.arguments().path("item").asText()), true);
        }
    }
}
