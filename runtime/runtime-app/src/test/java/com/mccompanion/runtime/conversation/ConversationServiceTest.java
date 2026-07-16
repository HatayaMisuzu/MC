package com.mccompanion.runtime.conversation;

import com.mccompanion.protocol.CapabilitySet;
import com.mccompanion.protocol.CompanionBodyState;
import com.mccompanion.protocol.CompanionStatus;
import com.mccompanion.protocol.PositionDto;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.agent.PlanStep;
import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.Handshake;
import com.mccompanion.runtime.session.SessionPeer;
import com.mccompanion.runtime.session.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationServiceTest {
    @TempDir Path temporary;

    @Test
    void offlineQuestionIsDeliveredToGameWhenCompanionReconnects() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("delivery.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("runtime.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                ConversationRepository repository = new ConversationRepository(database);
                ConversationService service = new ConversationService(repository, sessions, log);
                String planId = new AgentPlanRepository(database).create("c1", "拿16个", new AgentDecision(
                        DecisionKind.CREATE_PLAN, "拿铁", List.of(), List.of(), List.of(new PlanStep("取物",
                        "WithdrawFromStorage", Json.object(), "库存变化", Json.object(), "询问", false,
                        RiskLevel.LOW)), "", "")).planId();
                service.ask("c1", planId, "箱子里只有 6 个，还差 10 个。你想怎么做？", "RESOURCE_SHORTAGE",
                        List.of(new ConversationOption("partial", "先拿 6 个", "交付现有数量")), true,
                        Json.object().put("available", 6));
                assertFalse(repository.list("c1", 10).getFirst().gameDelivered());

                CapturingPeer peer = new CapturingPeer();
                var session = sessions.register(peer, new Handshake("mc-companion/1", "test", "1.21.1",
                        "fabric", "world", Json.object()));
                CompanionStatus status = new CompanionStatus("c1", "owner-1", "Misuzu", "world",
                        "minecraft:overworld", new PositionDto(0, 64, 0), CompanionBodyState.SPAWNED,
                        null, null, 0, 0, true, CapabilitySet.empty(), Instant.now());
                sessions.registerCompanion(session, status, Json.object().put("bodyState", "spawned"));
                service.deliverPending("c1");

                assertEquals(1, peer.messages.size());
                var envelope = Json.parse(peer.messages.getFirst());
                assertEquals("conversation_event", envelope.path("type").asText());
                assertTrue(envelope.path("payload").path("reply").asText().contains("6"));
                assertFalse(repository.list("c1", 10).getFirst().gameDelivered());
                service.acknowledgeGameDelivery("c1", envelope.path("payload").path("eventId").asText());
                assertTrue(repository.list("c1", 10).getFirst().gameDelivered());
            }
        }
    }

    @Test
    void ordinaryChatIsDurableBoundedAndKeepsNaturalOrder() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("chat.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("chat.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                ConversationRepository repository = new ConversationRepository(database);
                ConversationService service = new ConversationService(repository, sessions, log);

                service.hear("c1", null, "MESSAGE", "今天有点累，不想冒险。",
                        Json.object().put("channel", "GAME"));
                ConversationEvent reply = service.recordDirectReply("c1", null, "CHAT",
                        "那我们今天轻松一点，可以整理箱子或在家附近看看。",
                        Json.object().put("decision", "RESPOND"));
                service.markDirectReplyDelivered(reply.eventId());

                assertEquals(List.of(
                        "USER: 今天有点累，不想冒险。",
                        "ASSISTANT: 那我们今天轻松一点，可以整理箱子或在家附近看看。"),
                        service.recentTranscript("c1", 12));
                assertTrue(repository.list("c1", 12).getLast().gameDelivered());
            }
        }
    }

    private static final class CapturingPeer implements SessionPeer {
        private final List<String> messages = new ArrayList<>();
        @Override public String id() { return "peer"; }
        @Override public String remoteAddress() { return "loopback"; }
        @Override public boolean isOpen() { return true; }
        @Override public void send(String text) { messages.add(text); }
        @Override public void close(int code, String reason) { }
    }
}
