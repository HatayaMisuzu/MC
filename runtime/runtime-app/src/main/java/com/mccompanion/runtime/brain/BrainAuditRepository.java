package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;

/** Durable protocol audit. Hidden model reasoning is never stored. */
public final class BrainAuditRepository {
    private final RuntimeDatabase database;
    private final Clock clock;
    public BrainAuditRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    BrainAuditRepository(RuntimeDatabase database, Clock clock) { this.database = database; this.clock = clock; }

    public int interruptActiveSessions() {
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                long now = clock.millis();
                try (PreparedStatement calls = connection.prepareStatement("""
                        SELECT session_id,call_id,observation_json FROM brain_tool_call WHERE terminal=0
                        """); var rows = calls.executeQuery()) {
                    while (rows.next()) {
                        JsonNode previous = Json.parse(rows.getString("observation_json"));
                        var interrupted = previous.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) previous.deepCopy()
                                : Json.object();
                        interrupted.put("state", "INTERRUPTED").put("message", "Runtime restarted before terminal observation");
                        try (PreparedStatement update = connection.prepareStatement("""
                                UPDATE brain_tool_call SET success=0,result_code='RUNTIME_RESTARTED',
                                observation_json=?,terminal=1,state='INTERRUPTED',updated_at=?
                                WHERE session_id=? AND call_id=? AND terminal=0
                                """)) {
                            update.setString(1, Json.write(interrupted)); update.setLong(2, now);
                            update.setString(3, rows.getString("session_id")); update.setString(4, rows.getString("call_id"));
                            update.executeUpdate();
                        }
                    }
                }
                int count;
                try (PreparedStatement sessions = connection.prepareStatement("""
                        UPDATE brain_session SET state='INTERRUPTED',last_code='RUNTIME_RESTARTED',updated_at=?
                        WHERE state='ACTIVE'
                        """)) {
                    sessions.setLong(1, now); count = sessions.executeUpdate();
                }
                connection.commit();
                return count;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public Optional<BrainSession> interrupted(String controllerId, String companionId) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT session_id,controller_id,companion_id,created_at FROM brain_session
                WHERE controller_id=? AND companion_id=? AND state='INTERRUPTED'
                ORDER BY updated_at DESC LIMIT 1
                """)) {
            statement.setString(1, controllerId); statement.setString(2, companionId);
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(new BrainSession(row.getString("session_id"),
                        row.getString("controller_id"), row.getString("companion_id"),
                        Instant.ofEpochMilli(row.getLong("created_at")))) : Optional.empty();
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public List<ToolResult> undeliveredTerminal(String sessionId) {
        List<ToolResult> results = new ArrayList<>();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT call_id,tool_name,success,result_code,observation_json FROM brain_tool_call
                WHERE session_id=? AND terminal=1 AND delivered_at IS NULL ORDER BY created_at
                """)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) results.add(new ToolResult(rows.getString("call_id"), rows.getString("tool_name"),
                        rows.getInt("success") != 0, rows.getString("result_code"),
                        Json.parse(rows.getString("observation_json")), true));
            }
            return List.copyOf(results);
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public Optional<AuditedToolCall> tool(String sessionId, String callId) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT tool_name,arguments_json,success,result_code,observation_json,terminal
                FROM brain_tool_call WHERE session_id=? AND call_id=?
                """)) {
            statement.setString(1, sessionId); statement.setString(2, callId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                ToolCall call = new ToolCall(callId, row.getString("tool_name"),
                        Json.parse(row.getString("arguments_json")));
                ToolResult result = new ToolResult(callId, call.name(), row.getInt("success") != 0,
                        row.getString("result_code"), Json.parse(row.getString("observation_json")),
                        row.getInt("terminal") != 0);
                return Optional.of(new AuditedToolCall(call, result));
            }
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
                observation_json,terminal,created_at,state,task_id,behavior_id,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(session_id,call_id) DO UPDATE SET success=excluded.success,
                result_code=excluded.result_code,observation_json=excluded.observation_json,
                terminal=excluded.terminal,state=excluded.state,
                task_id=COALESCE(excluded.task_id,brain_tool_call.task_id),
                behavior_id=COALESCE(excluded.behavior_id,brain_tool_call.behavior_id),updated_at=excluded.updated_at
                """)) {
            long now = clock.millis();
            statement.setString(1, sessionId); statement.setString(2, call.callId()); statement.setString(3, call.name());
            statement.setString(4, Json.write(call.arguments())); statement.setInt(5, result.success() ? 1 : 0);
            statement.setString(6, result.code()); statement.setString(7, Json.write(result.observation()));
            statement.setInt(8, result.terminal() ? 1 : 0); statement.setLong(9, now);
            statement.setString(10, toolState(result));
            statement.setString(11, textOrNull(result.observation(), "taskId"));
            statement.setString(12, textOrNull(result.observation(), "behaviorId"));
            statement.setLong(13, now); statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void delivered(String sessionId, String callId) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE brain_tool_call SET delivered_at=?,updated_at=?
                WHERE session_id=? AND call_id=? AND terminal=1 AND delivered_at IS NULL
                """)) {
            long now = clock.millis();
            statement.setLong(1, now); statement.setLong(2, now);
            statement.setString(3, sessionId); statement.setString(4, callId); statement.executeUpdate();
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
                                    .put("state", toolRows.getString("state"))
                                    .put("success", toolRows.getInt("success") != 0)
                                    .put("code", toolRows.getString("result_code"))
                                    .put("terminal", toolRows.getInt("terminal") != 0)
                                    .put("taskId", toolRows.getString("task_id"))
                                    .put("behaviorId", toolRows.getString("behavior_id"))
                                    .put("delivered", toolRows.getObject("delivered_at") != null)
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

    private static String toolState(ToolResult result) {
        String explicit = result.observation().path("state").asText("").strip();
        if (!explicit.isBlank()) return switch (explicit) {
            case "CREATED", "ACCEPTED" -> "ACCEPTED";
            case "RUNNING" -> "RUNNING";
            case "COMPLETED", "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            case "WAITING", "PAUSED", "BLOCKED" -> "BLOCKED";
            case "CANCELLED" -> "CANCELLED";
            case "RECONCILIATION_REQUIRED", "INTERRUPTED" -> "INTERRUPTED";
            default -> result.terminal() ? result.success() ? "SUCCEEDED" : "FAILED" : "ACCEPTED";
        };
        if (!result.terminal()) return result.success() ? "ACCEPTED" : "BLOCKED";
        return result.success() ? "SUCCEEDED" : switch (result.code()) {
            case "TOOL_CANCELLED", "SEARCH_CANCELLED" -> "CANCELLED";
            case "TOOL_INTERRUPTED", "TOOL_TIMEOUT" -> "INTERRUPTED";
            default -> "FAILED";
        };
    }

    private static String textOrNull(JsonNode value, String field) {
        String text = value.path(field).asText("").strip();
        return text.isBlank() ? null : text;
    }

    public record AuditedToolCall(ToolCall call, ToolResult result) { }
}
