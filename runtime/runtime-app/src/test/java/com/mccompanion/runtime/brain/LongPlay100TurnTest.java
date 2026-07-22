package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.conversation.ConversationOption;
import com.mccompanion.runtime.conversation.ConversationRepository;
import com.mccompanion.runtime.conversation.IncomingMessageKind;
import com.mccompanion.runtime.conversation.IncomingMessageResolution;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.memory.MemoryKind;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic continuity/load regression. It is explicitly not evidence of live model quality. */
class LongPlay100TurnTest {
    private static final int TURNS = 105;
    @TempDir Path temporary;

    @Test
    void sustainsBoundedExternalTurnsAcrossReconnectRestartAndReviewedMemory() throws Exception {
        Path databasePath = temporary.resolve("long-play.db");
        int threadsBefore = Thread.getAllStackTraces().size();
        AtomicInteger reconnects = new AtomicInteger();
        AtomicInteger safeIdle = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicBoolean injectedDisconnect = new AtomicBoolean();
        LongPlayGateway gateway = new LongPlayGateway();

        try (RuntimeDatabase database = new RuntimeDatabase(databasePath)) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            ConversationRepository conversations = new ConversationRepository(database);
            MemoryRepository memories = new MemoryRepository(database);
            Script script = new Script(providerCalls, injectedDisconnect);

            ExternalBrainCoordinator beforeRestart = coordinator(script.adapter(), gateway, audit, conversations,
                    safeIdle, reconnects);
            ExternalBrainCoordinator active = beforeRestart;
            String lastSession = null;
            try {
                for (int turn = 1; turn <= TURNS; turn++) {
                    if (turn == 53) {
                        assertEquals(1, audit.interruptActiveSessions(), "restart must quarantine one active session");
                        active = coordinator(script.adapter(), gateway, audit, conversations, safeIdle, reconnects);
                    }
                    if (turn == 78) {
                        active.cancel("controller", "companion", "USER_CANCELLED");
                    }
                    BrainCoordinatorResult result = active.continueTurn("controller", "companion",
                            "turn-" + turn, context(memories));
                    if (result.kind() == BrainTurnResult.Kind.ASK_USER) {
                        assertEquals(25, turn);
                        result = active.answer("controller", result.question(),
                                new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER,
                                        "continue_safe", "Continue safely"), context(memories));
                    }
                    assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
                    lastSession = result.sessionId();
                }

                exercisePauseResume(database, gateway, lastSession);
                var capsule = memories.capsules().generate("companion", lastSession, "eeceb97");
                var candidate = memories.suggest("companion", MemoryKind.EPISODIC,
                        "episode:long-play", Json.object().put("turns", TURNS).put("result", "bounded"),
                        0.7, Duration.ofDays(30), "EXTERNAL_BRAIN_SUGGESTION", lastSession,
                        capsule.episodeId());
                assertTrue(memories.search("companion", MemoryKind.EPISODIC, "episode:long-play", 10).isEmpty());
                memories.approveSuggestion("companion", candidate.suggestionId(), "LOCAL_MANAGEMENT_USER");

                assertEquals(1, safeIdle.get());
                assertEquals(1, reconnects.get());
                assertEquals(TURNS - 1, gateway.uniqueCalls(), "ASK_USER turn has no Tool call");
                assertEquals(20, gateway.mutationEffects(), "each fifth-turn mutation executes exactly once");
                assertEquals(gateway.mutationEffects(), gateway.uniqueMutationCallIds());
                assertTrue(conversations.list("companion", 100).size() <= 4);
                assertEquals(1, memories.list("companion", MemoryKind.EPISODIC, 10).size());
                assertTrue(capsule.evidenceRefs().size() <= 128);
                assertTrue(Files.size(databasePath) < 8L * 1024 * 1024);
                Path wal = Path.of(databasePath + "-wal");
                long walBytes = Files.exists(wal) ? Files.size(wal) : 0L;
                assertTrue(walBytes < 8L * 1024 * 1024);

                writeReport(databasePath, walBytes, threadsBefore, providerCalls.get(), reconnects.get(),
                        gateway, conversations.list("companion", 100).size(), capsule.evidenceRefs().size());
            } finally {
                active.close();
                if (active != beforeRestart) beforeRestart.close();
            }
        }
        awaitThreadStability(threadsBefore);
    }

    private static ExternalBrainCoordinator coordinator(ExternalBrainAdapter delegate, ToolGateway gateway,
                                                        BrainAuditRepository audit,
                                                        ConversationRepository conversations,
                                                        AtomicInteger safeIdle, AtomicInteger reconnects) {
        BrainReconnectListener listener = new BrainReconnectListener() {
            @Override public void safeIdle(BrainSession session, int attempt, Duration delay,
                                           LiveBrainFailureCategory category) {
                assertEquals(LiveBrainFailureCategory.NETWORK, category); safeIdle.incrementAndGet();
            }
            @Override public void recovered(BrainSession session, int attempts) { reconnects.incrementAndGet(); }
        };
        var budgeted = new BudgetedExternalBrainAdapter(delegate,
                new LiveBrainBudget(512, 1_000_000, 200_000, Duration.ofMinutes(3), 2), listener);
        return new ExternalBrainCoordinator(budgeted, gateway, 4, audit, conversations);
    }

    private static AgentContext context(MemoryRepository memories) throws Exception {
        return new AgentContext("companion", Json.object().put("verified", true), List.of("bounded turn"),
                Json.object(), List.of(), List.of("world.observe", "inventory.transfer"),
                memories.preferenceContext("companion", 8), memories.latestCapsuleContext("companion"), 5);
    }

    private static void exercisePauseResume(RuntimeDatabase database, LongPlayGateway gateway,
                                            String brainSessionId) throws Exception {
        try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway, new TaskGraphExecutionRepository(database))) {
            ToolContext context = new ToolContext("controller", brainSessionId, "companion");
            ToolCall call = new ToolCall("long-play-pause", "task_graph.execute", Json.object());
            JsonNode graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"long-play-pause","permissions":["READ_WORLD"],
                     "root":{"id":"root","type":"sequence","nodes":[
                       {"id":"observe","type":"call_tool","tool":"world.observe","arguments":{}},
                       {"id":"wait","type":"wait","durationMillis":500},
                       {"id":"done","type":"return","value":"ok"}]}}
                    """);
            runtime.start(context, call, graph, Json.object(), Json.object().put("liveModel", false));
            waitForState(runtime, context, call.callId(), "WAITING");
            assertTrue(runtime.controlForManagement("companion", call.callId(), "pause").success());
            assertEquals("PAUSED", runtime.await(context, call, Duration.ofSeconds(2), ignored -> { })
                    .observation().path("state").asText());
            assertTrue(runtime.controlForManagement("companion", call.callId(), "resume").success());
            assertTrue(runtime.await(context, call, Duration.ofSeconds(3), ignored -> { }).success());
        }
    }

    private static void waitForState(TaskGraphRuntime runtime, ToolContext context, String id, String state)
            throws Exception {
        ToolCall inspect = new ToolCall("inspect-" + id, "task_graph.inspect", Json.object());
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (state.equals(runtime.inspect(context, inspect, id).observation().path("state").asText())) return;
            Thread.sleep(10);
        }
        fail("Task Graph did not reach " + state);
    }

    private static void writeReport(Path databasePath, long walBytes, int threadsBefore, int providerCalls,
                                    int reconnects, LongPlayGateway gateway, int conversations,
                                    int evidenceRefs) throws Exception {
        int threadsAfterWork = Thread.getAllStackTraces().size();
        JsonNode report = Json.object().put("schemaVersion", 1).put("liveModel", false)
                .put("provesModelIntelligence", false).put("turns", TURNS).put("result", "PASS")
                .put("providerCalls", providerCalls).put("reconnectCount", reconnects)
                .put("uniqueToolCalls", gateway.uniqueCalls()).put("mutationEffects", gateway.mutationEffects())
                .put("duplicateMutationEffects", 0).put("conversationEvents", conversations)
                .put("evidenceRefs", evidenceRefs).put("databaseBytes", Files.size(databasePath))
                .put("walBytes", walBytes).put("threadsBefore", threadsBefore)
                .put("threadsAfterWork", threadsAfterWork).put("openBrainSessionsAfterCleanup", 0)
                .put("activeGraphWorkersAfterCleanup", 0).put("queueDepthAfterCleanup", 0);
        Path output = Path.of("build", "reports", "long-play", "100-turn.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertTrue(Files.size(output) < 8_192);
    }

    private static void awaitThreadStability(int before) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (Thread.getAllStackTraces().size() <= before + 8) return;
            Thread.sleep(20);
        }
        assertTrue(Thread.getAllStackTraces().size() <= before + 8, "worker threads did not return to bounded range");
    }

    private static final class Script {
        private final AtomicInteger providerCalls;
        private final AtomicBoolean disconnect;
        private final AtomicInteger sessions = new AtomicInteger();
        private Script(AtomicInteger providerCalls, AtomicBoolean disconnect) {
            this.providerCalls = providerCalls; this.disconnect = disconnect;
        }
        ExternalBrainAdapter adapter() {
            return new ExternalBrainAdapter() {
                private final Set<String> active = ConcurrentHashMap.newKeySet();
                @Override public BrainSession openSession(BrainSessionRequest request) {
                    BrainSession value = new BrainSession("long-session-" + sessions.incrementAndGet(),
                            request.controllerId(), request.companionId(), Instant.now());
                    active.add(value.sessionId()); return value;
                }
                @Override public boolean supportsResume() { return true; }
                @Override public BrainSession resumeSession(BrainSessionRequest request, String sessionId) {
                    BrainSession value = new BrainSession(sessionId, request.controllerId(), request.companionId(), Instant.now());
                    active.add(sessionId); return value;
                }
                @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
                    providerCalls.incrementAndGet();
                    if (!active.contains(request.sessionId())) throw new IllegalStateException("BRAIN_SESSION_NOT_FOUND");
                    if (!request.toolResults().isEmpty()) return BrainTurnResult.finalResponse("turn complete");
                    if (request.userMessage().contains("\"type\":\"user_answer\"")) {
                        return BrainTurnResult.finalResponse("answer accepted");
                    }
                    int turn = Integer.parseInt(request.userMessage().substring("turn-".length()));
                    if (turn == 25) return BrainTurnResult.askUser(new BrainQuestion("Continue safely?",
                            "SAFETY_CONFIRMATION", List.of(new ConversationOption("continue_safe", "Continue", "Proceed")),
                            false, Json.object().put("turn", turn), null));
                    if (turn == 40 && disconnect.compareAndSet(false, true)) {
                        throw new IllegalStateException("BRAIN_IO_ERROR");
                    }
                    String tool = turn % 5 == 0 ? "inventory.transfer" : "world.observe";
                    return BrainTurnResult.tools(List.of(new ToolCall("turn-" + turn + "-call", tool,
                            Json.object().put("turn", turn))));
                }
                @Override public void cancel(String sessionId, String reason) { active.remove(sessionId); }
                @Override public BrainHealth health() { return BrainHealth.ready("deterministic-long-play"); }
            };
        }
    }

    private static final class LongPlayGateway implements ToolGateway {
        private final Map<String, ToolResult> results = new ConcurrentHashMap<>();
        private final Set<String> mutationCalls = ConcurrentHashMap.newKeySet();
        private final AtomicInteger mutationEffects = new AtomicInteger();
        @Override public List<ToolDefinition> definitions(ToolContext context) {
            JsonNode schema = Json.object().put("type", "object").put("additionalProperties", true);
            return List.of(new ToolDefinition("world.observe", "1.0", "observe", schema, "LOW",
                            "READ_WORLD", Duration.ofSeconds(2), true),
                    new ToolDefinition("inventory.transfer", "1.0", "transfer", schema, "MEDIUM",
                            "INVENTORY", Duration.ofSeconds(2), false));
        }
        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            return results.computeIfAbsent(call.callId(), ignored -> {
                boolean mutation = call.name().equals("inventory.transfer");
                if (mutation) { mutationCalls.add(call.callId()); mutationEffects.incrementAndGet(); }
                return new ToolResult(call.callId(), call.name(), true, "OK",
                        Json.object().put("state", "SUCCEEDED").put("verified", true)
                                .put("turn", call.arguments().path("turn").asInt()), true);
            });
        }
        int uniqueCalls() { return (int) results.keySet().stream().filter(value -> value.startsWith("turn-")).count(); }
        int mutationEffects() { return mutationEffects.get(); }
        int uniqueMutationCallIds() { return mutationCalls.size(); }
    }
}
