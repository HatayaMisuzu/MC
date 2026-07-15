package com.mccompanion.runtime.conversation;

import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.agent.PlanStep;
import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
}
