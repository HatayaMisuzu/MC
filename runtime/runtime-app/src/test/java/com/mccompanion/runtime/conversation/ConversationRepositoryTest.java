package com.mccompanion.runtime.conversation;

import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.agent.PlanStep;
import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.brain.BrainAuditRepository;
import com.mccompanion.runtime.brain.BrainSession;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ConversationRepositoryTest {
    @TempDir Path temporary;

    @Test
    void questionAndOptionsSurviveRepositoryRestartAndAnswerLinksOriginalPlan() throws Exception {
        Path path = temporary.resolve("conversation.db");
        String questionId;
        String planId;
        try (RuntimeDatabase database = new RuntimeDatabase(path)) {
            database.initialize();
            AgentPlanRepository plans = new AgentPlanRepository(database);
            AgentDecision decision = new AgentDecision(DecisionKind.CREATE_PLAN, "deliver iron", List.of(),
                    List.of(), List.of(new PlanStep("withdraw", "WithdrawFromStorage", Json.object(),
                    "delta", Json.object(), "ask", false, RiskLevel.LOW)), "", "");
            planId = plans.create("c1", "拿16个，不够就告诉我", decision).planId();
            ConversationRepository conversations = new ConversationRepository(database);
            WaitingQuestion question = conversations.ask("c1", planId,
                    "箱子里只有 6 个铁锭，还差 10 个。你想怎么做？", "RESOURCE_SHORTAGE",
                    List.of(new ConversationOption("deliver_partial", "先拿 6 个", "返回并交付现有数量"),
                            new ConversationOption("search_other", "看其他箱子", "检查其他已知容器"),
                            new ConversationOption("collect_missing", "去补齐", "扩大为资源采集任务")),
                    true, Json.object().put("available", 6).put("requested", 16), null);
            questionId = question.questionId();
        }

        try (RuntimeDatabase reopened = new RuntimeDatabase(path)) {
            reopened.initialize();
            ConversationRepository conversations = new ConversationRepository(reopened);
            WaitingQuestion recovered = conversations.activeForCompanion("c1").orElseThrow();
            assertEquals(questionId, recovered.questionId());
            assertEquals(planId, recovered.planId());
            assertEquals(3, recovered.options().size());
            WaitingQuestion answered = conversations.answer(questionId, "先把 6 个拿来", "deliver_partial");
            assertEquals("ANSWERED", answered.state());
            assertEquals("deliver_partial", answered.answer().path("optionId").asText());
            var events = conversations.list("c1", 10);
            assertEquals(List.of("QUESTION", "ANSWER"), events.stream().map(ConversationEvent::kind).toList());
        }
    }

    @Test
    void brainQuestionSurvivesRestartReusesIdentityAndConsumesAnswerOnce() throws Exception {
        Path path = temporary.resolve("brain-question.db");
        String questionId;
        try (RuntimeDatabase database = new RuntimeDatabase(path)) {
            database.initialize();
            new BrainAuditRepository(database).opened(
                    new BrainSession("brain_session_123", "controller", "c1", Instant.now()), "replay");
            ConversationRepository conversations = new ConversationRepository(database);
            WaitingQuestion first = conversations.askBrain("c1", "brain_session_123", null,
                    "Only 6 of 16 iron ingots are available. What should I do?", "RESOURCE_SHORTAGE",
                    List.of(new ConversationOption("deliver_partial", "Deliver 6", "Return and deliver what exists"),
                            new ConversationOption("collect_missing", "Collect 10", "Mine the missing amount")),
                    false, Json.object().put("available", 6).put("requested", 16), null);
            WaitingQuestion retried = conversations.askBrain("c1", "brain_session_123", null,
                    "This retry must not replace the durable question", "RESOURCE_SHORTAGE",
                    List.of(new ConversationOption("different", "Different", "Ignored retry")),
                    false, Json.object(), null);
            questionId = first.questionId();
            assertEquals(questionId, retried.questionId());
        }

        try (RuntimeDatabase reopened = new RuntimeDatabase(path)) {
            reopened.initialize();
            ConversationRepository conversations = new ConversationRepository(reopened);
            WaitingQuestion recovered = conversations.activeForBrainSession("brain_session_123").orElseThrow();
            assertEquals(questionId, recovered.questionId());
            assertNull(recovered.planId());
            assertEquals("brain_session_123", recovered.brainSessionId());
            WaitingQuestion answered = conversations.answer(questionId, "", "deliver_partial");
            WaitingQuestion duplicate = conversations.answer(questionId, "", "deliver_partial");
            assertEquals(answered, duplicate);
            assertThrows(IllegalStateException.class,
                    () -> conversations.answer(questionId, "", "collect_missing"));
            assertEquals(1, conversations.list("c1", 10).stream()
                    .filter(event -> event.kind().equals("ANSWER")).count());
        }
    }
}
