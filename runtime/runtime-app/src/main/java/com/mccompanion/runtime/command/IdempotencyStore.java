package com.mccompanion.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;

public final class IdempotencyStore {
    private final RuntimeDatabase database;
    private final Clock clock;

    public IdempotencyStore(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    public IdempotencyStore(RuntimeDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public String requestHash(JsonNode request) {
        return Digests.sha256(Json.canonical(request));
    }

    public synchronized Claim claim(String commandId, String requestHash) throws SQLException {
        validateCommandId(commandId);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT OR IGNORE INTO command_result(command_id, request_hash, state, response_json, created_at, updated_at)
                        VALUES (?, ?, 'PROCESSING', NULL, ?, ?)
                        """)) {
                    statement.setString(1, commandId);
                    statement.setString(2, requestHash);
                    statement.setLong(3, clock.millis());
                    statement.setLong(4, clock.millis());
                    inserted = statement.executeUpdate();
                }
                Stored stored = read(connection, commandId);
                connection.commit();
                if (!stored.requestHash.equals(requestHash)) {
                    return new Claim(ClaimState.CONFLICT, stored.response, stored.state);
                }
                if (inserted == 1) {
                    return new Claim(ClaimState.NEW, null, "PROCESSING");
                }
                if (stored.response != null && (stored.state.equals("COMPLETED") || stored.state.equals("FAILED")
                        || stored.state.equals("RECOVERY_REQUIRED"))) {
                    return new Claim(ClaimState.CACHED, stored.response, stored.state);
                }
                return new Claim(ClaimState.IN_PROGRESS, null, stored.state);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public void complete(String commandId, String requestHash, JsonNode response, boolean success) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_result SET state=?, response_json=?, updated_at=?
                WHERE command_id=? AND request_hash=? AND state='PROCESSING'
                """)) {
            statement.setString(1, success ? "COMPLETED" : "FAILED");
            statement.setString(2, Json.write(response));
            statement.setLong(3, clock.millis());
            statement.setString(4, commandId);
            statement.setString(5, requestHash);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Idempotent command claim changed before completion");
            }
        }
    }

    /** Commands interrupted by process death remain non-replayable until task reconciliation decides their outcome. */
    public int markInterruptedForReconciliation() throws SQLException {
        JsonNode response = Json.object().put("accepted", false).put("code", "RECONCILIATION_REQUIRED")
                .put("message", "Runtime restarted while this command was in progress; command was not replayed");
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_result SET state='RECOVERY_REQUIRED', response_json=?, updated_at=?
                WHERE state='PROCESSING'
                """)) {
            statement.setString(1, Json.write(response));
            statement.setLong(2, clock.millis());
            return statement.executeUpdate();
        }
    }

    private Stored read(Connection connection, String commandId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT request_hash, state, response_json FROM command_result WHERE command_id=?")) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Idempotency claim disappeared");
                }
                String response = result.getString(3);
                return new Stored(result.getString(1), result.getString(2),
                        response == null ? null : Json.parse(response));
            }
        }
    }

    private static void validateCommandId(String commandId) {
        if (commandId == null || commandId.isBlank() || commandId.length() > 128
                || !commandId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException("commandId is missing or invalid");
        }
    }

    public enum ClaimState { NEW, CACHED, IN_PROGRESS, CONFLICT }
    public record Claim(ClaimState state, JsonNode cachedResponse, String storedState) { }
    private record Stored(String requestHash, String state, JsonNode response) { }
}
