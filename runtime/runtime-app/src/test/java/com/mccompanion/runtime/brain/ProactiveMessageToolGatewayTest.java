package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.conversation.ConversationRepository;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ProactiveMessageToolGatewayTest {
    @TempDir Path temporary;

    @Test
    void queuesOnlyEvidenceBoundMeaningfulMessageAndSuppressesDuplicateAndRate() throws Exception {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("proactive.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("runtime.log"), false, new Redactor())) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(
                    database, Clock.fixed(now, ZoneOffset.UTC));
            BrainSession session = new BrainSession("brain-session", "controller", "c1", now);
            audit.opened(session, "replay-test");
            audit.tool(session.sessionId(), new ToolCall("blocked-1", "task.inspect", Json.object()),
                    new ToolResult("blocked-1", "task.inspect", false, "TASK_BLOCKED",
                            Json.object().put("state", "BLOCKED"), true));
            audit.tool(session.sessionId(), new ToolCall("milestone-1", "task.inspect", Json.object()),
                    new ToolResult("milestone-1", "task.inspect", true, "OK",
                            Json.object().put("state", "RUNNING"), true));

            ConversationRepository conversationRepository = new ConversationRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(
                    database, new CompanionRepository(database), log)) {
                ProactiveMessageToolGateway gateway = new ProactiveMessageToolGateway(
                        audit, new ProactiveMessageRepository(
                                database, Clock.fixed(now, ZoneOffset.UTC)),
                        new ConversationService(conversationRepository, sessions, log));
                ToolContext context = new ToolContext("controller", session.sessionId(), "c1");
                ToolCall first = proposal("proposal-1", "TASK_BLOCKED", "blocked-1",
                        "I am blocked and need your choice.");
                ToolResult admitted = gateway.execute(context, first);
                assertTrue(admitted.success(), admitted.observation().toString());
                assertEquals("PROACTIVE_MESSAGE_QUEUED", admitted.code());
                assertEquals(1, conversationRepository.list("c1", 10).size());
                assertEquals("PROACTIVE", conversationRepository.list("c1", 10).getFirst().kind());

                ToolResult duplicate = gateway.execute(context, proposal(
                        "proposal-2", "TASK_BLOCKED", "blocked-1", "A duplicate wording."));
                assertFalse(duplicate.success());
                assertEquals("PROACTIVE_DUPLICATE_EVENT", duplicate.code());

                ToolResult rateLimited = gateway.execute(context, proposal(
                        "proposal-3", "TASK_MILESTONE", "milestone-1", "A milestone happened."));
                assertFalse(rateLimited.success());
                assertEquals("PROACTIVE_RATE_LIMITED", rateLimited.code());
                assertEquals(1, conversationRepository.list("c1", 10).size());
            }
        }
    }

    @Test
    void quietModeSuppressesMilestonesAndRepositoryAllowsActiveModeAfterItsWindow() throws Exception {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("mode.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("mode.log"), false, new Redactor())) {
            database.initialize();
            BrainAuditRepository audit = new BrainAuditRepository(
                    database, Clock.fixed(now, ZoneOffset.UTC));
            BrainSession session = new BrainSession("brain-session", "controller", "c1", now);
            audit.opened(session, "replay-test");
            audit.updateBehaviorSettings("c1", BrainSemanticState.InitiativeMode.QUIET,
                    BrainSemanticState.PersonalityMode.COMPANION, "LOCAL_MANAGEMENT_USER");
            audit.tool(session.sessionId(), new ToolCall("milestone-1", "task.inspect", Json.object()),
                    new ToolResult("milestone-1", "task.inspect", true, "OK",
                            Json.object().put("state", "RUNNING"), true));
            ConversationRepository conversations = new ConversationRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(
                    database, new CompanionRepository(database), log)) {
                ProactiveMessageToolGateway gateway = new ProactiveMessageToolGateway(
                        audit, new ProactiveMessageRepository(database,
                        Clock.fixed(now, ZoneOffset.UTC)),
                        new ConversationService(conversations, sessions, log));
                ToolResult suppressed = gateway.execute(
                        new ToolContext("controller", session.sessionId(), "c1"),
                        proposal("quiet", "TASK_MILESTONE", "milestone-1", "Still working."));
                assertEquals("PROACTIVE_MODE_SUPPRESSED", suppressed.code());
            }

            ProactiveMessageRepository first = new ProactiveMessageRepository(
                    database, Clock.fixed(now, ZoneOffset.UTC));
            assertTrue(first.admit("c2", "s2", "e1", "TASK_MILESTONE", "hash1",
                    "ACTIVE", Duration.ofSeconds(15)).admitted());
            ProactiveMessageRepository later = new ProactiveMessageRepository(
                    database, Clock.fixed(now.plusSeconds(16), ZoneOffset.UTC));
            assertTrue(later.admit("c2", "s2", "e2", "TASK_MILESTONE", "hash2",
                    "ACTIVE", Duration.ofSeconds(15)).admitted());
        }
    }

    private static ToolCall proposal(String callId, String eventType, String evidenceCallId,
                                     String message) {
        return new ToolCall(callId, "conversation.propose_proactive",
                Json.object().put("eventType", eventType)
                        .put("evidenceCallId", evidenceCallId).put("message", message));
    }
}
