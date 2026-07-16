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
                           {"id":"wait","type":"wait","durationMillis":400}
                          ]}}}
                        """);
                runtime.start(context, execute, graph, Json.object().put("target", 2), Json.object());
                waitForState(runtime, context, "execution-7", "RUNNING");
                ToolResult pause = runtime.pause(context,
                        new ToolCall("pause-repeat", "task_graph.pause", Json.object()), "execution-7");
                assertTrue(pause.success());
                ToolResult paused = runtime.await(context, execute, Duration.ofSeconds(2), ignored -> { });
                assertEquals("PAUSED", paused.observation().path("state").asText());
                assertEquals(1, gateway.arguments.size());

                assertTrue(runtime.resume(context,
                        new ToolCall("resume-repeat", "task_graph.resume", Json.object()), "execution-7").success());
                ToolResult terminal = runtime.await(context, execute, Duration.ofSeconds(3), ignored -> { });
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
                    Json.object().put("item", call.arguments().path("item").asText())
                            .put("count", arguments.size()), true);
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
}
