package com.mccompanion.runtime.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable conversation outbox and user-choice state. */
public final class ConversationRepository {
    private final RuntimeDatabase database;
    private final Clock clock;

    public ConversationRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    ConversationRepository(RuntimeDatabase database, Clock clock) { this.database = database; this.clock = clock; }

    public ConversationEvent append(String companionId, String planId, String questionId,
                                    String direction, String kind, String content, JsonNode payload) throws SQLException {
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        try (Connection connection = database.open()) {
            insertEvent(connection, id, companionId, planId, questionId, direction, kind, content, payload, now);
        }
        return event(id).orElseThrow();
    }

    public WaitingQuestion ask(String companionId, String planId, String prompt, String reason,
                               List<ConversationOption> options, boolean freeTextAllowed,
                               JsonNode context, Instant expiresAt) throws SQLException {
        if (options == null || options.isEmpty() || options.size() > 3) {
            throw new IllegalArgumentException("A waiting question requires 1..3 options");
        }
        String questionId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO waiting_question(question_id,plan_id,companion_id,prompt,reason,options_json,
                          free_text_allowed,state,context_json,answer_json,created_at,updated_at,expires_at)
                        VALUES(?,?,?,?,?,?,?,'WAITING',?,NULL,?,?,?)
                        """)) {
                    statement.setString(1, questionId); statement.setString(2, required(planId));
                    statement.setString(3, required(companionId)); statement.setString(4, required(prompt));
                    statement.setString(5, required(reason));
                    statement.setString(6, Json.write(Json.MAPPER.valueToTree(options)));
                    statement.setInt(7, freeTextAllowed ? 1 : 0);
                    statement.setString(8, Json.write(context == null ? Json.object() : context));
                    statement.setLong(9, now); statement.setLong(10, now);
                    if (expiresAt == null) statement.setNull(11, java.sql.Types.BIGINT);
                    else statement.setLong(11, expiresAt.toEpochMilli());
                    statement.executeUpdate();
                }
                JsonNode payload = Json.object().put("reason", reason).put("freeTextAllowed", freeTextAllowed)
                        .set("options", Json.MAPPER.valueToTree(options));
                insertEvent(connection, eventId, companionId, planId, questionId,
                        "ASSISTANT", "QUESTION", prompt, payload, now);
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        }
        return waiting(questionId).orElseThrow();
    }

    public WaitingQuestion answer(String questionId, String text, String optionId) throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                WaitingQuestion question = waiting(connection, questionId).orElseThrow(
                        () -> new IllegalArgumentException("QUESTION_NOT_FOUND"));
                if (!question.state().equals("WAITING")) throw new IllegalStateException("QUESTION_NOT_WAITING");
                boolean knownOption = optionId != null && question.options().stream().anyMatch(v -> v.id().equals(optionId));
                if (!knownOption && (!question.freeTextAllowed() || text == null || text.isBlank())) {
                    throw new IllegalArgumentException("ANSWER_NOT_ALLOWED");
                }
                JsonNode answer = Json.object().put("text", text == null ? "" : text.strip())
                        .put("optionId", knownOption ? optionId : "");
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE waiting_question SET state='ANSWERED',answer_json=?,updated_at=?
                        WHERE question_id=? AND state='WAITING'
                        """)) {
                    update.setString(1, Json.write(answer)); update.setLong(2, now); update.setString(3, questionId);
                    if (update.executeUpdate() != 1) throw new IllegalStateException("QUESTION_NOT_WAITING");
                }
                insertEvent(connection, UUID.randomUUID().toString(), question.companionId(), question.planId(),
                        questionId, "USER", "ANSWER", text == null ? optionId : text, answer, now);
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        }
        return waiting(questionId).orElseThrow();
    }

    public WaitingQuestion cancel(String questionId, String reason) throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE waiting_question SET state='CANCELLED',answer_json=?,updated_at=?
                WHERE question_id=? AND state='WAITING'
                """)) {
            statement.setString(1, Json.write(Json.object().put("reason", reason == null ? "CANCELLED" : reason)));
            statement.setLong(2, now);
            statement.setString(3, questionId);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("QUESTION_NOT_WAITING");
        }
        return waiting(questionId).orElseThrow();
    }

    public Optional<WaitingQuestion> activeForCompanion(String companionId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM waiting_question WHERE companion_id=? AND state='WAITING'
                  AND (expires_at IS NULL OR expires_at>?) ORDER BY updated_at DESC LIMIT 1
                """)) {
            statement.setString(1, companionId); statement.setLong(2, clock.millis());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(readQuestion(result)) : Optional.empty(); }
        }
    }

    public List<ConversationEvent> list(String companionId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 200));
        List<ConversationEvent> values = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM conversation_event WHERE companion_id=? ORDER BY created_at DESC LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setInt(2, bounded);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) values.add(readEvent(result)); }
        }
        java.util.Collections.reverse(values);
        return List.copyOf(values);
    }

    public List<ConversationEvent> pendingGameDelivery(String companionId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 50));
        List<ConversationEvent> values = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM conversation_event WHERE companion_id=? AND direction='ASSISTANT'
                  AND game_delivered=0 ORDER BY created_at LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setInt(2, bounded);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) values.add(readEvent(result)); }
        }
        return List.copyOf(values);
    }

    public void markGameDelivered(String eventId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation_event SET game_delivered=1 WHERE event_id=?")) {
            statement.setString(1, eventId); statement.executeUpdate();
        }
    }

    public void markGameDelivered(String companionId, String eventId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation_event SET game_delivered=1 WHERE event_id=? AND companion_id=?")) {
            statement.setString(1, eventId);
            statement.setString(2, companionId);
            statement.executeUpdate();
        }
    }

    private Optional<ConversationEvent> event(String id) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM conversation_event WHERE event_id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(readEvent(result)) : Optional.empty(); }
        }
    }

    private Optional<WaitingQuestion> waiting(String id) throws SQLException {
        try (Connection connection = database.open()) { return waiting(connection, id); }
    }

    private static Optional<WaitingQuestion> waiting(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM waiting_question WHERE question_id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(readQuestion(result)) : Optional.empty(); }
        }
    }

    private static void insertEvent(Connection connection, String id, String companionId, String planId,
                                    String questionId, String direction, String kind, String content,
                                    JsonNode payload, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO conversation_event(event_id,companion_id,plan_id,question_id,direction,kind,
                  content,payload_json,game_delivered,created_at) VALUES(?,?,?,?,?,?,?,?,0,?)
                """)) {
            statement.setString(1, id); statement.setString(2, required(companionId));
            statement.setString(3, planId); statement.setString(4, questionId);
            statement.setString(5, required(direction)); statement.setString(6, required(kind));
            statement.setString(7, required(content)); statement.setString(8, Json.write(payload == null ? Json.object() : payload));
            statement.setLong(9, now); statement.executeUpdate();
        }
    }

    private static ConversationEvent readEvent(ResultSet result) throws SQLException {
        return new ConversationEvent(result.getString("event_id"), result.getString("companion_id"),
                result.getString("plan_id"), result.getString("question_id"), result.getString("direction"),
                result.getString("kind"), result.getString("content"), Json.parse(result.getString("payload_json")),
                result.getInt("game_delivered") != 0, Instant.ofEpochMilli(result.getLong("created_at")));
    }

    private static WaitingQuestion readQuestion(ResultSet result) throws SQLException {
        List<ConversationOption> options;
        try { options = Json.MAPPER.readValue(result.getString("options_json"), new TypeReference<>() { }); }
        catch (Exception invalid) { throw new SQLException("Invalid waiting question options", invalid); }
        long expires = result.getLong("expires_at"); Instant expiresAt = result.wasNull() ? null : Instant.ofEpochMilli(expires);
        String answer = result.getString("answer_json");
        return new WaitingQuestion(result.getString("question_id"), result.getString("plan_id"),
                result.getString("companion_id"), result.getString("prompt"), result.getString("reason"),
                List.copyOf(options), result.getInt("free_text_allowed") != 0, result.getString("state"),
                Json.parse(result.getString("context_json")), answer == null ? null : Json.parse(answer),
                Instant.ofEpochMilli(result.getLong("created_at")), Instant.ofEpochMilli(result.getLong("updated_at")), expiresAt);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required conversation value is blank");
        return value.strip();
    }
}
