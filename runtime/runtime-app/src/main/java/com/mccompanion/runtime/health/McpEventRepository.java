package com.mccompanion.runtime.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded durable SSE event replay scoped by the hash of an opaque MCP session bearer. */
public final class McpEventRepository {
    static final int MAX_EVENTS_PER_SESSION = 256;
    static final int MAX_EVENT_BYTES = 64 * 1024;
    private final RuntimeDatabase database;
    private final Clock clock;

    public McpEventRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    McpEventRepository(RuntimeDatabase database, Clock clock) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public Event append(String sessionToken, String callId, JsonNode payload, boolean terminal)
            throws SQLException {
        String json = Json.write(payload);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("MCP SSE event exceeds 64 KiB");
        }
        String sessionHash = Digests.sha256(sessionToken);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                String eventId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO mcp_event(
                          event_id,session_hash,call_id,payload_json,terminal,created_at)
                        VALUES(?,?,?,?,?,?)
                        """)) {
                    statement.setString(1, eventId);
                    statement.setString(2, sessionHash);
                    statement.setString(3, callId);
                    statement.setString(4, json);
                    statement.setInt(5, terminal ? 1 : 0);
                    statement.setLong(6, clock.millis());
                    statement.executeUpdate();
                }
                long sequence;
                try (var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT last_insert_rowid()")) {
                    if (!row.next()) throw new SQLException("Unable to read MCP event sequence");
                    sequence = row.getLong(1);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM mcp_event WHERE session_hash=? AND sequence NOT IN (
                          SELECT sequence FROM mcp_event WHERE session_hash=?
                          ORDER BY sequence DESC LIMIT ?
                        )
                        """)) {
                    statement.setString(1, sessionHash);
                    statement.setString(2, sessionHash);
                    statement.setInt(3, MAX_EVENTS_PER_SESSION);
                    statement.executeUpdate();
                }
                connection.commit();
                return new Event(eventId, sequence, payload.deepCopy(), terminal);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public List<Event> after(String sessionToken, String lastEventId) throws SQLException {
        String sessionHash = Digests.sha256(sessionToken);
        long sequence;
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT sequence FROM mcp_event WHERE event_id=? AND session_hash=?
                """)) {
            statement.setString(1, lastEventId);
            statement.setString(2, sessionHash);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("MCP SSE replay cursor is unknown");
                sequence = row.getLong(1);
            }
        }
        List<Event> events = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id,sequence,payload_json,terminal FROM mcp_event
                WHERE session_hash=? AND sequence>? ORDER BY sequence LIMIT ?
                """)) {
            statement.setString(1, sessionHash);
            statement.setLong(2, sequence);
            statement.setInt(3, MAX_EVENTS_PER_SESSION);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(new Event(rows.getString("event_id"), rows.getLong("sequence"),
                            Json.parse(rows.getString("payload_json")), rows.getInt("terminal") != 0));
                }
            }
        }
        return List.copyOf(events);
    }

    public int deleteSession(String sessionToken) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM mcp_event WHERE session_hash=?")) {
            statement.setString(1, Digests.sha256(sessionToken));
            return statement.executeUpdate();
        }
    }

    public int pruneInactiveSessions() throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM mcp_event WHERE session_hash NOT IN (
                  SELECT session_hash FROM mcp_session WHERE state='ACTIVE' AND expires_at>?
                )
                """)) {
            statement.setLong(1, clock.millis());
            return statement.executeUpdate();
        }
    }

    public record Event(String eventId, long sequence, JsonNode payload, boolean terminal) {
        public Event {
            payload = payload.deepCopy();
        }
    }
}
