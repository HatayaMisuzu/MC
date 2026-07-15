package com.mccompanion.runtime.conversation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.session.RuntimeSession;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.websocket.RuntimeWebSocketServer;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** One durable boundary for assistant speech, offline outbox delivery, and waiting questions. */
public final class ConversationService {
    private final ConversationRepository repository;
    private final SessionRegistry sessions;
    private final RuntimeLog log;

    public ConversationService(ConversationRepository repository, SessionRegistry sessions, RuntimeLog log) {
        this.repository = repository; this.sessions = sessions; this.log = log;
    }

    public WaitingQuestion ask(String companionId, String planId, String prompt, String reason,
                               List<ConversationOption> options, boolean freeTextAllowed,
                               com.fasterxml.jackson.databind.JsonNode context) throws SQLException {
        WaitingQuestion question = repository.ask(companionId, planId, prompt, reason, options,
                freeTextAllowed, context, null);
        deliverPending(companionId);
        return question;
    }

    public ConversationEvent say(String companionId, String planId, String kind, String content,
                                 com.fasterxml.jackson.databind.JsonNode payload) throws SQLException {
        ConversationEvent event = repository.append(companionId, planId, null,
                "ASSISTANT", kind, content, payload);
        deliverPending(companionId);
        return event;
    }

    public void deliverPending(String companionId) {
        RuntimeSession session = sessions.forCompanion(companionId).orElse(null);
        if (session == null || !session.peer().isOpen()) return;
        try {
            for (ConversationEvent event : repository.pendingGameDelivery(companionId, 20)) {
                ObjectNode payload = Json.object().put("eventId", event.eventId())
                        .put("companionId", event.companionId()).put("kind", event.kind())
                        .put("reply", event.content()).put("createdAt", event.createdAt().toString());
                if (event.planId() != null) payload.put("planId", event.planId());
                if (event.questionId() != null) payload.put("questionId", event.questionId());
                payload.set("details", event.payload());
                ObjectNode envelope = Json.object().put("protocol", RuntimeWebSocketServer.PROTOCOL)
                        .put("type", "conversation_event").put("sessionId", session.sessionId())
                        .put("worldId", session.handshake().worldId()).put("sequence", session.nextSequence())
                        .put("timestamp", Instant.now().toEpochMilli());
                envelope.set("payload", payload);
                session.peer().send(Json.write(envelope));
                repository.markGameDelivered(event.eventId());
            }
        } catch (SQLException | RuntimeException failure) {
            log.error("Conversation outbox delivery failed: companion=" + companionId, failure);
        }
    }

    public ConversationRepository repository() { return repository; }
}
