package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BrainAuditRepositoryTest {
    @TempDir Path temporary;

    @Test
    void persistsSessionAndDeduplicatedToolObservationAndInterruptsCrashRecovery() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("brain-audit.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            AtomicInteger turns = new AtomicInteger();
            ReplayBrainAdapter replay = new ReplayBrainAdapter(request -> turns.getAndIncrement() == 0
                    ? BrainTurnResult.tools(List.of(new ToolCall("observe-1", "world.observe", Json.object())))
                    : BrainTurnResult.finalResponse("done"));
            ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(replay, new ObserveGateway(), 4, audit);
            BrainCoordinatorResult result = coordinator.continueTurn("controller", "c1", "observe",
                    AgentContext.empty("c1", List.of()));
            assertEquals(1, audit.toolCount(result.sessionId()));
            assertEquals(1, audit.interruptActiveSessions());
            assertEquals(0, audit.interruptActiveSessions());
            coordinator.close();
        }
    }

    @Test
    void restartInterruptsUnfinishedToolAndResumesSameReplaySessionWithoutRedelivery() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("brain-resume.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            BrainSession original = new BrainSession("resume-session-1", "controller", "c1", Instant.now());
            audit.opened(original, "replay");
            ToolCall call = new ToolCall("navigate-1", "movement.navigate", Json.object());
            audit.tool(original.sessionId(), call, new ToolResult(call.callId(), call.name(), true,
                    "COMMAND_DISPATCHED", Json.object().put("state", "RUNNING")
                            .put("taskId", "task-1").put("behaviorId", "behavior-1"), false));

            assertEquals(1, audit.interruptActiveSessions());
            assertEquals(original.sessionId(), audit.interrupted("controller", "c1").orElseThrow().sessionId());
            List<ToolResult> interrupted = audit.undeliveredTerminal(original.sessionId());
            assertEquals(1, interrupted.size());
            assertEquals("INTERRUPTED", interrupted.getFirst().observation().path("state").asText());

            AtomicInteger turns = new AtomicInteger();
            ReplayBrainAdapter replay = new ReplayBrainAdapter(request -> {
                turns.incrementAndGet();
                assertEquals(original.sessionId(), request.sessionId());
                assertEquals(1, request.toolResults().size());
                assertEquals("navigate-1", request.toolResults().getFirst().callId());
                return BrainTurnResult.finalResponse("Recovered safely.");
            });
            try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(replay,
                    new ObserveGateway(), 4, audit)) {
                BrainCoordinatorResult result = coordinator.continueTurn("controller", "c1", "continue",
                        AgentContext.empty("c1", List.of()));
                assertEquals(original.sessionId(), result.sessionId());
                assertEquals(1, turns.get());
                assertTrue(audit.undeliveredTerminal(original.sessionId()).isEmpty());
            }
        }
    }

    private static final class ObserveGateway implements ToolGateway {
        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("world.observe", "1.0", "observe", Json.object(),
                    "LOW", "READ_WORLD", Duration.ofSeconds(1), true));
        }
        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            return new ToolResult(call.callId(), call.name(), true, "OK", Json.object().put("health", 20), true);
        }
    }
}
