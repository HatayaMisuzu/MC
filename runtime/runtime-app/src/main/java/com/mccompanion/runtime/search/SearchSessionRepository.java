package com.mccompanion.runtime.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolContext;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

/** Durable, expiring Search source sessions scoped to one controller/Brain/companion tuple. */
public final class SearchSessionRepository {
    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_SESSIONS_PER_COMPANION = 128;
    static final int MAX_STATE_BYTES = 256 * 1024;
    private final RuntimeDatabase database;
    private final Clock clock;

    public SearchSessionRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    SearchSessionRepository(RuntimeDatabase database, Clock clock) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public StoredSession activate(
            String searchId, ToolContext context, SearchQuery policy, List<SearchSource> sources)
            throws SQLException {
        String policyJson = Json.write(policy(policy));
        String sourcesJson = Json.write(Json.MAPPER.valueToTree(sources));
        int bytes = policyJson.getBytes(StandardCharsets.UTF_8).length
                + sourcesJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_STATE_BYTES) throw new IllegalArgumentException("SEARCH_SESSION_TOO_LARGE");
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM search_session
                        WHERE controller_id=? AND brain_session_id=? AND companion_id=?
                        """)) {
                    bindScope(statement, 1, context);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO search_session(
                          search_id,controller_id,brain_session_id,companion_id,state,policy_json,
                          sources_json,created_at,updated_at,expires_at)
                        VALUES(?,?,?,?,'ACTIVE',?,?,?,?,?)
                        """)) {
                    statement.setString(1, searchId);
                    statement.setString(2, context.controllerId());
                    statement.setString(3, context.brainSessionId());
                    statement.setString(4, context.companionId());
                    statement.setString(5, policyJson);
                    statement.setString(6, sourcesJson);
                    statement.setLong(7, now);
                    statement.setLong(8, now);
                    statement.setLong(9, now + TTL.toMillis());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM search_session WHERE companion_id=? AND search_id NOT IN (
                          SELECT search_id FROM search_session WHERE companion_id=?
                          ORDER BY updated_at DESC,search_id DESC LIMIT ?
                        )
                        """)) {
                    statement.setString(1, context.companionId());
                    statement.setString(2, context.companionId());
                    statement.setInt(3, MAX_SESSIONS_PER_COMPANION);
                    statement.executeUpdate();
                }
                connection.commit();
                return new StoredSession(searchId, policy, sources, now + TTL.toMillis());
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public Optional<StoredSession> active(ToolContext context) throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM search_session
                WHERE controller_id=? AND brain_session_id=? AND companion_id=? AND expires_at<=?
                """)) {
            bindScope(statement, 1, context);
            statement.setLong(4, now);
            statement.executeUpdate();
        }
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT search_id,policy_json,sources_json,expires_at FROM search_session
                WHERE controller_id=? AND brain_session_id=? AND companion_id=?
                  AND state='ACTIVE' AND expires_at>?
                ORDER BY updated_at DESC LIMIT 1
                """)) {
            bindScope(statement, 1, context);
            statement.setLong(4, now);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new StoredSession(row.getString("search_id"),
                        parsePolicy(row.getString("policy_json")),
                        Json.MAPPER.convertValue(Json.parse(row.getString("sources_json")),
                                new TypeReference<List<SearchSource>>() { }),
                        row.getLong("expires_at")));
            }
        }
    }

    public Optional<String> cancel(ToolContext context) throws SQLException {
        Optional<StoredSession> current = active(context);
        if (current.isEmpty()) return Optional.empty();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM search_session
                WHERE search_id=? AND controller_id=? AND brain_session_id=? AND companion_id=? AND state='ACTIVE'
                """)) {
            statement.setString(1, current.get().searchId());
            bindScope(statement, 2, context);
            return statement.executeUpdate() == 1
                    ? Optional.of(current.get().searchId()) : Optional.empty();
        }
    }

    public int expire() throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM search_session WHERE expires_at<=?")) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }

    public List<SessionView> listActive(int limit) throws SQLException {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("limit must be 1..128");
        expire();
        List<SessionView> sessions = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT search_id,companion_id,policy_json,sources_json,expires_at
                FROM search_session WHERE state='ACTIVE' AND expires_at>?
                ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setLong(1, clock.millis());
            statement.setInt(2, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    SearchQuery policy = parsePolicy(rows.getString("policy_json"));
                    sessions.add(new SessionView(rows.getString("search_id"),
                            rows.getString("companion_id"), policy.query(),
                            rows.getLong("expires_at"),
                            Json.MAPPER.convertValue(Json.parse(rows.getString("sources_json")),
                                    new TypeReference<List<SearchSource>>() { })));
                }
            }
        }
        return List.copyOf(sessions);
    }

    private static void bindScope(PreparedStatement statement, int start, ToolContext context)
            throws SQLException {
        statement.setString(start, context.controllerId());
        statement.setString(start + 1, context.brainSessionId());
        statement.setString(start + 2, context.companionId());
    }

    private static JsonNode policy(SearchQuery query) {
        var value = Json.object().put("query", query.query()).put("maxResults", query.maxResults())
                .put("locale", query.locale()).put("safeSearch", query.safeSearch())
                .put("timeoutMillis", query.timeout().toMillis());
        if (query.recencyDays() != null) value.put("recencyDays", query.recencyDays());
        value.set("allowedDomains", Json.MAPPER.valueToTree(query.allowedDomains()));
        return value;
    }

    private static SearchQuery parsePolicy(String json) {
        JsonNode value = Json.parse(json);
        List<String> domains = Json.MAPPER.convertValue(value.path("allowedDomains"),
                new TypeReference<List<String>>() { });
        return new SearchQuery(value.path("query").asText(), domains, value.path("maxResults").asInt(),
                value.has("recencyDays") ? value.path("recencyDays").asInt() : null,
                value.path("locale").asText(), value.path("safeSearch").asBoolean(),
                Duration.ofMillis(value.path("timeoutMillis").asLong()));
    }

    public record StoredSession(
            String searchId, SearchQuery policy, List<SearchSource> sources, long expiresAt) {
        public StoredSession {
            sources = List.copyOf(sources);
        }
    }

    public record SessionView(
            String searchId, String companionId, String query, long expiresAt,
            List<SearchSource> sources) {
        public SessionView {
            sources = List.copyOf(sources);
        }
    }
}
