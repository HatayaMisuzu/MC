package com.mccompanion.runtime.health;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.security.Digests;
import com.mccompanion.runtime.tool.ToolContext;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/** Durable opaque MCP transport sessions; only token hashes are persisted. */
public final class McpSessionRepository {
    static final Duration DEFAULT_TTL = Duration.ofHours(8);
    private final RuntimeDatabase database;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random;

    public McpSessionRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC(), DEFAULT_TTL, new SecureRandom());
    }

    McpSessionRepository(RuntimeDatabase database, Clock clock, Duration ttl, SecureRandom random) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.ttl = java.util.Objects.requireNonNull(ttl, "ttl");
        this.random = java.util.Objects.requireNonNull(random, "random");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("MCP session ttl must be 1ns..24h");
        }
    }

    public String create(ToolContext context, String protocolVersion) throws SQLException {
        for (int attempt = 0; attempt < 4; attempt++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            long now = clock.millis();
            try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO mcp_session(
                      session_hash,controller_id,brain_session_id,companion_id,protocol_version,
                      state,expires_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,'ACTIVE',?,?,?)
                    """)) {
                statement.setString(1, hash(token));
                statement.setString(2, context.controllerId());
                statement.setString(3, context.brainSessionId());
                statement.setString(4, context.companionId());
                statement.setString(5, protocolVersion);
                statement.setLong(6, now + ttl.toMillis());
                statement.setLong(7, now);
                statement.setLong(8, now);
                if (statement.executeUpdate() == 1) return token;
            }
        }
        throw new SQLException("Unable to allocate unique MCP session");
    }

    public Status validate(String token, ToolContext context, String protocolVersion) throws SQLException {
        if (token == null || token.isBlank()) return Status.MISSING;
        if (!validToken(token)) return Status.NOT_FOUND;
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT controller_id,brain_session_id,companion_id,protocol_version,state,expires_at
                FROM mcp_session WHERE session_hash=?
                """)) {
            statement.setString(1, hash(token));
            try (var row = statement.executeQuery()) {
                if (!row.next() || !"ACTIVE".equals(row.getString("state"))
                        || row.getLong("expires_at") <= now) return Status.NOT_FOUND;
                if (!row.getString("controller_id").equals(context.controllerId())
                        || !row.getString("brain_session_id").equals(context.brainSessionId())
                        || !row.getString("companion_id").equals(context.companionId())
                        || !row.getString("protocol_version").equals(protocolVersion)) {
                    return Status.NOT_FOUND;
                }
            }
        }
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE mcp_session SET updated_at=? WHERE session_hash=? AND state='ACTIVE' AND expires_at>?
                """)) {
            statement.setLong(1, now);
            statement.setString(2, hash(token));
            statement.setLong(3, now);
            return statement.executeUpdate() == 1 ? Status.ACTIVE : Status.NOT_FOUND;
        }
    }

    public boolean terminate(String token, ToolContext context, String protocolVersion) throws SQLException {
        if (validate(token, context, protocolVersion) != Status.ACTIVE) return false;
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE mcp_session SET state='TERMINATED',updated_at=?
                WHERE session_hash=? AND state='ACTIVE'
                """)) {
            statement.setLong(1, clock.millis());
            statement.setString(2, hash(token));
            return statement.executeUpdate() == 1;
        }
    }

    public int expire() throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE mcp_session SET state='EXPIRED',updated_at=?
                WHERE state='ACTIVE' AND expires_at<=?
                """)) {
            long now = clock.millis();
            statement.setLong(1, now);
            statement.setLong(2, now);
            return statement.executeUpdate();
        }
    }

    private static String hash(String token) {
        return Digests.sha256(token);
    }

    private static boolean validToken(String token) {
        return token.length() == 43 && token.chars().allMatch(character ->
                character >= 'A' && character <= 'Z'
                        || character >= 'a' && character <= 'z'
                        || character >= '0' && character <= '9'
                        || character == '-' || character == '_');
    }

    public enum Status { ACTIVE, MISSING, NOT_FOUND }
}
