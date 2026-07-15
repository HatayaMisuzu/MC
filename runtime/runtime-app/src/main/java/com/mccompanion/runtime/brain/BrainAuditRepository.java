package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import com.fasterxml.jackson.databind.JsonNode;

/** Durable protocol audit. Hidden model reasoning is never stored. */
public final class BrainAuditRepository {
    private final RuntimeDatabase database;
    private final Clock clock;
    public BrainAuditRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    BrainAuditRepository(RuntimeDatabase database, Clock clock) { this.database = database; this.clock = clock; }

    public int interruptActiveSessions() {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE brain_session SET state='INTERRUPTED',last_code='RUNTIME_RESTARTED',updated_at=?
                WHERE state='ACTIVE'
                """)) {
            statement.setLong(1, clock.millis()); return statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void opened(BrainSession session, String provider) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO brain_session(session_id,controller_id,companion_id,provider,state,last_code,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            long now = clock.millis();
            statement.setString(1, session.sessionId()); statement.setString(2, session.controllerId());
            statement.setString(3, session.companionId()); statement.setString(4, provider);
            statement.setString(5, "ACTIVE"); statement.setString(6, "OPENED");
            statement.setLong(7, now); statement.setLong(8, now); statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void tool(String sessionId, ToolCall call, ToolResult result) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO brain_tool_call(session_id,call_id,tool_name,arguments_json,success,result_code,
                observation_json,terminal,created_at) VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(session_id,call_id) DO NOTHING
                """)) {
            statement.setString(1, sessionId); statement.setString(2, call.callId()); statement.setString(3, call.name());
            statement.setString(4, Json.write(call.arguments())); statement.setInt(5, result.success() ? 1 : 0);
            statement.setString(6, result.code()); statement.setString(7, Json.write(result.observation()));
            statement.setInt(8, result.terminal() ? 1 : 0); statement.setLong(9, clock.millis()); statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void state(String sessionId, String state, String code) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE brain_session SET state=?,last_code=?,updated_at=? WHERE session_id=?")) {
            statement.setString(1, state); statement.setString(2, code); statement.setLong(3, clock.millis());
            statement.setString(4, sessionId); statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public int toolCount(String sessionId) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM brain_tool_call WHERE session_id=?")) {
            statement.setString(1, sessionId); try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
        }
    }

    public JsonNode inspect(String companionId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        var sessions = Json.MAPPER.createArrayNode();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM brain_session WHERE companion_id=? ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setInt(2, bounded);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var session = sessions.addObject().put("sessionId", rows.getString("session_id"))
                            .put("controllerId", rows.getString("controller_id")).put("provider", rows.getString("provider"))
                            .put("state", rows.getString("state")).put("lastCode", rows.getString("last_code"))
                            .put("createdAt", java.time.Instant.ofEpochMilli(rows.getLong("created_at")).toString())
                            .put("updatedAt", java.time.Instant.ofEpochMilli(rows.getLong("updated_at")).toString());
                    var tools = session.putArray("toolCalls");
                    try (PreparedStatement calls = connection.prepareStatement("""
                            SELECT * FROM brain_tool_call WHERE session_id=? ORDER BY created_at LIMIT 100
                            """)) {
                        calls.setString(1, rows.getString("session_id"));
                        try (var toolRows = calls.executeQuery()) {
                            while (toolRows.next()) tools.addObject().put("callId", toolRows.getString("call_id"))
                                    .put("toolName", toolRows.getString("tool_name"))
                                    .put("success", toolRows.getInt("success") != 0)
                                    .put("code", toolRows.getString("result_code"))
                                    .put("terminal", toolRows.getInt("terminal") != 0)
                                    .set("observation", Json.parse(toolRows.getString("observation_json")));
                        }
                    }
                }
            }
        }
        return sessions;
    }

    private static IllegalStateException persistence(SQLException failure) {
        return new IllegalStateException("BRAIN_PERSISTENCE_ERROR", failure);
    }
}
