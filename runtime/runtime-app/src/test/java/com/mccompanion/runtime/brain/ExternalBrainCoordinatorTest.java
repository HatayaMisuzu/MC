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
                assertFalse(accepted.terminal());
                assertEquals("ACCEPTED", accepted.observation().path("state").asText());
                return new ToolResult(call.callId(), call.name(), true, "OK",
                        Json.object().put("state", "SUCCEEDED").put("taskId", "task-1")
                                .put("behaviorId", "behavior-1")
                                .set("fabricObservation", Json.object().put("arrived", true)), true);
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
            assertEquals("SUCCEEDED", result.observation().path("state").asText());
            assertTrue(result.observation().path("fabricObservation").path("arrived").asBoolean());
            return BrainTurnResult.finalResponse("arrived");
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
    void concurrentCancelDoesNotWaitForCompanionTurnLock() throws Exception {
        CountDownLatch awaiting = new CountDownLatch(1);
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
                awaiting.countDown();
                try {
                    if (!cancelled.await(2, TimeUnit.SECONDS)) throw new AssertionError("cancel was blocked");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return new ToolResult(call.callId(), call.name(), false, "TOOL_CANCELLED",
                        Json.object().put("state", "CANCELLED").put("taskId", "task-1"), true);
            }
            @Override public void cancel(ToolContext context, String callId, String reason) { cancelled.countDown(); }
        };
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> BrainTurnResult.tools(List.of(
                new ToolCall("navigate-1", "movement.navigate", Json.object()))));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            CompletableFuture<BrainCoordinatorResult> turn = CompletableFuture.supplyAsync(() ->
                    coordinator.continueTurn("hermes-1", "c1", "go", context()));
            assertTrue(awaiting.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            coordinator.cancel("hermes-1", "c1", "OWNER_CANCELLED");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                    "cancel waited for the companion turn lock");
            assertEquals(BrainTurnResult.Kind.CANCEL, turn.get(2, TimeUnit.SECONDS).kind());
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
                    Json.object().put("health", 18), true);
        }
    }

    private static final class JsonNodeView {
        private final com.fasterxml.jackson.databind.JsonNode json;
        private JsonNodeView(String value) { json = Json.parse(value); }
    }
}
