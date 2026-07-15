package com.mccompanion.runtime.agent;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentPlanRepositoryTest {
    @TempDir Path temporary;

    @Test
    void persistsStepsObservationsAndAdvancesOnlyAfterVerifiedSuccess() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("runtime.db"))) {
            database.initialize();
            AgentPlanRepository repository = new AgentPlanRepository(database);
            DurablePlan plan = repository.create("c1", "准备铁镐", decision());
            assertEquals(StepState.READY, plan.steps().getFirst().state());
            assertEquals(StepState.PENDING, plan.steps().get(1).state());

            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
            assertEquals(1, plan.steps().getFirst().attempt());
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.SUCCEEDED,
                    Json.object().put("inventoryDelta", 3).put("verified", true), null);
            assertEquals(StepState.READY, plan.state());
            assertEquals(1, plan.currentStep());
            assertEquals(StepState.READY, plan.steps().get(1).state());
            assertTrue(plan.steps().getFirst().observation().path("verified").asBoolean());
        }
    }

    @Test
    void restartPausesInFlightStepInsteadOfClaimingCompletion() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("restart.db"))) {
            database.initialize();
            AgentPlanRepository repository = new AgentPlanRepository(database);
            DurablePlan plan = repository.create("c1", "准备铁镐", decision());
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
            assertEquals(1, repository.pauseRunningForRecovery());
            DurablePlan recovered = repository.get(plan.planId()).orElseThrow();
            assertEquals(StepState.PAUSED, recovered.state());
            assertEquals("RECOVERY_REQUIRED", recovered.steps().getFirst().failureCode());
        }
    }

    @Test
    void rejectsStaleRevisionAndInvalidTerminalTransition() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("revision.db"))) {
            database.initialize();
            AgentPlanRepository repository = new AgentPlanRepository(database);
            DurablePlan plan = repository.create("c1", "准备铁镐", decision());
            assertThrows(IllegalStateException.class, () -> repository.transitionStep(
                    plan.planId(), 99, 0, StepState.RUNNING, Json.object(), null));
            assertThrows(IllegalStateException.class, () -> repository.transitionStep(
                    plan.planId(), plan.revision(), 0, StepState.SUCCEEDED, Json.object(), null));
        }
    }

    private static AgentDecision decision() {
        return new AgentDecision(DecisionKind.CREATE_PLAN, "准备铁镐", List.of(), List.of(), List.of(
                step("WithdrawFromStorage"), step("CraftItem")), "我先核对材料。", "");
    }

    private static PlanStep step(String capability) {
        return new PlanStep("推进", capability, Json.object(), "真实状态变化",
                Json.object().put("verified", true), "记录失败并重规划", false, RiskLevel.LOW);
    }
}
