package com.mccompanion.runtime.agent;

import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.provider.DecisionProvider;
import com.mccompanion.runtime.provider.IntentProvider;
import com.mccompanion.runtime.provider.ProviderRouter;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentKernelReplanTest {
    @TempDir Path temporary;

    @Test
    void failedTaskObservationTriggersBoundedAsynchronousReplan() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("kernel.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("runtime.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                AgentPlanRepository plans = new AgentPlanRepository(database);
                AgentDecision initial = new AgentDecision(DecisionKind.CREATE_PLAN, "deliver iron", List.of(),
                        List.of(), List.of(step("NavigateTo")), "starting", "");
                DurablePlan plan = plans.create("c1", "deliver iron", initial);
                plan = plans.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
                plan = plans.linkTask(plan.planId(), plan.revision(), 0, "task-1");

                class TestProvider implements IntentProvider, DecisionProvider {
                    @Override public com.mccompanion.runtime.intent.Intent parse(String text) {
                        throw new AssertionError("replan must not use legacy intent parsing");
                    }
                    @Override public AgentDecision decide(com.mccompanion.runtime.provider.AgentRequest request) {
                        assertEquals("PATH_UNREACHABLE", request.context().activeTask().path("failureCode").asText());
                        assertTrue(request.context().activeTask().path("triggerObservation").path("blocked").asBoolean());
                        return new AgentDecision(DecisionKind.REPORT_BLOCKED, "deliver iron", List.of(), List.of(),
                                List.of(), "container unreachable", "verified path failure");
                    }
                }
                TestProvider provider = new TestProvider();
                ProviderRouter router = new ProviderRouter(new RuleIntentParser(), provider, log);
                try (AgentKernel kernel = new AgentKernel(plans, null, log, router, companions, sessions,
                        new CapabilityVisibility(CapabilityRegistry.standard()))) {
                    Instant now = Instant.now();
                    TaskRecord failed = new TaskRecord("task-1", "task-1", null, "c1", TaskType.TRAVEL,
                            TaskState.FAILED, 2, "deliver iron", Json.object(), "navigate", 1, 1,
                            false, now, now);
                    kernel.onTaskUpdated(failed,
                            Json.object().put("code", "PATH_UNREACHABLE").put("blocked", true));

                    DurablePlan replanned = awaitPlanningRevision(plans, plan.planId(), 1);
                    assertEquals(StepState.BLOCKED, replanned.state());
                    assertEquals(1, replanned.replanCount());
                    assertEquals("PATH_UNREACHABLE", replanned.steps().getFirst().failureCode());
                    assertTrue(replanned.steps().getFirst().observation().path("blocked").asBoolean());
                }
            }
        }
    }

    private static DurablePlan awaitPlanningRevision(AgentPlanRepository plans, String planId, int revision)
            throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            DurablePlan current = plans.get(planId).orElseThrow();
            if (current.planningRevision() >= revision) return current;
            Thread.sleep(25);
        }
        throw new AssertionError("Replan did not finish within 1.5 seconds");
    }

    private static PlanStep step(String capability) {
        return new PlanStep("advance", capability, Json.object(), "verified state change",
                Json.object().put("verified", true), "observe and replan", false, RiskLevel.LOW);
    }
}
