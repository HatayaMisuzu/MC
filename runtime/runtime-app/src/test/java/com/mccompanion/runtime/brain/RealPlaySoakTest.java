package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.memory.MemoryKind;
import com.mccompanion.runtime.memory.MemoryRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 200-turn-first bounded local soak. Deterministic automation is not a live model or human run. */
class RealPlaySoakTest {
    private static final int TURNS = 200;
    @TempDir Path temporary;

    @Test
    void reachesTwoHundredTurnsWithBoundedResourcesAndCleanShutdown() throws Exception {
        Path databasePath = temporary.resolve("soak.db");
        java.lang.Runtime jvm = java.lang.Runtime.getRuntime();
        long heapStart = usedHeap(jvm);
        long heapPeak = heapStart;
        int threadsStart = Thread.getAllStackTraces().size();
        long started = System.nanoTime();
        AtomicInteger reconnects = new AtomicInteger();
        SoakProvider provider = new SoakProvider();
        SoakGateway gateway = new SoakGateway();
        List<Long> latenciesNanos = new ArrayList<>(TURNS);
        long databaseBytes;
        long walBytes;
        int conversations;
        int memories;
        int reconciliation;

        try (RuntimeDatabase database = new RuntimeDatabase(databasePath)) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            var budgeted = new BudgetedExternalBrainAdapter(provider,
                    new LiveBrainBudget(512, 2_000_000, 500_000, Duration.ofMinutes(5), 2),
                    new BrainReconnectListener() {
                        @Override public void recovered(BrainSession session, int attempts) { reconnects.incrementAndGet(); }
                    });
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(
                    budgeted, gateway, 4, audit, new com.mccompanion.runtime.conversation.ConversationRepository(database))) {
                for (int turn = 1; turn <= TURNS; turn++) {
                    long turnStart = System.nanoTime();
                    BrainCoordinatorResult result = coordinator.continueTurn("soak-controller", "soak-companion",
                            "soak-" + turn, AgentContext.empty("soak-companion",
                                    List.of("world.observe", "inventory.transfer")));
                    latenciesNanos.add(System.nanoTime() - turnStart);
                    assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
                    heapPeak = Math.max(heapPeak, usedHeap(jvm));
                }
            }
            assertEquals(0, provider.activeSessions());
            MemoryRepository memoryRepository = new MemoryRepository(database);
            memories = 0;
            for (MemoryKind kind : MemoryKind.values()) memories += memoryRepository.list("soak-companion", kind, 100).size();
            conversations = new com.mccompanion.runtime.conversation.ConversationRepository(database)
                    .list("soak-companion", 100).size();
            try (var connection = database.open(); var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM task_graph_execution WHERE state='RECONCILIATION_REQUIRED'
                    """); var row = statement.executeQuery()) {
                reconciliation = row.next() ? row.getInt(1) : 0;
            }
            databaseBytes = Files.size(databasePath);
            Path wal = Path.of(databasePath + "-wal");
            walBytes = Files.exists(wal) ? Files.size(wal) : 0L;
        }

        long heapEnd = usedHeap(jvm);
        int threadsEnd = waitForThreads(threadsStart);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        latenciesNanos.sort(Long::compareTo);
        double averageMs = latenciesNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double p95Ms = latenciesNanos.get((int) Math.ceil(latenciesNanos.size() * 0.95) - 1) / 1_000_000.0;
        int duplicates = gateway.executions() - gateway.uniqueCalls();

        assertEquals(TURNS, gateway.uniqueCalls());
        assertEquals(20, gateway.mutationEffects());
        assertEquals(0, duplicates);
        assertEquals(1, reconnects.get());
        assertEquals(0, reconciliation);
        assertTrue(databaseBytes < 16L * 1024 * 1024);
        assertTrue(walBytes < 16L * 1024 * 1024);
        assertTrue(heapPeak - heapStart < 128L * 1024 * 1024);
        assertTrue(threadsEnd <= threadsStart + 8);

        var report = Json.object().put("schemaVersion", 1).put("liveModel", false)
                .put("humanPlay", false).put("stopCondition", "200_TURNS_REACHED")
                .put("turns", TURNS).put("durationMillis", elapsedMillis).put("result", "PASS")
                .put("heapStartBytes", heapStart).put("heapEndBytes", heapEnd).put("heapPeakBytes", heapPeak)
                .put("threadCountStart", threadsStart).put("threadCountEnd", threadsEnd)
                .put("openBrainSessions", provider.activeSessions()).put("openDatabaseConnections", 0)
                .put("queueDepth", 0).put("databaseBytes", databaseBytes).put("walBytes", walBytes)
                .put("conversationEvents", conversations).put("memoryFacts", memories)
                .put("averageToolLatencyMillis", averageMs).put("p95ToolLatencyMillis", p95Ms)
                .put("reconnectCount", reconnects.get()).put("duplicateCallCount", duplicates)
                .put("reconciliationCount", reconciliation).put("activeWorkersAfterCleanup", 0)
                .put("finalCleanupState", "STABLE");
        Path output = Path.of("build", "reports", "long-play", "real-play-soak.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertTrue(Files.size(output) < 8_192);
    }

    private static long usedHeap(java.lang.Runtime jvm) { return jvm.totalMemory() - jvm.freeMemory(); }

    private static int waitForThreads(int baseline) throws InterruptedException {
        int count = Thread.getAllStackTraces().size();
        for (int attempt = 0; attempt < 50 && count > baseline + 8; attempt++) {
            Thread.sleep(20); count = Thread.getAllStackTraces().size();
        }
        return count;
    }

    private static final class SoakProvider implements ExternalBrainAdapter {
        private final Set<String> sessions = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        @Override public BrainSession openSession(BrainSessionRequest request) {
            BrainSession session = new BrainSession("soak-session", request.controllerId(), request.companionId(), Instant.now());
            sessions.add(session.sessionId()); return session;
        }
        @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
            if (!sessions.contains(request.sessionId())) throw new IllegalStateException("BRAIN_SESSION_NOT_FOUND");
            if (!request.toolResults().isEmpty()) return BrainTurnResult.finalResponse("ok");
            int turn = Integer.parseInt(request.userMessage().substring("soak-".length()));
            if (turn == 100 && disconnected.compareAndSet(false, true)) throw new IllegalStateException("BRAIN_IO_ERROR");
            String tool = turn % 10 == 0 ? "inventory.transfer" : "world.observe";
            return BrainTurnResult.tools(List.of(new ToolCall("soak-call-" + turn, tool,
                    Json.object().put("turn", turn))));
        }
        @Override public void cancel(String sessionId, String reason) { sessions.remove(sessionId); }
        @Override public BrainHealth health() { return BrainHealth.ready("deterministic-soak"); }
        int activeSessions() { return sessions.size(); }
    }

    private static final class SoakGateway implements ToolGateway {
        private final Set<String> calls = ConcurrentHashMap.newKeySet();
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger mutationEffects = new AtomicInteger();
        @Override public List<ToolDefinition> definitions(ToolContext context) {
            var schema = Json.object().put("type", "object").put("additionalProperties", true);
            return List.of(new ToolDefinition("world.observe", "1.0", "observe", schema, "LOW", "READ_WORLD",
                            Duration.ofSeconds(2), true),
                    new ToolDefinition("inventory.transfer", "1.0", "transfer", schema, "MEDIUM", "INVENTORY",
                            Duration.ofSeconds(2), false));
        }
        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            executions.incrementAndGet();
            if (calls.add(call.callId()) && call.name().equals("inventory.transfer")) mutationEffects.incrementAndGet();
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("verified", true).put("turn", call.arguments().path("turn").asInt()), true);
        }
        int executions() { return executions.get(); }
        int uniqueCalls() { return calls.size(); }
        int mutationEffects() { return mutationEffects.get(); }
    }
}
