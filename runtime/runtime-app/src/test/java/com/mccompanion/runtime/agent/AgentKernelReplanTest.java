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
import com.mccompanion.runtime.conversation.ConversationRepository;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.conversation.ConversationOption;
import com.mccompanion.runtime.conversation.IncomingMessageKind;
import com.mccompanion.runtime.conversation.IncomingMessageResolution;
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

    @Test
    void explicitShortageConstraintCreatesVisibleDurableQuestionWithoutCallingProvider() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("shortage.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("shortage.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                AgentPlanRepository plans = new AgentPlanRepository(database);
                PlanStep withdraw = new PlanStep("withdraw iron", "WithdrawFromStorage",
                        Json.object().put("item", "minecraft:iron_ingot").put("quantity", 16),
                        "inventory delta", Json.object(), "ask on shortage", false, RiskLevel.LOW);
                DurablePlan plan = plans.create("c1", "去箱子拿16个铁锭，不够就告诉我", new AgentDecision(
                        DecisionKind.CREATE_PLAN, "deliver iron", List.of(), List.of(), List.of(withdraw), "", ""));
                plan = plans.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
                plan = plans.linkTask(plan.planId(), plan.revision(), 0, "shortage-task");
                class NoCallProvider implements IntentProvider, DecisionProvider {
                    @Override public com.mccompanion.runtime.intent.Intent parse(String text) { throw new AssertionError(); }
                    @Override public AgentDecision decide(com.mccompanion.runtime.provider.AgentRequest request) {
                        throw new AssertionError("explicit shortage must wait for the owner before provider replanning");
                    }
                }
                ProviderRouter router = new ProviderRouter(new RuleIntentParser(), new NoCallProvider(), log);
                ConversationRepository repository = new ConversationRepository(database);
                ConversationService conversation = new ConversationService(repository, sessions, log);
                try (AgentKernel kernel = new AgentKernel(plans, null, log, router, companions, sessions,
                        new CapabilityVisibility(CapabilityRegistry.standard()), null, conversation)) {
                    Instant now = Instant.now();
                    var fabricSnapshot = Json.object().put("failureCode", "ITEM_INSUFFICIENT")
                            .put("available", 6).put("requested", 16);
                    kernel.onTaskUpdated(new TaskRecord("shortage-task", "shortage-task", null, "c1",
                                    TaskType.SKILL, TaskState.FAILED, 2, "withdraw", Json.object(), "withdraw", 1,
                                    1, false, now, now),
                            Json.object().put("event", "BLOCKED").set("snapshot", fabricSnapshot));

                    var question = awaitQuestion(repository, "c1");
                    assertEquals(plan.planId(), question.planId());
                    assertTrue(question.prompt().contains("6"));
                    assertTrue(question.prompt().contains("10"));
                    assertEquals(List.of("deliver_partial", "search_other", "collect_missing"),
                            question.options().stream().map(value -> value.id()).toList());
                    assertEquals("WAITING_FOR_USER", plans.get(plan.planId()).orElseThrow().interactionState());
                }
            }
        }
    }

    @Test
    void partialAnswerResumesTheSamePlanWithVerifiedQuantityWithoutProvider() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("partial-answer.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("partial-answer.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                AgentPlanRepository plans = new AgentPlanRepository(database);
                PlanStep withdraw = new PlanStep("withdraw iron", "WithdrawFromStorage",
                        Json.object().put("item", "minecraft:iron_ingot").put("quantity", 16),
                        "inventory delta", Json.object(), "ask on shortage", false, RiskLevel.LOW);
                PlanStep deliver = new PlanStep("deliver iron", "DeliverItem",
                        Json.object().put("item", "minecraft:iron_ingot").put("quantity", 16),
                        "owner inventory delta", Json.object(), "report failure", false, RiskLevel.LOW);
                DurablePlan plan = plans.create("c1", "get 16 iron; tell me if short", new AgentDecision(
                        DecisionKind.CREATE_PLAN, "deliver iron", List.of(), List.of(),
                        List.of(withdraw, deliver), "", ""));
                plan = plans.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING, Json.object(), null);
                plan = plans.transitionStep(plan.planId(), plan.revision(), 0, StepState.BLOCKED,
                        Json.object().put("available", 6).put("requested", 16), "ITEM_INSUFFICIENT");
                plan = plans.markWaitingForUser(plan.planId(), plan.revision());
                String originalPlanId = plan.planId();

                ConversationRepository repository = new ConversationRepository(database);
                ConversationService conversation = new ConversationService(repository, sessions, log);
                var question = repository.ask("c1", plan.planId(),
                        "这个来源里只有 6 个，目标是 16 个，还差 10 个。你想怎么做？", "shortage",
                        List.of(new ConversationOption("deliver_partial", "先把现有的拿来", ""),
                                new ConversationOption("search_other", "看看其他来源", "")), true,
                        Json.object().put("available", 6).put("requested", 16), null);

                try (AgentKernel kernel = new AgentKernel(plans, null, log, null, companions, sessions,
                        new CapabilityVisibility(CapabilityRegistry.standard()), null, conversation)) {
                    DurablePlan resumed = kernel.resumeWaitingAnswer(question,
                            new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER,
                                    "deliver_partial", "先把 6 个拿来"));

                    assertEquals(originalPlanId, resumed.planId());
                    assertEquals(StepState.READY, resumed.state());
                    assertEquals("ACTIVE", resumed.interactionState());
                    assertEquals(6, resumed.steps().get(resumed.currentStep()).definition()
                            .parameters().path("quantity").asInt());
                    assertEquals(6, resumed.steps().get(resumed.currentStep() + 1).definition()
                            .parameters().path("quantity").asInt());
                    assertTrue(repository.activeForCompanion("c1").isEmpty());
                    assertTrue(repository.list("c1", 20).stream().anyMatch(event ->
                            event.kind().equals("ANSWER") && event.planId().equals(originalPlanId)));
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

    private static com.mccompanion.runtime.conversation.WaitingQuestion awaitQuestion(
            ConversationRepository repository, String companionId) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            var question = repository.activeForCompanion(companionId);
            if (question.isPresent()) return question.get();
            Thread.sleep(25);
        }
        throw new AssertionError("Question did not become durable within 1.5 seconds");
    }

    private static PlanStep step(String capability) {
        return new PlanStep("advance", capability, Json.object(), "verified state change",
                Json.object().put("verified", true), "observe and replan", false, RiskLevel.LOW);
    }
}
