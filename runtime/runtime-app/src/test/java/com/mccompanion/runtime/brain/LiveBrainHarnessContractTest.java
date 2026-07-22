package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LiveBrainHarnessContractTest {
    @Test void missingCredentialIsExplicitAndNeverFallsBackToReplay() {
        Map<String, String> env = new HashMap<>();
        env.put("MCAC_LIVE_BRAIN_ENABLED", "true");
        env.put("MCAC_LIVE_BRAIN_TOKEN_ENV", "TEST_LIVE_SECRET");
        var report = LiveBrainHarnessContract.preflight(env, Instant.parse("2026-07-22T00:00:00Z"));
        assertEquals("BLOCKED_BY_CREDENTIALS", report.path("status").asText());
        assertTrue(report.path("liveModel").asBoolean());
        assertEquals(3, report.path("scenarios").size());
        assertFalse(report.toString().contains("Replay"));
        assertFalse(report.toString().contains("secret-value"));
    }

    @Test void reportContainsOnlyCredentialSourceAndBoundedMetadata() {
        Map<String, String> env = new HashMap<>();
        env.put("MCAC_LIVE_BRAIN_ENABLED", "true");
        env.put("MCAC_LIVE_BRAIN_TOKEN_ENV", "TEST_LIVE_SECRET");
        env.put("TEST_LIVE_SECRET", "secret-value-never-report");
        env.put("MCAC_LIVE_BRAIN_MODEL", "provider-model");
        var report = LiveBrainHarnessContract.preflight(env, Instant.EPOCH);
        assertEquals("READY_FOR_LIVE_WORLD", report.path("status").asText());
        assertEquals("environment:TEST_LIVE_SECRET", report.path("credentialSource").asText());
        assertFalse(report.toString().contains("secret-value-never-report"));
        assertEquals(24, report.path("budget").path("maxRequests").asInt());
    }

    @Test void aggregateAdapterEnforcesRequestTokensRetriesAndCancellation() {
        AtomicInteger attempts = new AtomicInteger();
        ExternalBrainAdapter flaky = new ExternalBrainAdapter() {
            @Override public BrainSession openSession(BrainSessionRequest request) {
                return new BrainSession("session-123", request.controllerId(), request.companionId(), Instant.EPOCH);
            }
            @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("BRAIN_IO_ERROR");
                return BrainTurnResult.finalResponse("ok");
            }
            @Override public void cancel(String sessionId, String reason) { }
            @Override public BrainHealth health() { return new BrainHealth("OK", "test", "safe", Instant.EPOCH); }
        };
        var bounded = new BudgetedExternalBrainAdapter(flaky,
                new LiveBrainBudget(4, 2_000, 256, Duration.ofMinutes(1), 1));
        var context = AgentContext.empty("companion", List.of());
        BrainSession session = bounded.openSession(new BrainSessionRequest("controller", "companion", context,
                List.of(new ToolDefinition("world.observe", "1", "read", Json.object().put("type", "object"),
                        "LOW", "READ_WORLD", Duration.ofSeconds(1), true))));
        assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, bounded.continueTurn(new BrainTurnRequest(
                session.sessionId(), "observe", context, List.of(), 2)).kind());
        assertEquals(3, bounded.usage().requests());
        assertEquals(1, bounded.usage().retries());
        assertEquals("ESTIMATED", bounded.usage().tokenAccounting());
        bounded.cancel(session.sessionId(), "USER_CANCELLED");
        LiveBrainBudgetException cancelled = assertThrows(LiveBrainBudgetException.class,
                () -> bounded.continueTurn(new BrainTurnRequest(session.sessionId(), "again", context, List.of(), 1)));
        assertEquals(LiveBrainFailureCategory.USER_CANCELLED, cancelled.category());
    }
}
