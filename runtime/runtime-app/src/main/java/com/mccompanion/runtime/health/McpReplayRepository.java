package com.mccompanion.runtime.health;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;

/** Durable, identity-free MCP replay ledger keyed by the already scope-derived call ID. */
public final class McpReplayRepository {
    private final RuntimeDatabase database;
    private final Clock clock;

    public McpReplayRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    McpReplayRepository(RuntimeDatabase database, Clock clock) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public Acquisition acquire(ToolCall call) throws SQLException {
        String hash = argumentsHash(call);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                long now = clock.millis();
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT OR IGNORE INTO mcp_request(
                          call_id,tool_name,arguments_hash,state,result_json,created_at,updated_at)
                        VALUES(?,?,?,'IN_FLIGHT',NULL,?,?)
                        """)) {
                    statement.setString(1, call.callId());
                    statement.setString(2, call.name());
                    statement.setString(3, hash);
                    statement.setLong(4, now);
                    statement.setLong(5, now);
                    inserted = statement.executeUpdate();
                }
                if (inserted == 1) {
                    connection.commit();
                    return new Acquisition(Status.NEW, null);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT tool_name,arguments_hash,state,result_json FROM mcp_request WHERE call_id=?")) {
                    statement.setString(1, call.callId());
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("MCP replay row disappeared");
                        if (!row.getString("tool_name").equals(call.name())
                                || !row.getString("arguments_hash").equals(hash)) {
                            connection.rollback();
                            return new Acquisition(Status.CONFLICT, null);
                        }
                        String state = row.getString("state");
                        ToolResult result = "TERMINAL".equals(state)
                                ? result(row.getString("result_json")) : null;
                        connection.rollback();
                        return new Acquisition(switch (state) {
                            case "TERMINAL" -> Status.TERMINAL;
                            case "RECONCILIATION_REQUIRED" -> Status.RECONCILIATION_REQUIRED;
                            default -> Status.IN_FLIGHT;
                        }, result);
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public void complete(ToolCall call, ToolResult result) throws SQLException {
        if (!result.terminal()) throw new IllegalArgumentException("MCP result must be terminal");
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE mcp_request SET state='TERMINAL',result_json=?,updated_at=?
                WHERE call_id=? AND tool_name=? AND arguments_hash=? AND state='IN_FLIGHT'
                """)) {
            statement.setString(1, Json.write(resultJson(result)));
            statement.setLong(2, clock.millis());
            statement.setString(3, call.callId());
            statement.setString(4, call.name());
            statement.setString(5, argumentsHash(call));
            if (statement.executeUpdate() != 1) throw new SQLException("MCP replay completion lost ownership");
        }
    }

    public void quarantine(ToolCall call) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE mcp_request SET state='RECONCILIATION_REQUIRED',updated_at=?
                WHERE call_id=? AND tool_name=? AND arguments_hash=? AND state='IN_FLIGHT'
                """)) {
            statement.setLong(1, clock.millis());
            statement.setString(2, call.callId());
            statement.setString(3, call.name());
            statement.setString(4, argumentsHash(call));
            statement.executeUpdate();
        }
    }

    public int quarantineInterrupted() throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE mcp_request SET state='RECONCILIATION_REQUIRED',updated_at=? WHERE state='IN_FLIGHT'
                """)) {
            statement.setLong(1, clock.millis());
            return statement.executeUpdate();
        }
    }

    private static String argumentsHash(ToolCall call) {
        return Digests.sha256(Json.canonical(call.arguments()));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode resultJson(ToolResult result) {
        return Json.object().put("callId", result.callId()).put("toolName", result.toolName())
                .put("success", result.success()).put("code", result.code())
                .put("terminal", result.terminal()).set("observation", result.observation());
    }

    private static ToolResult result(String json) {
        var value = Json.parse(json);
        return new ToolResult(value.path("callId").asText(), value.path("toolName").asText(),
                value.path("success").asBoolean(), value.path("code").asText(),
                value.path("observation"), value.path("terminal").asBoolean());
    }

    public enum Status { NEW, TERMINAL, IN_FLIGHT, RECONCILIATION_REQUIRED, CONFLICT }
    public record Acquisition(Status status, ToolResult result) { }
}
