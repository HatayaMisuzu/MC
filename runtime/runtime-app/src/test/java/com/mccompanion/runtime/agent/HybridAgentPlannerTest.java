package com.mccompanion.runtime.agent;

import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.provider.DecisionProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HybridAgentPlannerTest {
    private final CapabilityRegistry capabilities = CapabilityRegistry.standard();

    @Test
    void safetyControlFastPathDoesNotWaitForModel() {
        AtomicInteger calls = new AtomicInteger();
        DecisionProvider provider = request -> { calls.incrementAndGet(); throw new AssertionError("must not call model"); };
        var result = new HybridAgentPlanner(provider).decide("  暂停  ", context());
        assertTrue(result.accepted());
        assertEquals(DecisionKind.PAUSE, result.decision().kind());
        assertEquals("fast_path", result.source());
        assertEquals(0, calls.get());
    }

    @Test
    void modelPlansFromContextInsteadOfReturningLegacyIntent() {
        DecisionProvider provider = request -> {
            assertEquals(16, request.input().quantity().orElseThrow());
            assertEquals("ACQUIRE_AND_DELIVER", request.hints().possibleIntent());
            assertEquals(3, request.context().maxPlanSteps());
            return new AgentDecision(DecisionKind.CREATE_PLAN, "从基地箱子交付16个铁锭",
                    List.of("允许部分交付"), List.of(), List.of(
                    step("LocateKnownContainer"), step("WithdrawFromStorage"), step("DeliverItem")),
                    "我会先核对基地箱子的实际库存，不足时告诉你缺口。", "");
        };
        var result = new HybridAgentPlanner(provider).decide("去基地箱子拿16个铁锭给我，不够就说还差多少", context());
        assertTrue(result.accepted(), result.userMessage());
        assertEquals(3, result.decision().steps().size());
        assertTrue(result.executableIntent().isEmpty());
    }

    @Test
    void missingOrFailingProviderNeverTurnsComplexRequestIntoRuleGuess() {
        var missing = new HybridAgentPlanner(null).decide("去那边看看", context());
        assertEquals("PROVIDER_UNAVAILABLE", missing.errorCode());
        assertEquals(DecisionKind.ASK_CLARIFICATION, missing.decision().kind());
    }

    @Test
    void replanBypassesFreshCommandFastPathAndRequiresRevisionDecision() {
        AtomicInteger calls = new AtomicInteger();
        DecisionProvider provider = request -> {
            calls.incrementAndGet();
            assertEquals("CONTAINER_UNREACHABLE",
                    request.context().activeTask().path("failureCode").asText());
            return new AgentDecision(DecisionKind.REPLAN, "deliver iron", List.of(), List.of(),
                    List.of(step("NavigateTo")), "改走另一条路", "container unreachable");
        };
        AgentContext blocked = new AgentContext("c1", Json.object(), List.of(),
                Json.object().put("failureCode", "CONTAINER_UNREACHABLE"), List.of(),
                capabilities.names(), 3);

        var result = new HybridAgentPlanner(provider).replan("暂停", blocked);

        assertTrue(result.accepted(), result.userMessage());
        assertEquals(DecisionKind.REPLAN, result.decision().kind());
        assertEquals("provider_replan", result.source());
        assertEquals(1, calls.get());
    }

    @Test
    void replanRejectsProviderAttemptToStartAnUnrelatedFreshPlan() {
        DecisionProvider provider = request -> new AgentDecision(DecisionKind.CREATE_PLAN, "other goal",
                List.of(), List.of(), List.of(step("NavigateTo")), "", "");

        var result = new HybridAgentPlanner(provider).replan("deliver iron", context());

        assertFalse(result.accepted());
        assertEquals("REPLAN_DECISION_REQUIRED", result.errorCode());
    }

    private AgentContext context() {
        return new AgentContext("c1", Json.object().put("health", 20), List.of(), Json.object(),
                List.of("基地"), capabilities.names(), 3);
    }

    private static PlanStep step(String capability) {
        return new PlanStep("推进", capability, Json.object(), "状态变化", Json.object().put("verified", true),
                "报告并重规划", false, RiskLevel.LOW);
    }
}
