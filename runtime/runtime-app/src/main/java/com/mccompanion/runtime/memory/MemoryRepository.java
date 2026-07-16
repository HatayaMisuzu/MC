package com.mccompanion.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Typed memory store. Inferences cannot overwrite verified facts. */
public final class MemoryRepository {
    private final RuntimeDatabase database;
    private final Clock clock;

    public MemoryRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    MemoryRepository(RuntimeDatabase database, Clock clock) { this.database = database; this.clock = clock; }

    public MemoryFact remember(String companionId, MemoryKind kind, String key, JsonNode value,
                               boolean verified, double confidence, Duration ttl) throws SQLException {
        return remember(companionId, kind, key, value, verified, confidence, ttl,
                verified ? "VERIFIED_RUNTIME" : "INFERENCE");
    }

    public MemoryFact remember(String companionId, MemoryKind kind, String key, JsonNode value,
                               boolean verified, double confidence, Duration ttl, String source) throws SQLException {
        if (confidence < 0 || confidence > 1 || Double.isNaN(confidence)) throw new IllegalArgumentException("confidence must be 0..1");
        long now = clock.millis();
        Long expires = ttl == null ? null : Math.addExact(now, ttl.toMillis());
        try (var connection = database.open()) {
            MemoryFact existing = find(connection, companionId, kind, key, now);
            if (existing != null && existing.verified() && !verified) return existing;
            String id = existing == null ? UUID.randomUUID().toString() : existing.memoryId();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO memory_fact(memory_id,companion_id,kind,fact_key,value_json,verified,confidence,source,expires_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(companion_id,kind,fact_key) DO UPDATE SET value_json=excluded.value_json,
                    verified=excluded.verified,confidence=excluded.confidence,source=excluded.source,
                    expires_at=excluded.expires_at,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, id); statement.setString(2, required(companionId)); statement.setString(3, kind.name());
                statement.setString(4, required(key)); statement.setString(5, Json.write(value == null ? Json.object() : value));
                statement.setInt(6, verified ? 1 : 0); statement.setDouble(7, confidence);
                statement.setString(8, required(source));
                if (expires == null) statement.setNull(9, java.sql.Types.BIGINT); else statement.setLong(9, expires);
                statement.setLong(10, existing == null ? now : existing.createdAt().toEpochMilli()); statement.setLong(11, now);
                statement.executeUpdate();
            }
            return find(connection, companionId, kind, key, now);
        }
    }

    public List<MemoryFact> list(String companionId, MemoryKind kind, int limit) throws SQLException {
        return relevant(companionId, kind, limit);
    }

    public MemorySuggestion suggest(String companionId, MemoryKind kind, String key, JsonNode value,
                                    double confidence, Duration ttl, String source,
                                    String brainSessionId) throws SQLException {
        if (kind == MemoryKind.WORKING) throw new IllegalArgumentException("working memory cannot be suggested");
        if (confidence < 0 || confidence > 0.9 || Double.isNaN(confidence)) {
            throw new IllegalArgumentException("suggestion confidence must be 0..0.9");
        }
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(60)) < 0
                || ttl.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("suggestion ttl must be 60 seconds..365 days");
        }
        JsonNode boundedValue = value == null ? Json.MAPPER.nullNode() : value;
        if (Json.write(boundedValue).length() > 4_096) {
            throw new IllegalArgumentException("suggestion value exceeds 4096 characters");
        }
        long now = clock.millis();
        long expires = Math.addExact(now, ttl.toMillis());
        String id = UUID.randomUUID().toString();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_suggestion(suggestion_id,companion_id,kind,suggestion_key,value_json,
                confidence,status,source,brain_session_id,expires_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'QUARANTINED',?,?,?,?,?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, required(companionId));
            statement.setString(3, java.util.Objects.requireNonNull(kind, "kind").name());
            statement.setString(4, required(key));
            statement.setString(5, Json.write(boundedValue));
            statement.setDouble(6, confidence);
            statement.setString(7, required(source));
            statement.setString(8, required(brainSessionId));
            statement.setLong(9, expires);
            statement.setLong(10, now);
            statement.setLong(11, now);
            statement.executeUpdate();
        }
        return suggestion(id).orElseThrow();
    }

    public List<MemorySuggestion> suggestions(String companionId, String status, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        long now = clock.millis();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM memory_suggestion
                WHERE companion_id=? AND status=? AND expires_at>?
                ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, required(companionId));
            statement.setString(2, required(status));
            statement.setLong(3, now);
            statement.setInt(4, bounded);
            List<MemorySuggestion> values = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(readSuggestion(result));
            }
            return List.copyOf(values);
        }
    }

    private java.util.Optional<MemorySuggestion> suggestion(String suggestionId) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM memory_suggestion WHERE suggestion_id=?")) {
            statement.setString(1, required(suggestionId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? java.util.Optional.of(readSuggestion(result)) : java.util.Optional.empty();
            }
        }
    }

    public List<MemoryFact> search(String companionId, String text, int limit) throws SQLException {
        return search(companionId, null, text, limit);
    }

    public List<MemoryFact> search(String companionId, MemoryKind filter, String text, int limit) throws SQLException {
        String query = required(text).toLowerCase(java.util.Locale.ROOT);
        List<MemoryFact> values = new ArrayList<>();
        for (MemoryKind kind : MemoryKind.values()) {
            if (filter != null && kind != filter) continue;
            for (MemoryFact fact : relevant(companionId, kind, 100)) {
                if (fact.key().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || Json.write(fact.value()).toLowerCase(java.util.Locale.ROOT).contains(query)) values.add(fact);
            }
        }
        return values.stream().sorted(java.util.Comparator.comparing(MemoryFact::updatedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 100))).toList();
    }

    public boolean delete(String companionId, String memoryId) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM memory_fact WHERE companion_id=? AND memory_id=?")) {
            statement.setString(1, required(companionId)); statement.setString(2, required(memoryId));
            return statement.executeUpdate() == 1;
        }
    }

    public int clear(String companionId, MemoryKind kind) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM memory_fact WHERE companion_id=? AND kind=?")) {
            statement.setString(1, required(companionId)); statement.setString(2, kind.name());
            return statement.executeUpdate();
        }
    }

    public List<MemoryFact> relevant(String companionId, MemoryKind kind, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        long now = clock.millis();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM memory_fact WHERE companion_id=? AND kind=? AND (expires_at IS NULL OR expires_at>?)
                ORDER BY verified DESC,confidence DESC,updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setString(2, kind.name()); statement.setLong(3, now); statement.setInt(4, bounded);
            List<MemoryFact> values = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) { while (result.next()) values.add(read(result)); }
            return List.copyOf(values);
        }
    }

    public int expire() throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM memory_fact WHERE expires_at IS NOT NULL AND expires_at<=?")) {
            long now = clock.millis();
            statement.setLong(1, now);
            int expired = statement.executeUpdate();
            try (PreparedStatement suggestions = connection.prepareStatement(
                    "DELETE FROM memory_suggestion WHERE expires_at<=?")) {
                suggestions.setLong(1, now);
                return expired + suggestions.executeUpdate();
            }
        }
    }

    /** Persists only body-verified visible container positions; item contents are never inferred or scanned. */
    public void rememberObservedContainers(String companionId, JsonNode status) throws SQLException {
        JsonNode observed = status == null ? null : status.path("observedContainers");
        if (observed == null || !observed.isArray()) return;
        for (JsonNode container : observed) {
            if (!container.path("verified").asBoolean(false)
                    || !container.path("x").canConvertToInt()
                    || !container.path("y").canConvertToInt()
                    || !container.path("z").canConvertToInt()) continue;
            String dimension = container.path("dimension").asText("");
            if (dimension.isBlank()) continue;
            String key = "container:" + dimension + ':' + container.path("x").asInt() + ':'
                    + container.path("y").asInt() + ':' + container.path("z").asInt();
            remember(companionId, MemoryKind.WORLD, key, container, true, 1.0D, null, "BODY_OBSERVATION");
        }
    }

    public JsonNode enrichVerifiedWorld(String companionId, JsonNode currentStatus) throws SQLException {
        ObjectNode world = currentStatus != null && currentStatus.isObject()
                ? (ObjectNode) currentStatus.deepCopy() : Json.object();
        var containers = world.putArray("knownContainers");
        for (MemoryFact fact : relevant(companionId, MemoryKind.WORLD, 100)) {
            if (fact.verified() && fact.key().startsWith("container:")) containers.add(fact.value());
        }
        return world;
    }

    public List<String> verifiedLandmarkKeys(String companionId) throws SQLException {
        return relevant(companionId, MemoryKind.WORLD, 100).stream()
                .filter(MemoryFact::verified).map(MemoryFact::key).toList();
    }

    /** Bounded preference context with confidence and freshness metadata. */
    public JsonNode preferenceContext(String companionId, int limit) throws SQLException {
        var values = Json.MAPPER.createArrayNode();
        for (MemoryFact fact : relevant(companionId, MemoryKind.PREFERENCE, limit)) {
            var entry = values.addObject().put("key", fact.key())
                    .put("verified", fact.verified()).put("confidence", fact.confidence())
                    .put("updatedAt", fact.updatedAt().toString());
            if (fact.expiresAt() != null) entry.put("expiresAt", fact.expiresAt().toString());
            entry.set("value", fact.value());
        }
        return values;
    }

    private static MemoryFact find(java.sql.Connection connection, String companionId, MemoryKind kind, String key, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM memory_fact WHERE companion_id=? AND kind=? AND fact_key=? AND (expires_at IS NULL OR expires_at>?)
                """)) {
            statement.setString(1, companionId); statement.setString(2, kind.name()); statement.setString(3, key); statement.setLong(4, now);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? read(result) : null; }
        }
    }

    private static MemoryFact read(ResultSet result) throws SQLException {
        long expires = result.getLong("expires_at");
        boolean noExpiry = result.wasNull();
        return new MemoryFact(result.getString("memory_id"), result.getString("companion_id"),
                MemoryKind.valueOf(result.getString("kind")), result.getString("fact_key"),
                Json.parse(result.getString("value_json")), result.getInt("verified") != 0,
                result.getDouble("confidence"), result.getString("source"),
                noExpiry ? null : Instant.ofEpochMilli(expires),
                Instant.ofEpochMilli(result.getLong("created_at")), Instant.ofEpochMilli(result.getLong("updated_at")));
    }

    private static MemorySuggestion readSuggestion(ResultSet result) throws SQLException {
        return new MemorySuggestion(result.getString("suggestion_id"), result.getString("companion_id"),
                MemoryKind.valueOf(result.getString("kind")), result.getString("suggestion_key"),
                Json.parse(result.getString("value_json")), result.getDouble("confidence"),
                result.getString("status"), result.getString("source"),
                result.getString("brain_session_id"), Instant.ofEpochMilli(result.getLong("expires_at")),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")));
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException("memory identifier is invalid");
        return value.strip();
    }
}
