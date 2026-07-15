package com.mccompanion.runtime.agent;

import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionValidatorTest {
    private final CapabilityRegistry registry = CapabilityRegistry.standard();
    private final DecisionValidator validator = new DecisionValidator(registry);
    private final AgentContext context = AgentContext.empty("companion-1", registry.names());

    @Test
    void acceptsReusableShortHorizonPlan() {
        AgentDecision decision = new AgentDecision(DecisionKind.CREATE_PLAN, "交付16个铁锭",
                List.of("不足时报告缺口"), List.of(), List.of(
                step("LocateKnownContainer"), step("WithdrawFromStorage"), step("DeliverItem")),
                "我会先检查基地储物，再把实际取得的铁锭交给你。", "");
        assertTrue(validator.validate(decision, context).valid());
    }

    @Test
    void rejectsUnknownCapabilityArbitraryCodeAndOverlongPlans() {
        PlanStep injection = new PlanStep("作弊", "NavigateTo", Json.object().put("script", "setBlock()"),
                "完成", Json.object().put("positionReached", true), "停止", false, RiskLevel.LOW);
        AgentDecision arbitrary = new AgentDecision(DecisionKind.CREATE_PLAN, "作弊", List.of(), List.of(),
                List.of(injection), "", "");
        assertFalse(validator.validate(arbitrary, context).valid());

        AgentDecision unknown = new AgentDecision(DecisionKind.CREATE_PLAN, "任务", List.of(), List.of(),
                List.of(step("Get16IronHandler")), "", "");
        assertTrue(validator.validate(unknown, context).errors().stream().anyMatch(v -> v.contains("unavailable")));
    }

    @Test
    void modelCannotDeclareCompletionWithoutEvidenceReference() {
        AgentDecision decision = new AgentDecision(DecisionKind.COMPLETE_CANDIDATE, "做好铁镐", List.of(),
                List.of(), List.of(), "已经做好了。", "");
        assertFalse(validator.validate(decision, context).valid());
    }

    private static PlanStep step(String capability) {
        return new PlanStep("推进任务", capability, Json.object(), "世界状态发生预期变化",
                Json.object().put("verified", true), "记录观察并重规划", false, RiskLevel.LOW);
    }
}
