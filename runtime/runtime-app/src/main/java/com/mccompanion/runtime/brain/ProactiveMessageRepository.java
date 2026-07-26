package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.db.RuntimeDatabase;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Durable, atomic rate and evidence deduplication boundary for external-Brain proactive speech. */
public final class ProactiveMessageRepository {
    private static final Duration RETENTION = Duration.ofDays(30);
    private final RuntimeDatabase database;
    private final Clock clock;

    public ProactiveMessageRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    ProactiveMessageRepository(RuntimeDatabase database, Clock clock) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public Admission admit(String companionId, String brainSessionId, String evidenceCallId,
                           String eventType, String messageSha256, String initiativeMode,
                           Duration minimumInterval) throws SQLException {
        long now = clock.millis();
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement prune = connection.prepareStatement("""
                        DELETE FROM proactive_message_admission
                        WHERE created_at<? OR admission_id IN (
                          SELECT admission_id FROM proactive_message_admission
                          WHERE companion_id=? ORDER BY created_at DESC LIMIT -1 OFFSET 512
                        )
                        """)) {
                    prune.setLong(1, now - RETENTION.toMillis());
                    prune.setString(2, required(companionId));
                    prune.executeUpdate();
                }
                try (PreparedStatement duplicate = connection.prepareStatement("""
                        SELECT admission_id,created_at,conversation_event_id
                        FROM proactive_message_admission
                        WHERE brain_session_id=? AND evidence_call_id=? AND event_type=?
                        """)) {
                    duplicate.setString(1, required(brainSessionId));
                    duplicate.setString(2, required(evidenceCallId));
                    duplicate.setString(3, required(eventType));
                    try (var row = duplicate.executeQuery()) {
                        if (row.next()) {
                            connection.commit();
                            return new Admission(false, "PROACTIVE_DUPLICATE_EVENT",
                                    row.getString(1), Instant.ofEpochMilli(row.getLong(2)),
                                    row.getString(3));
                        }
                    }
                }
                try (PreparedStatement latest = connection.prepareStatement("""
                        SELECT created_at FROM proactive_message_admission
                        WHERE companion_id=? ORDER BY created_at DESC LIMIT 1
                        """)) {
                    latest.setString(1, required(companionId));
                    try (var row = latest.executeQuery()) {
                        if (row.next() && now - row.getLong(1) < minimumInterval.toMillis()) {
                            connection.commit();
                            return new Admission(false, "PROACTIVE_RATE_LIMITED", null,
                                    Instant.ofEpochMilli(row.getLong(1)), null);
                        }
                    }
                }
                String id = UUID.randomUUID().toString();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO proactive_message_admission(
                          admission_id,companion_id,brain_session_id,evidence_call_id,event_type,
                          message_sha256,initiative_mode,conversation_event_id,created_at)
                        VALUES(?,?,?,?,?,?,?,NULL,?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, required(companionId));
                    insert.setString(3, required(brainSessionId));
                    insert.setString(4, required(evidenceCallId));
                    insert.setString(5, required(eventType));
                    insert.setString(6, required(messageSha256));
                    insert.setString(7, required(initiativeMode));
                    insert.setLong(8, now);
                    insert.executeUpdate();
                }
                connection.commit();
                return new Admission(true, "PROACTIVE_ADMITTED", id,
                        Instant.ofEpochMilli(now), null);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void linkConversationEvent(String admissionId, String eventId) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE proactive_message_admission SET conversation_event_id=?
                WHERE admission_id=? AND conversation_event_id IS NULL
                """)) {
            statement.setString(1, required(eventId));
            statement.setString(2, required(admissionId));
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("PROACTIVE_EVENT_LINK_CONFLICT");
            }
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
        return value.strip();
    }

    public record Admission(boolean admitted, String code, String admissionId,
                            Instant createdAt, String conversationEventId) { }
}
