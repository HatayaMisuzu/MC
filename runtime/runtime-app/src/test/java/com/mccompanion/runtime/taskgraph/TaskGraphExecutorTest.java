package com.mccompanion.runtime.taskgraph;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void composesWorldScanCandidateIntoNavigationWithBoundedArrayReferences() {
        CandidateGateway tools = new CandidateGateway();
        TaskGraphExecutor executor = new TaskGraphExecutor(tools);
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"scan-and-navigate",
                 "permissions":["READ_WORLD","MOVE"],
                 "root":{"id":"root","type":"sequence","nodes":[
                   {"id":"scan","type":"call_tool","tool":"world.scan",
                    "arguments":{"block":"examplemod:blue_ore","radius":8}},
                   {"id":"candidate","type":"if",
                    "condition":"${outputs.scan.candidates.length > 0}",
                    "then":{"id":"navigate","type":"call_tool","tool":"movement.navigate",
                      "arguments":{
                        "x":"${outputs.scan.candidates[0].position.x}",
                        "y":"${outputs.scan.candidates[0].position.y}",
                        "z":"${outputs.scan.candidates[0].position.z}",
                        "dimension":"${outputs.scan.candidates[0].dimension}"}},
                    "else":{"id":"missing","type":"fail","code":"NO_CANDIDATE","message":"none"}},
                   {"id":"done","type":"return","value":"${outputs.scan.candidates[0]}"}
                 ]}}
                """);

        TaskGraphExecutionResult result = executor.execute("exec-array",
                new ToolContext("hermes", "brain-1", "companion-1"), graph);

        assertTrue(result.success(), result.toJson().toString());
        assertEquals(2, result.toolCalls());
        assertEquals(7, tools.navigation.path("x").asInt());
        assertEquals(11, tools.navigation.path("y").asInt());
        assertEquals(-4, tools.navigation.path("z").asInt());
        assertEquals("examplemod:moon", tools.navigation.path("dimension").asText());
        assertEquals("examplemod:blue_ore", result.value().path("block").asText());
    }

    @Test
    void sharedParallelExecutorEnforcesGlobalConcurrencyAcrossGraphs() throws Exception {
        ExecutorService shared = Executors.newFixedThreadPool(2);
        ConcurrencyGateway tools = new ConcurrencyGateway();
        try {
            TaskGraphExecutor executor = new TaskGraphExecutor(
                    tools, new TaskGraphValidator(), TaskGraphExecutor.EXECUTABLE_NODE_TYPES, shared);
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"shared-parallel","permissions":["READ_WORLD"],
                     "root":{"id":"parallel","type":"parallel","maxConcurrency":2,"nodes":[
                       {"id":"one","type":"call_tool","tool":"world.observe"},
                       {"id":"two","type":"call_tool","tool":"world.observe"}
                     ]}}
                    """);
            CompletableFuture<TaskGraphExecutionResult> first = CompletableFuture.supplyAsync(() ->
                    executor.execute("parallel-one",
                            new ToolContext("hermes", "brain-1", "companion-1"), graph));
            CompletableFuture<TaskGraphExecutionResult> second = CompletableFuture.supplyAsync(() ->
                    executor.execute("parallel-two",
                            new ToolContext("hermes", "brain-2", "companion-2"), graph));

            assertTrue(tools.twoActive.await(2, TimeUnit.SECONDS), "shared workers did not start");
            assertEquals(2, tools.maximumActive.get());
            tools.release.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).success());
            assertTrue(second.get(2, TimeUnit.SECONDS).success());
            assertEquals(2, tools.maximumActive.get());
        } finally {
            tools.release.countDown();
            shared.shutdownNow();
            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void unsupportedValidatedNodeFailsHonestlyWithoutCallingTools() {
        FakeGateway tools = new FakeGateway(false);
        TaskGraphExecutor executor = new TaskGraphExecutor(tools);
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"suggest","permissions":[],
                 "root":{"id":"suggest","type":"suggest_memory","kind":"PREFERENCE","content":"concise"}}
                """);

        TaskGraphExecutionResult result = executor.execute("exec-3",
                new ToolContext("hermes", "brain-1", "companion-1"), graph);

        assertFalse(result.success());
        assertEquals("TASK_GRAPH_INVALID", result.code());
        assertEquals(0, result.toolCalls());
        assertTrue(result.evidence().toString().contains("NODE_NOT_EXECUTABLE"));
    }

    @Test
    void revalidatesResolvedToolArgumentsBeforeDispatch() {
        SchemaGateway tools = new SchemaGateway();
        TaskGraphExecutor executor = new TaskGraphExecutor(tools);
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"runtime-schema",
                 "inputs":{"x":{"type":"string","required":true}},
                 "permissions":["MOVE"],
                 "root":{"id":"move","type":"call_tool","tool":"movement.navigate",
                  "arguments":{"x":"${inputs.x}","y":64,"z":0}}}
                """);

        TaskGraphExecutionResult result = executor.execute("exec-schema",
                new ToolContext("hermes", "brain-1", "companion-1"), graph,
                Json.object().put("x", "not-an-integer"), null,
                new TaskGraphExecutionControl(), ignored -> { });

        assertFalse(result.success());
        assertEquals("TOOL_ARGUMENT_SCHEMA_INVALID", result.code());
        assertEquals(0, result.toolCalls());
        assertEquals(0, tools.calls.get());
        assertEquals("$.x", result.value().path("path").asText());
    }

    @Test
    void appliesOneEntryAndByteBudgetToEveryEvidenceSource() {
        OversizedEvidenceGateway tools = new OversizedEvidenceGateway();
        TaskGraphExecutor executor = new TaskGraphExecutor(tools);
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"evidence-budget","permissions":["READ_WORLD"],
                 "limits":{"maxEvidenceEntries":8,"maxEvidenceBytes":1024},
                 "root":{"id":"root","type":"sequence","nodes":[
                   {"id":"observe","type":"call_tool","tool":"world.observe"},
                   {"id":"checkpoint","type":"checkpoint","label":"after observation"},
                   {"id":"done","type":"return","value":"ok"}
                 ]}}
                """);

        TaskGraphExecutionResult result = executor.execute("exec-evidence",
                new ToolContext("hermes", "brain-1", "companion-1"), graph);

        assertTrue(result.success(), result.toJson().toString());
        String serialized = Json.write(result.toJson().path("evidence"));
        assertTrue(serialized.getBytes(StandardCharsets.UTF_8).length <= 1024, serialized);
        assertTrue(result.evidence().size() <= 8);
        assertEquals(2, result.evidence().stream()
                .filter(value -> value.path("code").asText().equals("EVIDENCE_ENTRY_OVERSIZED")).count());
        assertFalse(serialized.contains("progress-raw"));
        assertFalse(serialized.contains("terminal-raw"));
        assertTrue(result.evidence().stream().anyMatch(value -> value.path("type").asText().equals("CHECKPOINT")));
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

    private static final class CandidateGateway implements ToolGateway {
        private com.fasterxml.jackson.databind.JsonNode navigation = Json.object();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(
                    new ToolDefinition("world.scan", "1.0", "scan",
                            Json.object().put("type", "object"), "MEDIUM", "READ_WORLD",
                            Duration.ofSeconds(1), true),
                    new ToolDefinition("movement.navigate", "1.0", "navigate",
                            Json.object().put("type", "object"), "LOW", "MOVE",
                            Duration.ofSeconds(1), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            if (call.name().equals("world.scan")) {
                return new ToolResult(call.callId(), call.name(), true, "OK", Json.parse("""
                        {"candidates":[{"block":"examplemod:blue_ore","dimension":"examplemod:moon",
                          "position":{"x":7,"y":11,"z":-4}}]}
                        """), true);
            }
            navigation = call.arguments().deepCopy();
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("arrived", true), true);
        }
    }

    private static final class ConcurrencyGateway implements ToolGateway {
        private final CountDownLatch twoActive = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("world.observe", "1.0", "observe",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD",
                    Duration.ofSeconds(2), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            twoActive.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
                return new ToolResult(call.callId(), call.name(), true, "OK",
                        Json.object().put("observed", true), true);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static final class SchemaGateway implements ToolGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("movement.navigate", "1.0", "navigate",
                    Json.parse("""
                            {"type":"object","additionalProperties":false,
                             "required":["x","y","z"],
                             "properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}}}
                            """), "LOW", "MOVE", Duration.ofSeconds(1), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            calls.incrementAndGet();
            return new ToolResult(call.callId(), call.name(), true, "OK", Json.object(), true);
        }
    }

    private static final class OversizedEvidenceGateway implements ToolGateway {
        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("world.observe", "1.0", "observe",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD",
                    Duration.ofSeconds(1), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            return new ToolResult(call.callId(), call.name(), true, "ACCEPTED", Json.object(), false);
        }

        @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted,
                                                  Duration timeout,
                                                  java.util.function.Consumer<ToolResult> progress) {
            progress.accept(new ToolResult(call.callId(), call.name(), true, "TOOL_PROGRESS",
                    Json.object().put("payload", "progress-raw-" + "p".repeat(2_000)), false));
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("payload", "terminal-raw-" + "t".repeat(2_000)), true);
        }
    }
}
