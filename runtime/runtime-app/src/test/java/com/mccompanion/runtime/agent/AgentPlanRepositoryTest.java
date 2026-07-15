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

    @Test
    void persistsProviderBudgetAndAppliesSemanticRevisionWithoutReusingOldSteps() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("replan.db"))) {
            database.initialize();
            AgentPlanRepository repository = new AgentPlanRepository(database);
            DurablePlan plan = repository.create("c1", "deliver iron", decision());
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.BLOCKED,
                    Json.object().put("containerReachable", false), "CONTAINER_UNREACHABLE");

            plan = repository.reserveReplan(plan.planId(), plan.revision());
            assertEquals(1, plan.replanCount());
            AgentDecision replacement = replan("NavigateTo", "WithdrawFromStorage", "DeliverItem");
            plan = repository.applyReplan(plan.planId(), plan.revision(), replacement,
                    plan.steps().getFirst().observation(), "CONTAINER_UNREACHABLE");

            assertEquals(1, plan.planningRevision());
            assertEquals(StepState.READY, plan.state());
            assertEquals(2, plan.currentStep());
            assertEquals(StepState.CANCELLED, plan.steps().get(1).state());
            assertEquals("SUPERSEDED_BY_REPLAN", plan.steps().get(1).failureCode());
            assertEquals(StepState.READY, plan.steps().get(2).state());
            assertEquals(5, plan.steps().size());
        }
    }

    @Test
    void rejectsIdenticalReplanAsNoProgressAndKeepsPlanBlocked() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("loop.db"))) {
            database.initialize();
            AgentPlanRepository repository = new AgentPlanRepository(database);
            DurablePlan plan = repository.create("c1", "deliver iron", decision());
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.BLOCKED,
                    Json.object().put("inventoryDelta", 0), "NO_PROGRESS");
            plan = repository.reserveReplan(plan.planId(), plan.revision());
            AgentDecision identical = new AgentDecision(DecisionKind.REPLAN, plan.decision().understoodGoal(),
                    plan.decision().constraints(), List.of(), plan.decision().steps(), "retry", "retry");

            plan = repository.applyReplan(plan.planId(), plan.revision(), identical,
                    Json.object().put("inventoryDelta", 0), "NO_PROGRESS");

            assertEquals(StepState.BLOCKED, plan.state());
            assertEquals(1, plan.noProgressCount());
            assertEquals(2, plan.steps().size());
        }
    }

    @Test
    void enforcesDurableReplanCallBudget() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("budget.db"))) {
            database.initialize();
            AgentPlanRepository repository = new AgentPlanRepository(database);
            DurablePlan plan = repository.create("c1", "deliver iron", decision());
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.BLOCKED, Json.object(), "BLOCKED");
            for (int call = 0; call < AgentPlanRepository.MAX_REPLANS; call++) {
                plan = repository.reserveReplan(plan.planId(), plan.revision());
            }
            DurablePlan exhausted = plan;
            assertThrows(IllegalStateException.class,
                    () -> repository.reserveReplan(exhausted.planId(), exhausted.revision()));
        }
    }

    @Test
    void detectsNonConsecutivePlanOscillationFromRevisionHistory() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("oscillation.db"))) {
            database.initialize();
            AgentPlanRepository repository = new AgentPlanRepository(database);
            DurablePlan plan = repository.create("c1", "deliver iron", decision());
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
            plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.BLOCKED, Json.object(), "A_BLOCKED");
            plan = repository.reserveReplan(plan.planId(), plan.revision());
            plan = repository.applyReplan(plan.planId(), plan.revision(), replan("NavigateTo"), Json.object(), "A_BLOCKED");
            plan = repository.transitionStep(plan.planId(), plan.revision(), plan.currentStep(), StepState.RUNNING,
                    Json.object(), null);
            plan = repository.transitionStep(plan.planId(), plan.revision(), plan.currentStep(), StepState.BLOCKED,
                    Json.object(), "B_BLOCKED");
            plan = repository.reserveReplan(plan.planId(), plan.revision());
            AgentDecision returnToInitial = new AgentDecision(DecisionKind.REPLAN, decision().understoodGoal(),
                    decision().constraints(), List.of(), decision().steps(), "oscillate", "retry old route");

            plan = repository.applyReplan(plan.planId(), plan.revision(), returnToInitial, Json.object(), "B_BLOCKED");

            assertEquals(StepState.BLOCKED, plan.state());
            assertEquals(1, plan.noProgressCount());
            assertEquals(3, plan.steps().size());
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

    private static AgentDecision replan(String... capabilities) {
        return new AgentDecision(DecisionKind.REPLAN, "deliver iron", List.of(), List.of(),
                java.util.Arrays.stream(capabilities).map(AgentPlanRepositoryTest::step).toList(),
                "replanned", "world state changed");
    }
}
