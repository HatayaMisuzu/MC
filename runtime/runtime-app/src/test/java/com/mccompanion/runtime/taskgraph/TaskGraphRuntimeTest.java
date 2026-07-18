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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
                assertEquals("testmod:unknown_item", terminal.observation().path("value").asText());
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
                           {"id":"wait","type":"wait","durationMillis":2000},
                           {"id":"done","type":"return","value":"ok"}
                         ]}}
                        """);
                ToolResult accepted = runtime.start(context, execute, graph, Json.object(), Json.object());
                waitForState(runtime, context, "execution-2", "WAITING");
                ToolResult pause = runtime.pause(context,
                        new ToolCall("pause-1", "task_graph.pause", Json.object()), "execution-2");
                assertTrue(pause.success());
                ToolResult paused = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertEquals("PAUSED", paused.observation().path("state").asText());
                assertEquals(1, gateway.arguments.size());

                ToolResult resumed = runtime.resume(context,
                        new ToolCall("resume-1", "task_graph.resume", Json.object()), "execution-2");
                assertTrue(resumed.success(), resumed.code() + ": " + resumed.observation());
                ToolResult completed = runtime.await(context, execute, Duration.ofSeconds(4), ignored -> { });
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
                         "root":{"id":"wait","type":"wait","durationMillis":2000}}
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
                waitForState(runtime, owner, "execution-3", "WAITING");

                ToolCall cancelCall = new ToolCall(
                        "external-cancel-request", "task_graph.cancel", Json.object());
                ToolResult cancelled = runtime.cancel(
                        owner, cancelCall, "execution-3", "test");
                assertTrue(cancelled.success());
                assertEquals("external-cancel-request", cancelled.callId());
                ToolResult terminal = runtime.await(owner, call, Duration.ofSeconds(2), ignored -> { });
                assertEquals("CANCELLED", terminal.observation().path("state").asText());
            }
        }
    }

    @Test
    void executesIfAndSwitchFromVerifiedToolObservation() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("condition.db"))) {
            database.initialize();
            FakeGateway gateway = new FakeGateway();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall call = new ToolCall("execution-4", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"conditional",
                         "inputs":{"minimum":{"type":"integer","required":true}},
                         "permissions":["READ_WORLD"],
                         "root":{"id":"root","type":"sequence","nodes":[
                           {"id":"observe","type":"call_tool","tool":"test.observe",
                            "arguments":{"item":"testmod:ore"}},
                           {"id":"enough","type":"if",
                            "condition":"${outputs.observe.count >= inputs.minimum}",
                            "then":{"id":"choose","type":"switch","expression":"${outputs.observe.item}",
                             "cases":[{"equals":"testmod:ore",
                               "node":{"id":"done","type":"return","value":"${outputs.observe.item}"}}],
                             "default":{"id":"wrong","type":"fail","code":"WRONG_ITEM","message":"wrong"}},
                            "else":{"id":"short","type":"fail","code":"SHORT","message":"short"}}
                         ]}}
                        """);
                ToolResult accepted = runtime.start(context, call, graph,
                        Json.object().put("minimum", 1), Json.object());
                ToolResult terminal = runtime.await(context, call, Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertTrue(terminal.observation().path("completedNodes").toString().contains("choose"));
            }
        }
    }

    @Test
    void repeatUsesPriorObservationsAndStableIterationCallIds() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("repeat.db"))) {
            database.initialize();
            FakeGateway gateway = new FakeGateway();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall call = new ToolCall("execution-5", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"repeat-observation",
                         "inputs":{"target":{"type":"integer","required":true}},
                         "permissions":["READ_WORLD"],
                         "root":{"id":"repeat","type":"repeat","maxIterations":5,
                          "until":"${outputs.observe.count >= inputs.target}",
                          "body":{"id":"observe","type":"call_tool","tool":"test.observe",
                           "arguments":{"item":"testmod:ore"}}}}
                        """);
                runtime.start(context, call, graph, Json.object().put("target", 3), Json.object());
                ToolResult terminal = runtime.await(context, call, Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(3, gateway.arguments.size());
                assertEquals(3, terminal.observation().path("outputs").path("observe").path("count").asInt());
                assertEquals(3, terminal.observation().path("variables").path("_mcac")
                        .path("loops").path("repeat").asInt());
            }
        }
    }

    @Test
    void whileFailsExplicitlyWhenItsBoundIsExhausted() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("while.db"))) {
            database.initialize();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(new FakeGateway(),
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall call = new ToolCall("execution-6", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"bounded-while",
                         "inputs":{"keepGoing":{"type":"boolean","required":true}},
                         "permissions":[],
                         "root":{"id":"loop","type":"while",
                          "condition":"${inputs.keepGoing == true}","maxIterations":2,
                          "body":{"id":"tick","type":"emit_progress","message":"tick"}}}
                        """);
                runtime.start(context, call, graph, Json.object().put("keepGoing", true), Json.object());
                ToolResult terminal = runtime.await(context, call, Duration.ofSeconds(2), ignored -> { });
                assertFalse(terminal.success());
                assertEquals("LOOP_EXHAUSTED", terminal.code());
                assertEquals("FAILED", terminal.observation().path("state").asText());
            }
        }
    }

    @Test
    void resumesInsideRepeatWithoutReplayingCompletedIterationTools() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("repeat-resume.db"))) {
            database.initialize();
            FakeGateway gateway = new FakeGateway();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall execute = new ToolCall("execution-7", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"repeat-resume",
                         "inputs":{"target":{"type":"integer","required":true}},
                         "permissions":["READ_WORLD"],
                         "root":{"id":"repeat","type":"repeat","maxIterations":5,
                          "until":"${outputs.observe.count >= inputs.target}",
                          "body":{"id":"iteration","type":"sequence","nodes":[
                           {"id":"observe","type":"call_tool","tool":"test.observe",
                            "arguments":{"item":"testmod:ore"}},
                           {"id":"wait","type":"wait","durationMillis":2000}
                          ]}}}
                        """);
                runtime.start(context, execute, graph, Json.object().put("target", 2), Json.object());
                waitForState(runtime, context, "execution-7", "WAITING");
                ToolResult pause = runtime.pause(context,
                        new ToolCall("pause-repeat", "task_graph.pause", Json.object()), "execution-7");
                assertTrue(pause.success());
                ToolResult paused = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertEquals("PAUSED", paused.observation().path("state").asText());
                assertEquals(1, gateway.arguments.size());

                assertTrue(runtime.resume(context,
                        new ToolCall("resume-repeat", "task_graph.resume", Json.object()), "execution-7").success());
                ToolResult terminal = runtime.await(context, execute, Duration.ofSeconds(5), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(2, gateway.arguments.size(),
                        "resume replayed a Tool already persisted in the interrupted iteration");
            }
        }
    }

    @Test
    void parallelRunsConcurrentlyAndCancellationReachesEveryActiveTool() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("parallel.db"))) {
            database.initialize();
            BlockingParallelGateway gateway = new BlockingParallelGateway();
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall execute = new ToolCall("execution-8", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"parallel-cancel",
                         "permissions":["READ_WORLD"],
                         "root":{"id":"parallel","type":"parallel","maxConcurrency":2,"nodes":[
                          {"id":"left","type":"call_tool","tool":"test.block","arguments":{"side":"left"}},
                          {"id":"right","type":"call_tool","tool":"test.block","arguments":{"side":"right"}}
                         ]}}
                        """);
                runtime.start(context, execute, graph, Json.object(), Json.object());
                assertTrue(gateway.entered.await(2, TimeUnit.SECONDS),
                        "parallel branches did not have two Tools active concurrently");

                ToolResult cancellation = runtime.cancel(context, "execution-8", "test parallel cancel");
                assertTrue(cancellation.success());
                ToolResult terminal = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertEquals("CANCELLED", terminal.observation().path("state").asText());
                assertEquals(Set.of("execution-8:left:1", "execution-8:right:1"), gateway.cancelled);
            }
        }
    }

    @Test
    void unknownToolFailureRequiresReconciliationInsteadOfLeavingRunningState() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("reconcile.db"))) {
            database.initialize();
            ToolGateway throwing = new ToolGateway() {
                @Override public List<ToolDefinition> definitions(ToolContext context) {
                    return List.of(new ToolDefinition("test.throw", "1.0", "throws after dispatch",
                            Json.object().put("type", "object"), "LOW", "READ_WORLD",
                            Duration.ofSeconds(1), true));
                }

                @Override public ToolResult execute(ToolContext context, ToolCall call) {
                    throw new IllegalStateException("transport disconnected after dispatch");
                }
            };
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(throwing,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall execute = new ToolCall("execution-9", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"reconcile",
                         "permissions":["READ_WORLD"],
                         "root":{"id":"unknown","type":"call_tool","tool":"test.throw"}}
                        """);
                runtime.start(context, execute, graph, Json.object(), Json.object());
                ToolResult terminal = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertFalse(terminal.success());
                assertEquals("RECONCILIATION_REQUIRED", terminal.observation().path("state").asText());
                assertEquals("TOOL_RECONCILIATION_REQUIRED", terminal.code());
                assertEquals("execution-9:unknown:1",
                        terminal.observation().path("value").path("callId").asText());
            }
        }
    }

    @Test
    void interruptedIdempotentToolCanResumeFromPersistedBoundaryAfterRestart() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("recovery-resume.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"recovery-resume",
                     "permissions":["READ_WORLD"],
                     "root":{"id":"observe","type":"call_tool","tool":"test.observe",
                      "arguments":{"item":"minecraft:stone"}}}
                    """);
            TaskGraphExecutionRecord created = repository.create("execution-recovery", context, graph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(created.executionId(), created.revision(), "RUNNING", "observe",
                    Json.MAPPER.createArrayNode(), Json.object(), Json.object(), Json.object(),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            assertEquals(1, repository.markUnfinishedForReconciliation());

            FakeGateway gateway = new FakeGateway();
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(gateway, repository)) {
                ToolResult accepted = restarted.resume(context,
                        new ToolCall("resume-recovery", "task_graph.resume", Json.object()),
                        "execution-recovery");
                assertTrue(accepted.success(), accepted.observation().toString());
                assertEquals("RECOVERY_RESUME_ACCEPTED", accepted.code());
                ToolResult terminal = restarted.await(context,
                        new ToolCall("execution-recovery", "task_graph.execute", Json.object()),
                        Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals("execution-recovery", terminal.observation().path("executionId").asText());
                assertEquals(1, gateway.arguments.size());
            }

            var modArgumentsGraph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"recovery-mod-arguments",
                     "permissions":["READ_WORLD"],
                     "root":{"id":"parallel","type":"parallel","maxConcurrency":1,"nodes":[
                       {"id":"observe","type":"call_tool","tool":"test.observe",
                        "arguments":{"payload":{"id":"mod-data","type":"call_tool","tool":"test.move"}}}
                     ]}}
                    """);
            TaskGraphExecutionRecord modCreated = repository.create(
                    "execution-mod-arguments", context, modArgumentsGraph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(modCreated.executionId(), modCreated.revision(), "RUNNING", "observe",
                    Json.MAPPER.createArrayNode(), Json.object(), Json.object(), Json.object(),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();
            FakeGateway modGateway = new FakeGateway();
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(modGateway, repository)) {
                ToolResult accepted = restarted.resume(context,
                        new ToolCall("resume-mod-arguments", "task_graph.resume", Json.object()),
                        "execution-mod-arguments");
                assertTrue(accepted.success(), accepted.observation().toString());
                assertTrue(restarted.await(context,
                        new ToolCall("execution-mod-arguments", "task_graph.execute", Json.object()),
                        Duration.ofSeconds(2), ignored -> { }).success());
                assertEquals("call_tool",
                        modGateway.arguments.getFirst().path("payload").path("type").asText());
            }
        }
    }

    @Test
    void interruptedNonIdempotentOrCorruptGraphRemainsInReconciliation() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("recovery-reject.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"recovery-reject",
                     "permissions":["MOVE"],
                     "root":{"id":"move","type":"call_tool","tool":"test.move"}}
                    """);
            TaskGraphExecutionRecord created = repository.create("execution-non-idempotent", context, graph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(created.executionId(), created.revision(), "RUNNING", "move",
                    Json.MAPPER.createArrayNode(), Json.object(), Json.object(), Json.object(),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();
            AtomicGateway nonIdempotent = new AtomicGateway(false);
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(nonIdempotent, repository)) {
                ToolResult rejected = restarted.resume(context,
                        new ToolCall("resume-non-idempotent", "task_graph.resume", Json.object()),
                        "execution-non-idempotent");
                assertFalse(rejected.success());
                assertEquals("TASK_GRAPH_RECOVERY_UNCONFIRMED_EFFECT", rejected.code());
                assertEquals("RECONCILIATION_REQUIRED",
                        repository.get("execution-non-idempotent").orElseThrow().state());
                assertEquals(0, nonIdempotent.calls);
            }

            TaskGraphExecutionRecord confirmed = repository.create("execution-confirmed", context, graph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            var completed = Json.MAPPER.createArrayNode().add("move");
            var observation = Json.object().put("moved", true);
            var results = Json.object();
            results.set("execution-confirmed:move:1", Json.object()
                    .put("nodeId", "move").put("toolName", "test.move")
                    .put("success", true).put("code", "OK").set("observation", observation));
            repository.save(confirmed.executionId(), confirmed.revision(), "RUNNING", "move",
                    completed, results, Json.object(), Json.object().set("move", observation),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();
            AtomicGateway confirmedGateway = new AtomicGateway(false);
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(confirmedGateway, repository)) {
                ToolResult accepted = restarted.resume(context,
                        new ToolCall("resume-confirmed", "task_graph.resume", Json.object()),
                        "execution-confirmed");
                assertTrue(accepted.success(), accepted.observation().toString());
                ToolResult terminal = restarted.await(context,
                        new ToolCall("execution-confirmed", "task_graph.execute", Json.object()),
                        Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(0, confirmedGateway.calls, "confirmed Tool effect was repeated");
            }

            var safeGraph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"recovery-corrupt",
                     "permissions":["READ_WORLD"],
                     "root":{"id":"observe","type":"call_tool","tool":"test.observe"}}
                    """);
            TaskGraphExecutionRecord safe = repository.create("execution-corrupt", context, safeGraph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(safe.executionId(), safe.revision(), "RUNNING", "observe",
                    Json.MAPPER.createArrayNode(), Json.object(), Json.object(), Json.object(),
                    Json.MAPPER.createArrayNode(), Json.object(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(new FakeGateway(), repository)) {
                ToolResult corrupt = restarted.resume(context,
                        new ToolCall("resume-corrupt", "task_graph.resume", Json.object()),
                        "execution-corrupt");
                assertFalse(corrupt.success());
                assertEquals("TASK_GRAPH_RECOVERY_CORRUPT", corrupt.code());
            }

            TaskGraphExecutionRecord badHash = repository.create("execution-bad-hash", context, safeGraph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(badHash.executionId(), badHash.revision(), "RUNNING", "observe",
                    Json.MAPPER.createArrayNode(), Json.object(), Json.object(), Json.object(),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();
            try (var connection = database.open();
                 var statement = connection.prepareStatement(
                         "UPDATE task_graph_execution SET graph_hash=? WHERE execution_id=?")) {
                statement.setString(1, "0".repeat(64));
                statement.setString(2, "execution-bad-hash");
                assertEquals(1, statement.executeUpdate());
            }
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(new FakeGateway(), repository)) {
                ToolResult corrupt = restarted.resume(context,
                        new ToolCall("resume-bad-hash", "task_graph.resume", Json.object()),
                        "execution-bad-hash");
                assertFalse(corrupt.success());
                assertEquals("TASK_GRAPH_RECOVERY_CORRUPT", corrupt.code());
            }
        }
    }

    @Test
    void recoveryMatchesConfirmedNonIdempotentResultToExactLoopScope() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("loop-scope-recovery.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"loop-scope-recovery",
                     "permissions":["MOVE"],
                     "root":{"id":"loop","type":"repeat","maxIterations":2,
                      "body":{"id":"move","type":"call_tool","tool":"test.move"}}}
                    """);
            var completedFirstIteration = Json.MAPPER.createArrayNode().add("move@loop#0");
            var variables = Json.object();
            variables.set("_mcac", Json.object()
                    .put("currentNodeKey", "move@loop#1")
                    .set("loops", Json.object().put("loop", 1)));
            var firstObservation = Json.object().put("iteration", 0);
            var priorOnlyResults = Json.object();
            priorOnlyResults.set("loop-prior-only:move@loop#0:1", Json.object()
                    .put("nodeId", "move").put("toolName", "test.move")
                    .put("success", true).put("code", "OK").set("observation", firstObservation));
            priorOnlyResults.set("loop-prior-only:move@loop#1:2", Json.object()
                    .put("nodeId", "move").put("toolName", "test.move")
                    .put("success", true).put("code", "OK")
                    .set("observation", Json.object().put("iteration", 1)));

            TaskGraphExecutionRecord priorOnly = repository.create(
                    "loop-prior-only", context, graph, TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(priorOnly.executionId(), priorOnly.revision(), "RUNNING", "move",
                    completedFirstIteration, priorOnlyResults, variables,
                    Json.object().set("move", firstObservation),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();
            AtomicGateway rejectedGateway = new AtomicGateway(false);
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(rejectedGateway, repository)) {
                ToolResult rejected = restarted.resume(context,
                        new ToolCall("resume-loop-prior", "task_graph.resume", Json.object()),
                        "loop-prior-only");
                assertFalse(rejected.success());
                assertEquals("TASK_GRAPH_RECOVERY_UNCONFIRMED_EFFECT", rejected.code());
                assertEquals(0, rejectedGateway.calls,
                        "another loop iteration or attempt must not confirm the current non-idempotent effect");
            }

            var exactObservation = Json.object().put("iteration", 1);
            var exactResults = Json.object();
            exactResults.set("loop-exact:move@loop#0:1", Json.object()
                    .put("nodeId", "move").put("toolName", "test.move")
                    .put("success", true).put("code", "OK").set("observation", firstObservation));
            exactResults.set("loop-exact:move@loop#1:1", Json.object()
                    .put("nodeId", "move").put("toolName", "test.move")
                    .put("success", true).put("code", "OK").set("observation", exactObservation));
            TaskGraphExecutionRecord exact = repository.create(
                    "loop-exact", context, graph, TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(exact.executionId(), exact.revision(), "RUNNING", "move",
                    completedFirstIteration, exactResults, variables,
                    Json.object().set("move", exactObservation),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();
            AtomicGateway exactGateway = new AtomicGateway(false);
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(exactGateway, repository)) {
                ToolResult accepted = restarted.resume(context,
                        new ToolCall("resume-loop-exact", "task_graph.resume", Json.object()), "loop-exact");
                assertTrue(accepted.success(), accepted.observation().toString());
                ToolResult terminal = restarted.await(context,
                        new ToolCall("loop-exact", "task_graph.execute", Json.object()),
                        Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals("SUCCEEDED", terminal.observation().path("state").asText());
                assertEquals(0, exactGateway.calls,
                        "the exact persisted loop-iteration result must be reused without repeating its effect");
            }
        }
    }

    @Test
    void recoveryImportsExactDurableGatewayResultBeforeResumingNonIdempotentTool() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("gateway-reconciliation.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-reconcile", "companion-1");
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"gateway-reconciliation",
                     "inputs":{"distance":{"type":"integer","required":true}},
                     "permissions":["MOVE"],
                     "root":{"id":"move","type":"call_tool","tool":"test.move",
                      "arguments":{"distance":"${inputs.distance}"}}}
                    """);
            TaskGraphExecutionRecord created = repository.create(
                    "gateway-reconcile", context, graph, TaskGraphLimits.DEFAULTS,
                    Json.object().put("distance", 3), Json.object());
            var variables = Json.object().put("distance", 3);
            variables.set("_mcac", Json.object().put("currentNodeKey", "move"));
            repository.save(created.executionId(), created.revision(), "RUNNING", "move",
                    Json.MAPPER.createArrayNode(), Json.object(), variables, Json.object(),
                    Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(),
                    Json.MAPPER.nullNode(), Json.MAPPER.nullNode(), "RUNNING");
            repository.markUnfinishedForReconciliation();

            ReconciliationGateway gateway = new ReconciliationGateway();
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(gateway, repository)) {
                ToolResult accepted = restarted.resume(context,
                        new ToolCall("resume-gateway-result", "task_graph.resume", Json.object()),
                        "gateway-reconcile");
                assertTrue(accepted.success(), accepted.observation().toString());
                ToolResult terminal = restarted.await(context,
                        new ToolCall("gateway-reconcile", "task_graph.execute", Json.object()),
                        Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(0, gateway.executeCalls);
                assertEquals(1, gateway.reconcileCalls);
                assertEquals(3, gateway.reconciledArguments.path("distance").asInt());
                assertTrue(repository.get("gateway-reconcile").orElseThrow().toolResults()
                        .has("gateway-reconcile:move:1"));
            }
        }
    }

    @Test
    void repositoryFailureAfterNonIdempotentEffectRecoversWithoutReplayAfterRuntimeRestart() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("effect-crash-window.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-crash-window", "companion-1");
            ToolCall execute = new ToolCall("effect-crash-window", "task_graph.execute", Json.object());
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"effect-crash-window",
                     "inputs":{"distance":{"type":"integer","required":true}},
                     "permissions":["MOVE"],
                     "root":{"id":"move","type":"call_tool","tool":"test.move",
                      "arguments":{"distance":"${inputs.distance}"}}}
                    """);
            DurableEffectGateway gateway = new DurableEffectGateway();

            try (var connection = database.open(); var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TRIGGER inject_tool_result_write_failure
                        BEFORE UPDATE ON task_graph_execution
                        WHEN NEW.execution_id = 'effect-crash-window'
                         AND NEW.result_code = 'TOOL_RESULT_RECORDED'
                        BEGIN
                          SELECT RAISE(ABORT, 'injected crash-window persistence failure');
                        END
                        """);
            }

            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway, repository)) {
                ToolResult accepted = runtime.start(context, execute, graph,
                        Json.object().put("distance", 4), Json.object());
                assertFalse(accepted.terminal());
                ToolResult quarantined = runtime.await(
                        context, execute, Duration.ofSeconds(2), ignored -> { });
                assertEquals("RECONCILIATION_REQUIRED",
                        quarantined.observation().path("state").asText());
                assertEquals("TASK_GRAPH_WORKER_FAILED", quarantined.code());
                assertEquals(1, gateway.executeCalls);
                assertEquals(0, gateway.reconcileCalls);
                assertTrue(repository.get(execute.callId()).orElseThrow().toolResults().isEmpty(),
                        "the injected write failure must leave the completed effect absent from Graph state");
            }

            try (var connection = database.open(); var statement = connection.createStatement()) {
                statement.executeUpdate("DROP TRIGGER inject_tool_result_write_failure");
            }

            try (TaskGraphRuntime restarted = new TaskGraphRuntime(gateway, repository)) {
                ToolResult accepted = restarted.resume(context,
                        new ToolCall("resume-effect-crash-window", "task_graph.resume", Json.object()),
                        execute.callId());
                assertTrue(accepted.success(), accepted.observation().toString());
                ToolResult terminal = restarted.await(
                        context, execute, Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals("SUCCEEDED", terminal.observation().path("state").asText());
                assertEquals(1, gateway.executeCalls,
                        "the already completed non-idempotent effect must not be dispatched again");
                assertEquals(1, gateway.reconcileCalls);
                assertEquals(4, gateway.reconciledArguments.path("distance").asInt());
                assertTrue(repository.get(execute.callId()).orElseThrow().toolResults()
                        .has("effect-crash-window:move:1"));
            }
        }
    }

    @Test
    void timeoutConfirmsWaitCancellationAndQuarantinesUnconfirmedToolCancellation() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("timeout-confirmation.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
            ToolCall waitCall = new ToolCall("execution-timeout-wait", "task_graph.execute", Json.object());
            var waitGraph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"timeout-wait","permissions":[],
                     "root":{"id":"wait","type":"wait","durationMillis":1000}}
                    """);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(
                    new FakeGateway(), repository, null, Duration.ofMillis(200))) {
                runtime.start(context, waitCall, waitGraph, Json.object(), Json.object());
                ToolResult timeout = runtime.await(
                        context, waitCall, Duration.ofMillis(30), ignored -> { });
                assertEquals("TOOL_TIMEOUT_CANCELLED", timeout.code());
                assertEquals("CANCELLED", timeout.observation().path("state").asText());
                assertTrue(timeout.observation().path("cancellationConfirmed").asBoolean());
            }

            UnconfirmedCancellationGateway gateway = new UnconfirmedCancellationGateway();
            ToolCall toolCall = new ToolCall("execution-timeout-tool", "task_graph.execute", Json.object());
            var toolGraph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"timeout-tool",
                     "permissions":["READ_WORLD"],
                     "root":{"id":"block","type":"call_tool","tool":"test.block"}}
                    """);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(
                    gateway, repository, null, Duration.ofMillis(100))) {
                runtime.start(context, toolCall, toolGraph, Json.object(), Json.object());
                assertTrue(gateway.entered.await(2, TimeUnit.SECONDS));
                ToolResult timeout = runtime.await(
                        context, toolCall, Duration.ofMillis(30), ignored -> { });
                assertEquals("TOOL_TIMEOUT_RECONCILIATION_REQUIRED", timeout.code());
                assertEquals("RECONCILIATION_REQUIRED", timeout.observation().path("state").asText());
                assertFalse(timeout.observation().path("cancellationConfirmed").asBoolean());
                assertEquals("RECONCILIATION_REQUIRED",
                        repository.get("execution-timeout-tool").orElseThrow().state());
                assertEquals(Set.of("execution-timeout-tool:block:1"), gateway.cancelled);
            }
        }
    }

    @Test
    void readMemoryUsesPermissionBoundGenericSearchTool() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("memory-node.db"))) {
            database.initialize();
            var memories = new com.mccompanion.runtime.memory.MemoryRepository(database);
            memories.remember("companion-1", com.mccompanion.runtime.memory.MemoryKind.WORLD, "ore:iron",
                    Json.object().put("dimension", "minecraft:overworld"), true, 1.0, null,
                    "BODY_OBSERVATION");
            memories.remember("companion-1", com.mccompanion.runtime.memory.MemoryKind.PREFERENCE, "ore:avoid",
                    Json.object().put("reason", "owner preference"), false, 0.5, Duration.ofDays(1),
                    "EXTERNAL_BRAIN_SUGGESTION");
            ToolGateway memory = new com.mccompanion.runtime.memory.MemoryToolGateway(memories);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(memory,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall execute = new ToolCall("execution-10", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"read-world-memory",
                         "permissions":["MEMORY"],
                         "root":{"id":"memory","type":"read_memory","kind":"WORLD","query":"ore"}}
                        """);
                runtime.start(context, execute, graph, Json.object(), Json.object());
                ToolResult terminal = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(1, terminal.observation().path("outputs").path("memory").size());
                assertEquals("WORLD",
                        terminal.observation().path("outputs").path("memory").path(0).path("kind").asText());

                ToolCall denied = new ToolCall("execution-11", "task_graph.execute", Json.object());
                var missingPermission = (com.fasterxml.jackson.databind.node.ObjectNode) graph.deepCopy();
                missingPermission.set("permissions", Json.MAPPER.createArrayNode());
                ToolResult rejected = runtime.start(context, denied, missingPermission, Json.object(), Json.object());
                assertFalse(rejected.success());
                assertTrue(rejected.observation().toString().contains("TOOL_PERMISSION_NOT_DECLARED"));
            }
        }
    }

    @Test
    void suggestMemoryQuarantinesContentOutsideVerifiedMemory() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("suggest-memory-node.db"))) {
            database.initialize();
            var memories = new com.mccompanion.runtime.memory.MemoryRepository(database);
            ToolGateway memory = new com.mccompanion.runtime.memory.MemoryToolGateway(memories);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(memory,
                    new TaskGraphExecutionRepository(database))) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall execute = new ToolCall("execution-suggest", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"suggest-world-memory",
                         "permissions":["MEMORY"],
                         "root":{"id":"root","type":"sequence","nodes":[
                           {"id":"suggest","type":"suggest_memory","kind":"WORLD",
                            "content":"examplemod moon landmark at 1 2 3"},
                           {"id":"read","type":"read_memory","kind":"WORLD","query":"moon"},
                           {"id":"done","type":"return","value":"${outputs.read.length}"}
                         ]}}
                        """);

                runtime.start(context, execute, graph, Json.object(), Json.object());
                ToolResult terminal = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });

                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(0, terminal.observation().path("value").asInt());
                assertEquals("QUARANTINED", terminal.observation().path("outputs")
                        .path("suggest").path("status").asText());
                assertTrue(memories.relevant("companion-1",
                        com.mccompanion.runtime.memory.MemoryKind.WORLD, 100).isEmpty());
                var suggestions = memories.suggestions("companion-1", "QUARANTINED", 10);
                assertEquals(1, suggestions.size());
                assertEquals("brain-1", suggestions.getFirst().brainSessionId());
                assertEquals(com.mccompanion.runtime.memory.MemoryKind.WORLD,
                        suggestions.getFirst().kind());
            }
        }
    }

    @Test
    void askUserPersistsQuestionAndResumesSameExecutionWithDurableAnswerOutput() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("ask-user.db"))) {
            database.initialize();
            var conversations = new com.mccompanion.runtime.conversation.ConversationRepository(database);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(new FakeGateway(),
                    new TaskGraphExecutionRepository(database), conversations)) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                ToolCall execute = new ToolCall("execution-12", "task_graph.execute", Json.object());
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"ask-and-resume","permissions":[],
                         "root":{"id":"root","type":"sequence","nodes":[
                          {"id":"ask","type":"ask_user","prompt":"Continue?","options":["Yes","No"]},
                          {"id":"done","type":"return","value":"${outputs.ask.text}"}
                         ]}}
                        """);
                runtime.start(context, execute, graph, Json.object(), Json.object());
                ToolResult waiting = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertTrue(waiting.success(), waiting.observation().toString());
                assertEquals("WAITING", waiting.observation().path("state").asText());
                var question = conversations.activeForCompanion("companion-1").orElseThrow();
                assertNull(question.taskId());
                assertEquals("execution-12", question.taskGraphExecutionId());
                assertFalse(question.freeTextAllowed());
                assertEquals("execution-12",
                        waiting.observation().path("waitingQuestion").path("context")
                                .path("taskGraphExecutionId").asText());

                ToolResult resumed = runtime.answer(question,
                        new com.mccompanion.runtime.conversation.IncomingMessageResolution(
                                com.mccompanion.runtime.conversation.IncomingMessageKind.WAITING_ANSWER,
                                "option_1", "Yes"));
                assertTrue(resumed.success(), resumed.observation().toString());
                ToolResult duplicate = runtime.answer(question,
                        new com.mccompanion.runtime.conversation.IncomingMessageResolution(
                                com.mccompanion.runtime.conversation.IncomingMessageKind.WAITING_ANSWER,
                                "option_1", "Yes"));
                assertFalse(duplicate.success());
                ToolResult terminal = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals("Yes", terminal.observation().path("outputs").path("ask").path("text").asText());
                assertEquals("Yes", terminal.observation().path("value").asText());
                assertTrue(conversations.activeForCompanion("companion-1").isEmpty());
            }
        }
    }

    @Test
    void askUserSurvivesRuntimeRestartAndResumesOriginalExecution() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("ask-restart.db"))) {
            database.initialize();
            var repository = new TaskGraphExecutionRepository(database);
            var conversations = new com.mccompanion.runtime.conversation.ConversationRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
            ToolCall execute = new ToolCall("execution-13", "task_graph.execute", Json.object());
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"ask-restart","permissions":[],
                     "root":{"id":"root","type":"sequence","nodes":[
                      {"id":"ask","type":"ask_user","prompt":"Choose","options":["One","Two"]},
                      {"id":"done","type":"return","value":"${outputs.ask.optionId}"}
                     ]}}
                    """);
            try (TaskGraphRuntime first = new TaskGraphRuntime(new FakeGateway(), repository, conversations)) {
                first.start(context, execute, graph, Json.object(), Json.object());
                ToolResult waiting = first.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertEquals("WAITING", waiting.observation().path("state").asText());
            }
            assertEquals(0, repository.markUnfinishedForReconciliation());
            var question = conversations.activeForTaskGraph("execution-13").orElseThrow();
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(new FakeGateway(), repository, conversations)) {
                ToolResult resumed = restarted.answer(question,
                        new com.mccompanion.runtime.conversation.IncomingMessageResolution(
                                com.mccompanion.runtime.conversation.IncomingMessageKind.WAITING_ANSWER,
                                "option_2", "Two"));
                assertTrue(resumed.success(), resumed.observation().toString());
                ToolResult terminal = restarted.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals("option_2", terminal.observation().path("value").asText());
                assertEquals("execution-13", terminal.observation().path("executionId").asText());
            }
        }
    }

    @Test
    void cancellingWaitingGraphCancelsItsQuestionAndPreventsLateAnswer() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("ask-cancel.db"))) {
            database.initialize();
            var repository = new TaskGraphExecutionRepository(database);
            var conversations = new com.mccompanion.runtime.conversation.ConversationRepository(database);
            ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
            ToolCall execute = new ToolCall("execution-14", "task_graph.execute", Json.object());
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"ask-cancel","permissions":[],
                     "root":{"id":"ask","type":"ask_user","prompt":"Continue?","options":["Yes","No"]}}
                    """);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(new FakeGateway(), repository, conversations)) {
                runtime.start(context, execute, graph, Json.object(), Json.object());
                assertEquals("WAITING",
                        runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { })
                                .observation().path("state").asText());
                var question = conversations.activeForTaskGraph("execution-14").orElseThrow();
                ToolResult cancelled = runtime.cancel(question, "OWNER_CANCELLED");
                assertTrue(cancelled.success(), cancelled.observation().toString());
                assertTrue(conversations.activeForTaskGraph("execution-14").isEmpty());
                ToolResult late = runtime.answer(question,
                        new com.mccompanion.runtime.conversation.IncomingMessageResolution(
                                com.mccompanion.runtime.conversation.IncomingMessageKind.WAITING_ANSWER,
                                "option_1", "Yes"));
                assertFalse(late.success());
                assertEquals("CANCELLED", repository.get("execution-14").orElseThrow().state());
            }
        }
    }

    @Test
    void timedWaitsReleaseGraphWorkersAndResumeFromPersistentDeadlines() throws Exception {
        Path databasePath = temporary.resolve("timed-waits.db");
        ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
        var waitGraph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"bounded-wait","permissions":[],
                 "root":{"id":"wait","type":"wait","durationMillis":800}}
                """);
        var immediateGraph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"immediate","permissions":[],
                 "root":{"id":"done","type":"return","value":"ready"}}
                """);
        try (RuntimeDatabase database = new RuntimeDatabase(databasePath)) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(new FakeGateway(), repository)) {
                for (String id : List.of("wait-a", "wait-b")) {
                    runtime.start(context, new ToolCall(id, "task_graph.execute", Json.object()),
                            waitGraph, Json.object(), Json.object());
                    waitForState(runtime, context, id, "WAITING");
                }
                long started = System.nanoTime();
                ToolCall immediate = new ToolCall("immediate-c", "task_graph.execute", Json.object());
                runtime.start(context, immediate, immediateGraph, Json.object(), Json.object());
                ToolResult terminal = runtime.await(context, immediate, Duration.ofSeconds(1), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 500,
                        "two timed waits occupied both fixed Graph workers");
                assertEquals("WAITING", repository.get("wait-a").orElseThrow().state());
                assertEquals("TIME",
                        repository.get("wait-a").orElseThrow().waitingQuestion().path("kind").asText());
            }
            try (TaskGraphRuntime recovered = new TaskGraphRuntime(new FakeGateway(), repository)) {
                ToolCall first = new ToolCall("wait-a", "task_graph.execute", Json.object());
                ToolResult completed = recovered.await(context, first, Duration.ofSeconds(2), ignored -> { });
                assertTrue(completed.success(), completed.observation().toString());
                assertEquals("SUCCEEDED", completed.observation().path("state").asText());
            }
        }
    }

    @Test
    void queuedExecutionsRemainFairAndCompleteUnderSustainedAdmission() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("scheduler-soak.db"))) {
            database.initialize();
            FairnessGateway gateway = new FairnessGateway();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway, repository)) {
                ToolContext context = new ToolContext("hermes", "brain-fairness", "companion-1");
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"scheduler-fairness",
                         "inputs":{"index":{"type":"integer","required":true}},
                         "permissions":["READ_WORLD"],
                         "root":{"id":"observe","type":"call_tool","tool":"test.observe",
                          "arguments":{"index":"${inputs.index}"}}}
                        """);
                int executions = 64;
                for (int index = 0; index < 2; index++) {
                    runtime.start(context,
                            new ToolCall("fair-" + index, "task_graph.execute", Json.object()),
                            graph, Json.object().put("index", index), Json.object());
                }
                assertTrue(gateway.firstWorkersEntered.await(2, TimeUnit.SECONDS),
                        "the two graph workers did not enter the controlled saturation boundary");
                long saturatedAt = System.nanoTime();
                for (int index = 2; index < executions; index++) {
                    runtime.start(context,
                            new ToolCall("fair-" + index, "task_graph.execute", Json.object()),
                            graph, Json.object().put("index", index), Json.object());
                }
                var saturated = runtime.telemetry();
                assertEquals("READY", saturated.path("status").asText());
                assertEquals(executions, saturated.path("activeExecutions").asInt());
                assertEquals(2, saturated.path("activeToolCalls").asInt());
                assertEquals(2, saturated.path("workerActive").asInt());
                assertEquals(executions - 2, saturated.path("workerQueueDepth").asInt());
                assertEquals(executions, saturated.path("durable").path("totalExecutions").asInt());
                assertEquals(2, saturated.path("durable").path("states").path("RUNNING").asInt());
                assertEquals(executions - 2,
                        saturated.path("durable").path("states").path("READY").asInt());
                gateway.release.countDown();
                for (int index = executions - 1; index >= 0; index--) {
                    ToolCall call = new ToolCall("fair-" + index, "task_graph.execute", Json.object());
                    ToolResult terminal = runtime.await(context, call, Duration.ofSeconds(10), ignored -> { });
                    assertTrue(terminal.success(), "execution " + index + " starved: " + terminal.observation());
                    assertEquals("SUCCEEDED", repository.get(call.callId()).orElseThrow().state());
                }
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - saturatedAt).toMillis();
                assertEquals(executions, gateway.started.size());
                assertEquals(executions, Set.copyOf(gateway.started).size());
                int lastEarly = gateway.started.stream()
                        .filter(index -> index >= 2 && index < 12)
                        .mapToInt(gateway.started::indexOf).max().orElseThrow();
                int firstLate = gateway.started.stream()
                        .filter(index -> index >= executions - 10)
                        .mapToInt(gateway.started::indexOf).min().orElseThrow();
                assertTrue(lastEarly < firstLate,
                        "FIFO scheduler admitted the tail before the early queued cohort: " + gateway.started);
                assertTrue(elapsedMillis < 10_000,
                        "64 durable executions exceeded the bounded local scheduler budget: " + elapsedMillis + "ms");
                var drained = runtime.telemetry();
                assertEquals(0, drained.path("activeExecutions").asInt());
                assertEquals(0, drained.path("activeToolCalls").asInt());
                assertEquals(0, drained.path("workerActive").asInt());
                assertEquals(0, drained.path("workerQueueDepth").asInt());
                assertEquals(executions,
                        drained.path("durable").path("states").path("SUCCEEDED").asInt());
            } finally {
                gateway.release.countDown();
            }
        }
    }

    @Test
    void timedWaitCanBeCancelledWhileNoGraphWorkerIsActive() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("cancel-wait.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(new FakeGateway(), repository)) {
                ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"cancel-wait","permissions":[],
                         "root":{"id":"wait","type":"wait","durationMillis":5000}}
                        """);
                ToolCall execute = new ToolCall("cancel-wait", "task_graph.execute", Json.object());
                runtime.start(context, execute, graph, Json.object(), Json.object());
                waitForState(runtime, context, execute.callId(), "WAITING");
                ToolResult cancelled = runtime.cancel(context,
                        new ToolCall("cancel-request", "task_graph.cancel", Json.object()),
                        execute.callId(), "test cancellation");
                assertTrue(cancelled.success(), cancelled.observation().toString());
                assertEquals("CANCELLED", repository.get(execute.callId()).orElseThrow().state());
                Thread.sleep(100);
                assertEquals("CANCELLED", repository.get(execute.callId()).orElseThrow().state());
            }
        }
    }

    @Test
    void retryBackoffSurvivesRestartAndAdvancesToTheNextStableAttempt() throws Exception {
        Path databasePath = temporary.resolve("retry-backoff.db");
        ToolContext context = new ToolContext("hermes", "brain-1", "companion-1");
        FlakyRetryGateway gateway = new FlakyRetryGateway();
        var graph = Json.parse("""
                {"version":"mcac-task-graph/1","id":"retry-backoff",
                 "permissions":["READ_WORLD"],
                 "root":{"id":"retry","type":"retry","maxAttempts":2,"backoffMillis":1000,
                  "node":{"id":"observe","type":"call_tool","tool":"test.retry"}}}
                """);
        try (RuntimeDatabase database = new RuntimeDatabase(databasePath)) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            ToolCall execute = new ToolCall("retry-backoff", "task_graph.execute", Json.object());
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway, repository)) {
                runtime.start(context, execute, graph, Json.object(), Json.object());
                waitForExecutionState(runtime, context, execute.callId(), "WAITING");
                assertEquals(List.of("retry-backoff:observe:1"), gateway.callIds);
                TaskGraphExecutionRecord waiting = repository.get(execute.callId()).orElseThrow();
                assertEquals(2, waiting.variables().path("_mcac").path("retries").path("retry").asInt());
                assertEquals("retry:backoff", waiting.waitingQuestion().path("nodeKey").asText());
            }
            try (TaskGraphRuntime restarted = new TaskGraphRuntime(gateway, repository)) {
                ToolResult terminal = restarted.await(context, execute, Duration.ofSeconds(3), ignored -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(List.of("retry-backoff:observe:1", "retry-backoff:observe:2"), gateway.callIds);
                assertEquals(2, terminal.observation().path("outputs").path("observe").path("attempt").asInt());
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

    private static void waitForExecutionState(TaskGraphRuntime runtime, ToolContext context,
                                              String executionId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            ToolResult inspected = runtime.inspect(context,
                    new ToolCall("inspect-state", "task_graph.inspect", Json.object()), executionId);
            if (inspected.observation().path("state").asText().equals(expected)) return;
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
                    Json.object().put("item", call.arguments().path("item").asText())
                            .put("count", arguments.size()), true);
        }
    }

    private static final class FlakyRetryGateway implements ToolGateway {
        private final List<String> callIds = new CopyOnWriteArrayList<>();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.retry", "1.0", "retry observation",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD",
                    Duration.ofSeconds(1), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            callIds.add(call.callId());
            int attempt = callIds.size();
            return new ToolResult(call.callId(), call.name(), attempt > 1,
                    attempt > 1 ? "OK" : "TEMPORARY_FAILURE",
                    Json.object().put("attempt", attempt), true);
        }
    }

    private static final class BlockingParallelGateway implements ToolGateway {
        private final CountDownLatch entered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.block", "1.0", "blocking observation",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD",
                    Duration.ofSeconds(2), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            entered.countDown();
            return new ToolResult(call.callId(), call.name(), true, "ACCEPTED", Json.object(), false);
        }

        @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted,
                                                  Duration timeout,
                                                  java.util.function.Consumer<ToolResult> progress) {
            try {
                if (!release.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    return new ToolResult(call.callId(), call.name(), false, "TIMEOUT", Json.object(), true);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return new ToolResult(call.callId(), call.name(), false, "INTERRUPTED", Json.object(), true);
            }
            boolean wasCancelled = cancelled.contains(call.callId());
            return new ToolResult(call.callId(), call.name(), !wasCancelled,
                    wasCancelled ? "CANCELLED" : "OK",
                    Json.object().put("side", call.arguments().path("side").asText()), true);
        }

        @Override public void cancel(ToolContext context, String callId, String reason) {
            cancelled.add(callId);
            if (cancelled.size() >= 2) release.countDown();
        }
    }

    private static final class AtomicGateway implements ToolGateway {
        private final boolean idempotent;
        private int calls;

        private AtomicGateway(boolean idempotent) {
            this.idempotent = idempotent;
        }

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.move", "1.0", "move",
                    Json.object().put("type", "object"), "LOW", "MOVE",
                    Duration.ofSeconds(1), idempotent));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            calls++;
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("moved", true), true);
        }
    }

    private static final class ReconciliationGateway implements ToolGateway {
        private int executeCalls;
        private int reconcileCalls;
        private com.fasterxml.jackson.databind.JsonNode reconciledArguments = Json.object();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.move", "1.0", "move",
                    Json.object().put("type", "object")
                            .set("properties", Json.object().set("distance",
                                    Json.object().put("type", "integer"))),
                    "LOW", "MOVE", Duration.ofSeconds(1), false));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            executeCalls++;
            return ToolResult.rejected(call, "UNEXPECTED_REPLAY", "must not dispatch during reconciliation");
        }

        @Override public Optional<ToolResult> reconcile(ToolContext context, ToolCall call) {
            reconcileCalls++;
            reconciledArguments = call.arguments().deepCopy();
            return Optional.of(new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("state", "SUCCEEDED").put("taskId", "durable-task-1"), true));
        }
    }

    private static final class DurableEffectGateway implements ToolGateway {
        private int executeCalls;
        private int reconcileCalls;
        private ToolResult durableResult;
        private com.fasterxml.jackson.databind.JsonNode reconciledArguments = Json.object();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.move", "1.0", "move",
                    Json.object().put("type", "object")
                            .set("properties", Json.object().set("distance",
                                    Json.object().put("type", "integer"))),
                    "LOW", "MOVE", Duration.ofSeconds(1), false));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            executeCalls++;
            durableResult = new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("state", "SUCCEEDED")
                            .put("distance", call.arguments().path("distance").asInt()), true);
            return durableResult;
        }

        @Override public Optional<ToolResult> reconcile(ToolContext context, ToolCall call) {
            reconcileCalls++;
            reconciledArguments = call.arguments().deepCopy();
            if (durableResult == null
                    || !durableResult.callId().equals(call.callId())
                    || !durableResult.toolName().equals(call.name())) {
                return Optional.empty();
            }
            return Optional.of(durableResult);
        }
    }

    private static final class FairnessGateway implements ToolGateway {
        private final CountDownLatch firstWorkersEntered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<Integer> started = new CopyOnWriteArrayList<>();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.observe", "1.0", "observe",
                    Json.object().put("type", "object")
                            .set("properties", Json.object().set("index",
                                    Json.object().put("type", "integer"))),
                    "LOW", "READ_WORLD", Duration.ofSeconds(2), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            int index = call.arguments().path("index").asInt();
            started.add(index);
            if (index < 2) {
                firstWorkersEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        return new ToolResult(call.callId(), call.name(), false,
                                "TEST_SATURATION_TIMEOUT", Json.object(), true);
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return new ToolResult(call.callId(), call.name(), false,
                            "INTERRUPTED", Json.object(), true);
                }
            }
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("index", index), true);
        }
    }

    private static final class UnconfirmedCancellationGateway implements ToolGateway {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.block", "1.0", "block",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD",
                    Duration.ofSeconds(10), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            return new ToolResult(call.callId(), call.name(), true, "ACCEPTED", Json.object(), false);
        }

        @Override public ToolResult awaitTerminal(
                ToolContext context, ToolCall call, ToolResult accepted, Duration timeout,
                java.util.function.Consumer<ToolResult> progress) {
            entered.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(call.callId(), call.name(), false, "INTERRUPTED", Json.object(), true);
        }

        @Override public void cancel(ToolContext context, String callId, String reason) {
            cancelled.add(callId);
        }
    }
}
