package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import com.mccompanion.runtime.conversation.ConversationOption;
import com.mccompanion.runtime.conversation.ConversationRepository;
import com.mccompanion.runtime.conversation.IncomingMessageKind;
import com.mccompanion.runtime.conversation.IncomingMessageResolution;
import com.mccompanion.runtime.conversation.WaitingQuestion;
import com.mccompanion.runtime.db.RuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExternalBrainCoordinatorTest {
    @TempDir Path temporary;

    @Test
    void replayBrainCanChatWithoutCreatingOrCallingAnyTool() {
        RecordingGateway gateway = new RecordingGateway();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request ->
                BrainTurnResult.finalResponse("今天就轻松聊会儿，不开始任务。"));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn("hermes-1", "c1",
                    "今天有点累", context());

            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
            assertTrue(result.response().contains("不开始任务"));
            assertTrue(result.toolResults().isEmpty());
            assertTrue(gateway.calls.isEmpty());
            assertEquals("hermes-1", coordinator.activeControllerId());
        }
    }

    @Test
    void coordinatorRelaysToolObservationBackToTheSameExternalBrainTurn() {
        RecordingGateway gateway = new RecordingGateway();
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
            if (turns.getAndIncrement() == 0) {
                assertTrue(request.toolResults().isEmpty());
                return BrainTurnResult.tools(List.of(new ToolCall("observe-1", "world.observe", Json.object())));
            }
            assertEquals(1, request.toolResults().size());
            assertEquals(18, request.toolResults().getFirst().observation().path("health").asInt());
            assertEquals("", request.userMessage());
            return BrainTurnResult.finalResponse("现在生命值是 18，我建议先休息，不自动行动。");
        });
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn("hermes-1", "c1",
                    "我们现在适合做什么？", context());

            assertEquals(2, turns.get());
            assertEquals(List.of("world.observe"), gateway.calls);
            assertEquals(1, result.toolResults().size());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
        }
    }

    @Test
    void externalBrainCanHonestlyStopAtUnsupportedGenericInteractionAfterReadOnlyDiscovery() {
        List<String> calls = new ArrayList<>();
        ToolGateway discovery = new ToolGateway() {
            @Override public List<ToolDefinition> definitions(ToolContext context) {
                return List.of(
                        new ToolDefinition(
                                "registry.describe", "1.0", "Describe connected Registry content",
                                Json.object().put("type", "object"), "LOW", "READ_WORLD",
                                Duration.ofSeconds(5), true),
                        new ToolDefinition(
                                "recipe.query", "1.0", "Query connected recipes",
                                Json.object().put("type", "object"), "LOW", "READ_WORLD",
                                Duration.ofSeconds(5), true));
            }

            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                calls.add(call.name());
                if (call.name().equals("registry.describe")) {
                    return new ToolResult(
                            call.callId(), call.name(), true, "OK",
                            Json.object().put("id", "mcac_unknown_fixture:special_console")
                                    .put("genericInteraction", false), true);
                }
                return new ToolResult(
                        call.callId(), call.name(), true, "OK",
                        Json.object().putArray("recipes"), true);
            }
        };
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> switch (turns.getAndIncrement()) {
            case 0 -> BrainTurnResult.tools(List.of(new ToolCall(
                    "describe-special", "registry.describe",
                    Json.object().put("kind", "BLOCK")
                            .put("id", "mcac_unknown_fixture:special_console"))));
            case 1 -> BrainTurnResult.tools(List.of(new ToolCall(
                    "query-special-recipe", "recipe.query",
                    Json.object().put("output", "mcac_unknown_fixture:special_result"))));
            default -> BrainTurnResult.finalResponse(
                    "UNSUPPORTED_GENERIC_INTERACTION: Registry and recipe observations expose no "
                            + "bounded generic interaction for this special mechanism.");
        });

        try (ExternalBrainCoordinator coordinator =
                     new ExternalBrainCoordinator(brain, discovery, 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn(
                    "external-agent", "c1",
                    "Use the unknown special console without guessing its internal mechanism.",
                    context());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
            assertTrue(result.response().startsWith("UNSUPPORTED_GENERIC_INTERACTION"));
            assertEquals(List.of("registry.describe", "recipe.query"), calls);
            assertTrue(calls.stream().noneMatch(name ->
                    name.startsWith("block.") || name.startsWith("menu.")
                            || name.startsWith("memory.")));
        }
    }

    @Test
    void acceptedMinecraftCommandIsNeverReturnedToBrainBeforeTerminalFabricObservation() {
        AtomicInteger turns = new AtomicInteger();
        ToolGateway gateway = new ToolGateway() {
            @Override public List<ToolDefinition> definitions(ToolContext context) {
                return List.of(new ToolDefinition("movement.navigate", "1.0", "navigate", Json.object(),
                        "LOW", "MOVE", Duration.ofSeconds(1), false));
            }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return new ToolResult(call.callId(), call.name(), true, "COMMAND_DISPATCHED",
                        Json.object().put("state", "ACCEPTED").put("taskId", "task-1")
                                .put("behaviorId", "behavior-1"), false);
            }
            @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted,
                                                      Duration timeout, java.util.function.Consumer<ToolResult> progress) {
                throw new AssertionError("external Brain request must not await a durable action");
            }
        };
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
            if (turns.getAndIncrement() == 0) {
                assertTrue(request.toolResults().isEmpty());
                return BrainTurnResult.tools(List.of(new ToolCall("navigate-1", "movement.navigate",
                        Json.object().put("x", 4).put("y", 64).put("z", 8))));
            }
            assertEquals(1, request.toolResults().size());
            ToolResult result = request.toolResults().getFirst();
            assertTrue(result.terminal());
            assertEquals("ACCEPTED", result.observation().path("state").asText());
            assertEquals("ASYNCHRONOUS", result.observation().path("executionMode").asText());
            assertFalse(result.observation().path("completionVerified").asBoolean());
            assertEquals("task.inspect", result.observation().path("statusTool").asText());
            return BrainTurnResult.finalResponse("navigation started");
        });
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn("hermes-1", "c1", "go", context());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
            assertEquals(2, turns.get());
        }
    }

    @Test
    void enforcesSingleControllerAndToolBudgetWithoutInventingFallbackStrategy() {
        RecordingGateway gateway = new RecordingGateway();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> BrainTurnResult.tools(List.of(
                new ToolCall("a", "world.observe", Json.object()),
                new ToolCall("b", "world.observe", Json.object()))));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 1)) {
            BrainCoordinatorResult exhausted = coordinator.continueTurn("hermes-1", "c1", "observe", context());
            assertEquals("TOOL_BUDGET_EXHAUSTED", exhausted.code());
            assertTrue(gateway.calls.isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.continueTurn("deepseek-2", "c1", "take over", context()));
        }
    }

    @Test
    void askUserIsDurableAndAnswerResumesTheSameBrainSessionExactlyOnce() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
            if (turns.getAndIncrement() == 0) {
                return BrainTurnResult.askUser(new BrainQuestion(
                        "Only 6 of 16 iron ingots are available. What should I do?", "RESOURCE_SHORTAGE",
                        List.of(new ConversationOption("deliver_partial", "Deliver 6", "Deliver existing stock"),
                                new ConversationOption("collect_missing", "Collect 10", "Mine the remainder")),
                        false, Json.object().put("available", 6).put("requested", 16), null));
            }
            JsonNodeView answer = new JsonNodeView(request.userMessage());
            assertEquals("user_answer", answer.json.path("type").asText());
            assertEquals("deliver_partial", answer.json.path("optionId").asText());
            return BrainTurnResult.finalResponse("I will deliver the available 6 ingots.");
        });
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("ask-user.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            ConversationRepository conversations = new ConversationRepository(database);
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                    brain, new RecordingGateway(), 4, audit, conversations)) {
                BrainCoordinatorResult asked = coordinator.continueTurn("hermes-1", "c1", "Bring 16 iron", context());
                assertEquals(BrainTurnResult.Kind.ASK_USER, asked.kind());
                assertNotNull(asked.question());
                assertEquals(asked.sessionId(), asked.question().brainSessionId());
                assertEquals(asked.question().questionId(), conversations.activeForCompanion("c1").orElseThrow().questionId());

                BrainCoordinatorResult resumed = coordinator.answer("hermes-1", asked.question(),
                        new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER,
                                "deliver_partial", "Deliver 6"), context());
                assertEquals(asked.sessionId(), resumed.sessionId());
                assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, resumed.kind());
                assertTrue(conversations.activeForCompanion("c1").isEmpty());
                assertEquals(1, conversations.list("c1", 10).stream()
                        .filter(event -> event.kind().equals("ANSWER")).count());
            }
        }
    }

    @Test
    void answerAfterRuntimeInterruptionResumesPersistedBrainSession() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("ask-restart.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            ConversationRepository conversations = new ConversationRepository(database);
            ReplayBrainAdapter beforeRestart = new ReplayBrainAdapter(request -> BrainTurnResult.askUser(
                    new BrainQuestion("Continue with 6?", "RESOURCE_SHORTAGE",
                            List.of(new ConversationOption("yes", "Yes", "Continue with six")),
                            false, Json.object(), null)));
            ExternalBrainCoordinator first = new ExternalBrainCoordinator(
                    beforeRestart, new RecordingGateway(), 4, audit, conversations);
            try {
                BrainCoordinatorResult asked = first.continueTurn("hermes-1", "c1", "Bring 16", context());
                String originalSession = asked.sessionId();
                assertEquals(1, audit.interruptActiveSessions());

                ReplayBrainAdapter afterRestart = new ReplayBrainAdapter(request -> {
                    assertEquals(originalSession, request.sessionId());
                    assertTrue(request.userMessage().contains("\"optionId\":\"yes\""));
                    return BrainTurnResult.finalResponse("Continuing with six.");
                });
                try (ExternalBrainCoordinator recovered = new ExternalBrainCoordinator(
                        afterRestart, new RecordingGateway(), 4, audit, conversations)) {
                    WaitingQuestion persisted = conversations.activeForCompanion("c1").orElseThrow();
                    BrainCoordinatorResult result = recovered.answer("hermes-1", persisted,
                            new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER, "yes", "yes"), context());
                    assertEquals(originalSession, result.sessionId());
                    assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
                }
            } finally {
                first.close();
            }
        }
    }

    @Test
    void sessionMismatchIsRejectedBeforeTheWaitingAnswerIsPersisted() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("ask-session-race.db"))) {
            database.initialize();
            ConversationRepository conversations = new ConversationRepository(database);
            BrainAuditRepository audit = new BrainAuditRepository(database);
            ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> BrainTurnResult.finalResponse("ready"));
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                    brain, new RecordingGateway(), 4, audit, conversations)) {
                coordinator.continueTurn("hermes-1", "c1", "open", context());
                audit.opened(new BrainSession("different-session", "hermes-1", "c1",
                        java.time.Instant.now()), "fixture");
                WaitingQuestion stale = conversations.askBrain("c1", "different-session", null,
                        "Continue?", "TEST", List.of(new ConversationOption("yes", "Yes", "")),
                        false, Json.object(), null);

                assertThrows(IllegalStateException.class, () -> coordinator.answer("hermes-1", stale,
                        new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER, "yes", "Yes"),
                        context()));
                WaitingQuestion stillWaiting = conversations.activeForCompanion("c1").orElseThrow();
                assertEquals(stale.questionId(), stillWaiting.questionId());
                assertEquals("WAITING", stillWaiting.state());
            }
        }
    }

    @Test
    void repeatedCallIdReturnsAuditedResultWithoutExecutingToolAgain() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        RecordingGateway gateway = new RecordingGateway();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> switch (turns.getAndIncrement()) {
            case 0, 1 -> BrainTurnResult.tools(List.of(
                    new ToolCall("same-call", "world.observe", Json.object())));
            default -> BrainTurnResult.finalResponse("done");
        });
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("duplicate-call.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                    brain, gateway, 4, audit, new ConversationRepository(database))) {
                BrainCoordinatorResult result = coordinator.continueTurn("hermes-1", "c1", "observe", context());
                assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
                assertEquals(3, turns.get());
                assertEquals(List.of("world.observe"), gateway.calls,
                        "duplicate callId must not execute the gateway twice");
                assertEquals(2, result.toolResults().size());
                assertEquals(result.toolResults().get(0).observation(), result.toolResults().get(1).observation());
            }
        }
    }

    @Test
    void durableAsynchronousStartCanBeCancelledAfterBrainRequestReturns() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        ToolGateway gateway = new ToolGateway() {
            @Override public List<ToolDefinition> definitions(ToolContext context) {
                return List.of(new ToolDefinition("movement.navigate", "1.0", "navigate", Json.object(),
                        "LOW", "MOVE", Duration.ofSeconds(5), false));
            }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return new ToolResult(call.callId(), call.name(), true, "COMMAND_DISPATCHED",
                        Json.object().put("state", "ACCEPTED").put("taskId", "task-1"), false);
            }
            @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted,
                                                      Duration timeout, java.util.function.Consumer<ToolResult> progress) {
                throw new AssertionError("external Brain request must not await a durable action");
            }
            @Override public void cancel(ToolContext context, String callId, String reason) { cancelled.countDown(); }
        };
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> turns.getAndIncrement() == 0
                ? BrainTurnResult.tools(List.of(new ToolCall("navigate-1", "movement.navigate", Json.object())))
                : BrainTurnResult.finalResponse("navigation started"));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            BrainCoordinatorResult turn = coordinator.continueTurn("hermes-1", "c1", "go", context());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, turn.kind());
            long started = System.nanoTime();
            coordinator.cancel("hermes-1", "c1", "OWNER_CANCELLED");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                    "cancel was unexpectedly delayed");
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void multipleDurableStartsRemainIndependentlyCancellable() {
        List<String> cancelled = new java.util.concurrent.CopyOnWriteArrayList<>();
        ToolGateway gateway = new ToolGateway() {
            @Override public List<ToolDefinition> definitions(ToolContext context) {
                return List.of(
                        new ToolDefinition("movement.navigate", "1.0", "navigate", Json.object(),
                                "LOW", "MOVE", Duration.ofSeconds(5), false),
                        new ToolDefinition("task_graph.execute", "1.0", "graph", Json.object(),
                                "LOW", "EXECUTE_TASK_GRAPH", Duration.ofSeconds(5), false));
            }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                var observation = Json.object().put("state", "ACCEPTED");
                if (call.name().equals("movement.navigate")) observation.put("taskId", "task-1");
                else observation.put("executionId", "graph-1");
                return new ToolResult(call.callId(), call.name(), true, "ACCEPTED", observation, false);
            }
            @Override public void cancel(ToolContext context, String callId, String reason) {
                cancelled.add(callId);
            }
        };
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> turns.getAndIncrement() == 0
                ? BrainTurnResult.tools(List.of(
                new ToolCall("navigate-1", "movement.navigate", Json.object()),
                new ToolCall("graph-1", "task_graph.execute", Json.object())))
                : BrainTurnResult.finalResponse("accepted"));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            coordinator.continueTurn("hermes-1", "c1", "start both", context());
            coordinator.cancel("hermes-1", "c1", "OWNER_CANCELLED");
            assertEquals(java.util.Set.of("navigate-1", "graph-1"), new java.util.HashSet<>(cancelled));
        }
    }

    @Test
    void durableTrackingIsBoundedAcrossBrainTurnsAndCancelsOverflow() {
        List<String> cancelled = new java.util.concurrent.CopyOnWriteArrayList<>();
        ToolGateway gateway = new ToolGateway() {
            @Override public List<ToolDefinition> definitions(ToolContext context) {
                return List.of(new ToolDefinition("movement.navigate", "1.0", "navigate", Json.object(),
                        "LOW", "MOVE", Duration.ofSeconds(5), false));
            }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return new ToolResult(call.callId(), call.name(), true, "ACCEPTED",
                        Json.object().put("state", "ACCEPTED").put("taskId", "task-" + call.callId()), false);
            }
            @Override public void cancel(ToolContext context, String callId, String reason) {
                if ("DURABLE_EXECUTION_TRACKING_CAPACITY_EXCEEDED".equals(reason)) cancelled.add(callId);
            }
        };
        AtomicInteger batch = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
            if (!request.toolResults().isEmpty()) return BrainTurnResult.finalResponse("accepted");
            int count = batch.getAndIncrement() < 2 ? 32 : 1;
            List<ToolCall> calls = java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> new ToolCall("call-" + batch.get() + "-" + index,
                            "movement.navigate", Json.object()))
                    .toList();
            return BrainTurnResult.tools(calls);
        });
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 32)) {
            assertTrue(coordinator.continueTurn("hermes-1", "c1", "batch one", context())
                    .toolResults().stream().allMatch(ToolResult::success));
            assertTrue(coordinator.continueTurn("hermes-1", "c1", "batch two", context())
                    .toolResults().stream().allMatch(ToolResult::success));
            BrainCoordinatorResult overflow = coordinator.continueTurn(
                    "hermes-1", "c1", "overflow", context());
            assertEquals("DURABLE_EXECUTION_TRACKING_FULL", overflow.toolResults().get(0).code());
            assertEquals(List.of("call-3-0"), cancelled);
        }
    }

    @Test
    void verifiedInterruptionPausesActiveToolAndDeliversPauseBeforeNewMessage() throws Exception {
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch paused = new CountDownLatch(1);
        ToolGateway gateway = new ToolGateway() {
            @Override public List<ToolDefinition> definitions(ToolContext context) {
                return List.of(new ToolDefinition("task_graph.execute", "1.0", "execute",
                        Json.object(), "LOW", "EXECUTE_TASK_GRAPH", Duration.ofSeconds(5), false));
            }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return new ToolResult(call.callId(), call.name(), true, "ACCEPTED",
                        Json.object().put("state", "ACCEPTED"), false);
            }
            @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted,
                                                      Duration timeout,
                                                      java.util.function.Consumer<ToolResult> progress) {
                awaiting.countDown();
                try {
                    if (!paused.await(2, TimeUnit.SECONDS)) throw new AssertionError("pause was blocked");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_PAUSED",
                        Json.object().put("state", "PAUSED"), true);
            }
            @Override public boolean pause(ToolContext context, String callId, String reason) {
                paused.countDown();
                return true;
            }
            @Override public boolean conflictsWithOwnerActivity(
                    ToolContext context, String callId, com.fasterxml.jackson.databind.JsonNode activity) {
                return activity.path("position").path("x").asInt() == 4;
            }
        };
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
            if (turns.getAndIncrement() == 0) {
                return BrainTurnResult.tools(List.of(
                        new ToolCall("long-goal", "task_graph.execute", Json.object())));
            }
            assertEquals("先跟我走", request.userMessage());
            assertEquals(1, request.toolResults().size());
            assertEquals("PAUSED", request.toolResults().getFirst().observation().path("state").asText());
            return BrainTurnResult.finalResponse("我已暂停原任务，现在跟你走。");
        });
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            CompletableFuture<BrainCoordinatorResult> original = CompletableFuture.supplyAsync(() ->
                    coordinator.continueTurn("hermes-1", "c1", "执行长期目标", context()));
            assertTrue(awaiting.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertFalse(coordinator.yieldToOwnerActivity("hermes-1", "c1",
                    Json.object().set("position", Json.object().put("x", 5))));
            assertTrue(coordinator.yieldToOwnerActivity("hermes-1", "c1",
                    Json.object().set("position", Json.object().put("x", 4))));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0);
            assertEquals("BRAIN_TURN_PAUSED_FOR_USER_INSTRUCTION",
                    original.get(2, TimeUnit.SECONDS).code());

            BrainCoordinatorResult immediate = coordinator.continueTurn(
                    "hermes-1", "c1", "先跟我走", context());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, immediate.kind());
            assertEquals(2, turns.get(), "the interrupted old turn must not continue on its own");
        }
    }

    @Test
    void companionSessionsRunConcurrentlyAndRemainIsolated() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        var sessionByCompanion = new ConcurrentHashMap<String, String>();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
            sessionByCompanion.put(request.context().companionId(), request.sessionId());
            bothEntered.countDown();
            try {
                if (!bothEntered.await(1, TimeUnit.SECONDS)) throw new AssertionError("companion turns serialized globally");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return BrainTurnResult.finalResponse("done " + request.context().companionId());
        });
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, new RecordingGateway(), 4)) {
            CompletableFuture<BrainCoordinatorResult> first = CompletableFuture.supplyAsync(() ->
                    coordinator.continueTurn("hermes-1", "c1", "one",
                            AgentContext.empty("c1", List.of("FollowOwner"))));
            CompletableFuture<BrainCoordinatorResult> second = CompletableFuture.supplyAsync(() ->
                    coordinator.continueTurn("hermes-1", "c2", "two",
                            AgentContext.empty("c2", List.of("FollowOwner"))));
            assertEquals("done c1", first.get(2, TimeUnit.SECONDS).response());
            assertEquals("done c2", second.get(2, TimeUnit.SECONDS).response());
            assertEquals(2, sessionByCompanion.size());
            assertNotEquals(sessionByCompanion.get("c1"), sessionByCompanion.get("c2"));
        }
    }

    @Test
    void localBehaviorSettingsReachBrainWithoutChangingAvailableTools() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("brain-settings-context.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            RecordingGateway gateway = new RecordingGateway();
            List<String> modes = new ArrayList<>();
            List<List<String>> toolNames = new ArrayList<>();
            ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
                modes.add(request.context().brainBehaviorSettings().path("initiativeMode").asText());
                toolNames.add(gateway.definitions(new ToolContext("controller", request.sessionId(), "c1"))
                        .stream().map(ToolDefinition::name).toList());
                return BrainTurnResult.finalResponse("acknowledged");
            });
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                    brain, gateway, 4, audit)) {
                coordinator.continueTurn("controller", "c1", "hello", context());
                coordinator.updateBehaviorSettings("c1", BrainSemanticState.InitiativeMode.ACTIVE,
                        BrainSemanticState.PersonalityMode.IMMERSIVE_ROLEPLAY);
                coordinator.continueTurn("controller", "c1", "continue", context());
            }
            assertEquals(List.of("NORMAL", "ACTIVE"), modes);
            assertEquals(toolNames.getFirst(), toolNames.getLast());
        }
    }

    @Test
    void brainCannotOverrideLocalInitiativeOrPersonalitySetting() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("brain-settings-policy.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            audit.updateBehaviorSettings("c1", BrainSemanticState.InitiativeMode.QUIET,
                    BrainSemanticState.PersonalityMode.COMPANION, "LOCAL_MANAGEMENT_USER");
            ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> BrainTurnResult.finalResponse("override")
                    .withSemanticState(new BrainSemanticState("", "", "", "", "", false,
                            BrainSemanticState.InitiativeMode.ACTIVE,
                            BrainSemanticState.PersonalityMode.COMPANION,
                            BrainSemanticState.PermissionPreset.ASK_FOR_EFFECTS,
                            false, null, List.of())));
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                    brain, new RecordingGateway(), 4, audit)) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> coordinator.continueTurn("controller", "c1", "hello", context()));
                assertEquals("BRAIN_SEMANTIC_STATE_POLICY_MISMATCH", failure.getMessage());
            }
        }
    }

    @Test
    void verifiedCompletionClaimLinksToTheBrainSelectedFinalObservation() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> turns.getAndIncrement() == 0
                ? BrainTurnResult.tools(List.of(new ToolCall("final-observe-1", "world.observe", Json.object())))
                : BrainTurnResult.finalResponse("The check is complete.").withCompletionClaim(
                        new BrainCompletionClaim("Base state checked", BrainCompletionClaim.Certainty.VERIFIED,
                                "final-observe-1", "task-1", List.of(
                                new BrainCompletionClaim.EvidenceCondition("/health",
                                        BrainCompletionClaim.Operator.AT_LEAST, Json.parse("18"))), "")));
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("completion-claim.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                    brain, new RecordingGateway(), 4, audit)) {
                BrainCoordinatorResult result = coordinator.continueTurn(
                        "controller", "c1", "check the base", context());
                assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
                var inspected = audit.inspect("c1", 10).path(0).path("completionClaims").path(0);
                assertEquals("VERIFIED", inspected.path("certainty").asText());
                assertEquals("final-observe-1", inspected.path("observationCallId").asText());
                assertEquals("task-1", inspected.path("taskId").asText());
                assertEquals("/health", inspected.path("conditions").path(0).path("pointer").asText());
            }
        }
    }

    @Test
    void missingObservationCannotSupportVerifiedClaimButExplicitUnverifiedGapIsAllowed() throws Exception {
        ReplayBrainAdapter invalid = new ReplayBrainAdapter(request -> BrainTurnResult.finalResponse("done")
                .withCompletionClaim(new BrainCompletionClaim("Done", BrainCompletionClaim.Certainty.VERIFIED,
                        "missing-observation", "task-1", List.of(
                        new BrainCompletionClaim.EvidenceCondition("/state",
                                BrainCompletionClaim.Operator.EQUALS, Json.parse("\"SUCCEEDED\""))), "")));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                invalid, new RecordingGateway(), 4)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> coordinator.continueTurn("controller", "c1", "finish", context()));
            assertEquals("BRAIN_FINAL_OBSERVATION_NOT_FOUND", failure.getMessage());
        }

        ReplayBrainAdapter honest = new ReplayBrainAdapter(request -> BrainTurnResult.finalResponse(
                "I could not verify the result.").withCompletionClaim(new BrainCompletionClaim(
                "Result unavailable", BrainCompletionClaim.Certainty.UNVERIFIED,
                "", "task-1", List.of(), "Companion is offline")));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                honest, new RecordingGateway(), 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn(
                    "controller", "c1", "finish", context());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
        }
    }

    @Test
    void unrelatedSuccessfulObservationCannotSatisfyStructuredCompletionCondition() {
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> turns.getAndIncrement() == 0
                ? BrainTurnResult.tools(List.of(
                new ToolCall("observe-health", "world.observe", Json.object())))
                : BrainTurnResult.finalResponse("Ten iron were collected.").withCompletionClaim(
                new BrainCompletionClaim("Collected ten iron", BrainCompletionClaim.Certainty.VERIFIED,
                        "observe-health", "task-1", List.of(
                        new BrainCompletionClaim.EvidenceCondition("/inventory/minecraft:iron_ingot",
                                BrainCompletionClaim.Operator.AT_LEAST, Json.parse("10"))), "")));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                brain, new RecordingGateway(), 4)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> coordinator.continueTurn("controller", "c1", "collect iron", context()));
            assertEquals("BRAIN_FINAL_OBSERVATION_CONDITION_FAILED:/inventory/minecraft:iron_ingot",
                    failure.getMessage());
        }
    }

    @Test
    void successfulTaskGraphInspectionCanVerifyCompletion() {
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> turns.getAndIncrement() == 0
                ? BrainTurnResult.tools(List.of(new ToolCall("inspect-graph", "task_graph.inspect",
                Json.object().put("executionId", "graph-1"))))
                : BrainTurnResult.finalResponse("Graph completed").withCompletionClaim(
                new BrainCompletionClaim("Graph completed", BrainCompletionClaim.Certainty.VERIFIED,
                        "inspect-graph", "", List.of(
                        new BrainCompletionClaim.EvidenceCondition("/state",
                                BrainCompletionClaim.Operator.EQUALS, Json.parse("\"SUCCEEDED\""))), "")));
        ToolGateway gateway = new ToolGateway() {
            @Override public List<ToolDefinition> definitions(ToolContext context) {
                return List.of(new ToolDefinition("task_graph.inspect", "1.0", "inspect", Json.object(),
                        "LOW", "READ_TASK_GRAPH", Duration.ofSeconds(5), true));
            }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_SUCCEEDED",
                        Json.object().put("executionId", "graph-1").put("state", "SUCCEEDED"), true);
            }
        };
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn("controller", "c1", "finish", context());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
        }
    }

    private static AgentContext context() {
        return AgentContext.empty("c1", List.of("NavigateTo", "FollowOwner"));
    }

    private static final class RecordingGateway implements ToolGateway {
        private final List<String> calls = new ArrayList<>();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("world.observe", "1.0", "Observe verified state",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD", Duration.ofSeconds(5), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            calls.add(call.name());
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("health", 18).put("taskId", "task-1"), true);
        }
    }

    private static final class JsonNodeView {
        private final com.fasterxml.jackson.databind.JsonNode json;
        private JsonNodeView(String value) { json = Json.parse(value); }
    }
}
