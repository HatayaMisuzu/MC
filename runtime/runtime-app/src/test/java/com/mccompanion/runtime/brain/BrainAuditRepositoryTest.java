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
            ToolCall call = new ToolCall("observe-1", "world.observe", Json.object());
            audit.tool(original.sessionId(), call, new ToolResult(call.callId(), call.name(), true,
                    "COMMAND_DISPATCHED", Json.object().put("state", "RUNNING")
                            .put("cursor", "fixture"), false));

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
                assertEquals("observe-1", request.toolResults().getFirst().callId());
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

    @Test
    void durableExecutionRemainsControllableAcrossRestartUntilVerifiedTerminalState() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("brain-durable-restart.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            BrainSession session = new BrainSession("durable-session-1", "controller", "c1", Instant.now());
            audit.opened(session, "replay");
            ToolCall start = new ToolCall("navigate-1", "movement.navigate", Json.object());
            audit.tool(session.sessionId(), start, new ToolResult(start.callId(), start.name(), true,
                    "COMMAND_DISPATCHED", Json.object().put("state", "RUNNING")
                            .put("taskId", "task-1"), false));

            assertEquals(1, audit.interruptActiveSessions());
            assertEquals(1, audit.activeDurableCalls().size());
            assertEquals("task-1", audit.activeDurableCalls().getFirst().handle().id());

            ToolCall inspect = new ToolCall("inspect-1", "task.inspect", Json.object());
            audit.tool(session.sessionId(), inspect, new ToolResult(inspect.callId(), inspect.name(), true,
                    "OK", Json.object().put("state", "COMPLETED").put("taskId", "task-1"), true));
            assertTrue(audit.activeDurableCalls().isEmpty(), "terminal task must not be recovered as active");
            assertTrue(audit.activeDurableCalls().isEmpty(), "repeated recovery inspection is idempotent");
        }
    }

    @Test
    void semanticStateIsSessionScopedVersionedAndVisibleInAudit() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("brain-semantic.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            BrainSession session = new BrainSession("semantic-session-1", "hermes", "c1", Instant.now());
            audit.opened(session, "hermes");
            BrainSemanticState initial = semantic("Mine safely", List.of("chest contents"));
            var first = audit.semanticState(session.sessionId(), "hermes", "c1", initial);
            var second = audit.semanticState(session.sessionId(), "hermes", "c1",
                    semantic("Return to owner", List.of()));

            assertEquals(1, first.revision());
            assertEquals(2, second.revision());
            assertEquals("Return to owner", audit.semanticState(session.sessionId()).state().currentTask());
            assertThrows(IllegalArgumentException.class, () ->
                    audit.semanticState(session.sessionId(), "other-controller", "c1", initial));
            var inspected = audit.inspect("c1", 10).path(0);
            assertEquals(2, inspected.path("semanticStateRevision").asLong());
            assertEquals("Return to owner", inspected.path("semanticState").path("currentTask").asText());
        }
    }

    @Test
    void behaviorSettingsDefaultToNormalCompanionAndRemainCompanionScoped() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("brain-behavior-settings.db"))) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(database);
            BrainBehaviorSettings defaults = audit.behaviorSettings("c1");
            assertEquals(BrainSemanticState.InitiativeMode.NORMAL, defaults.initiativeMode());
            assertEquals(BrainSemanticState.PersonalityMode.COMPANION, defaults.personalityMode());
            assertEquals(0, defaults.revision());

            BrainBehaviorSettings updated = audit.updateBehaviorSettings("c1",
                    BrainSemanticState.InitiativeMode.QUIET,
                    BrainSemanticState.PersonalityMode.IMMERSIVE_ROLEPLAY, "LOCAL_MANAGEMENT_USER");
            assertEquals(1, updated.revision());
            assertEquals(BrainSemanticState.InitiativeMode.QUIET, audit.behaviorSettings("c1").initiativeMode());
            assertEquals(BrainSemanticState.InitiativeMode.NORMAL, audit.behaviorSettings("c2").initiativeMode());
            assertFalse(updated.toJson().path("changesToolPermissions").asBoolean());
            assertFalse(updated.toJson().path("changesSafetyPolicy").asBoolean());
            assertFalse(updated.toJson().path("changesBudgets").asBoolean());
            assertFalse(updated.toJson().path("changesMemoryPolicy").asBoolean());
        }
    }

    private static BrainSemanticState semantic(String task, List<String> stale) {
        return new BrainSemanticState("Current conversation", "", task, "", "", false,
                BrainSemanticState.InitiativeMode.NORMAL, BrainSemanticState.PersonalityMode.COMPANION,
                BrainSemanticState.PermissionPreset.ASK_FOR_EFFECTS, false, null, stale);
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
