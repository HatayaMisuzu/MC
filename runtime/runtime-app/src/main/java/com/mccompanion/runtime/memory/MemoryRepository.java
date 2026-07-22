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
    private static final int MAX_VALUE_CHARS = 16_384;
    private static final int MAX_QUARANTINED_SUGGESTIONS = 128;
    private static final Duration MAX_WORKING_TTL = Duration.ofHours(24);
    private final RuntimeDatabase database;
    private final Clock clock;
    private final EpisodeCapsuleRepository capsules;

    public MemoryRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    MemoryRepository(RuntimeDatabase database, Clock clock) {
        this.database = database; this.clock = clock; this.capsules = new EpisodeCapsuleRepository(database, clock);
    }

    public EpisodeCapsuleRepository capsules() { return capsules; }

    public JsonNode latestCapsuleContext(String companionId) throws SQLException {
        List<EpisodeCapsule> values = capsules.list(companionId, 1);
        return values.isEmpty() ? Json.object() : Json.MAPPER.valueToTree(values.getFirst());
    }

    public MemoryFact remember(String companionId, MemoryKind kind, String key, JsonNode value,
                               boolean verified, double confidence, Duration ttl) throws SQLException {
        return remember(companionId, kind, key, value, verified, confidence, ttl,
                verified ? "VERIFIED_RUNTIME" : "INFERENCE");
    }

    public MemoryFact remember(String companionId, MemoryKind kind, String key, JsonNode value,
                               boolean verified, double confidence, Duration ttl, String source) throws SQLException {
        if (confidence < 0 || confidence > 1 || Double.isNaN(confidence)) throw new IllegalArgumentException("confidence must be 0..1");
        java.util.Objects.requireNonNull(kind, "kind");
        JsonNode boundedValue = value == null ? Json.object() : value;
        if (Json.write(boundedValue).length() > MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("memory value exceeds 16384 characters");
        }
        if (kind == MemoryKind.WORKING && (ttl == null || ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(MAX_WORKING_TTL) > 0)) {
            throw new IllegalArgumentException("working memory ttl must be 1 second..24 hours");
        }
        long now = clock.millis();
        Long expires = ttl == null ? null : Math.addExact(now, ttl.toMillis());
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                MemoryFact existing = find(connection, companionId, kind, key, now);
                if (existing == null) ensureCapacity(connection, companionId, kind, now);
                if (existing != null && existing.verified() && !verified) {
                    connection.commit();
                    return existing;
                }
                String id = existing == null ? UUID.randomUUID().toString() : existing.memoryId();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO memory_fact(memory_id,companion_id,kind,fact_key,value_json,verified,confidence,source,expires_at,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(companion_id,kind,fact_key) DO UPDATE SET value_json=excluded.value_json,
                        verified=excluded.verified,confidence=excluded.confidence,source=excluded.source,
                        expires_at=excluded.expires_at,updated_at=excluded.updated_at
                        """)) {
                    statement.setString(1, id); statement.setString(2, required(companionId)); statement.setString(3, kind.name());
                    statement.setString(4, required(key)); statement.setString(5, Json.write(boundedValue));
                    statement.setInt(6, verified ? 1 : 0); statement.setDouble(7, confidence);
                    statement.setString(8, required(source));
                    if (expires == null) statement.setNull(9, java.sql.Types.BIGINT); else statement.setLong(9, expires);
                    statement.setLong(10, existing == null ? now : existing.createdAt().toEpochMilli()); statement.setLong(11, now);
                    statement.executeUpdate();
                }
                MemoryFact remembered = find(connection, companionId, kind, key, now);
                connection.commit();
                return remembered;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<MemoryFact> list(String companionId, MemoryKind kind, int limit) throws SQLException {
        return relevant(companionId, kind, limit);
    }

    public MemorySuggestion suggest(String companionId, MemoryKind kind, String key, JsonNode value,
                                    double confidence, Duration ttl, String source,
                                    String brainSessionId) throws SQLException {
        return suggest(companionId, kind, key, value, confidence, ttl, source, brainSessionId, null);
    }

    public MemorySuggestion suggest(String companionId, MemoryKind kind, String key, JsonNode value,
                                    double confidence, Duration ttl, String source,
                                    String brainSessionId, String capsuleId) throws SQLException {
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
        if (capsuleId != null) {
            EpisodeCapsule capsule = capsules.require(companionId, capsuleId);
            if (!capsule.brainSessionId().equals(brainSessionId)) {
                throw new IllegalArgumentException("episode capsule does not belong to the active brain session");
            }
        }
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                deleteExpiredSuggestions(connection, now);
                try (PreparedStatement count = connection.prepareStatement("""
                        SELECT COUNT(*) FROM memory_suggestion
                        WHERE companion_id=? AND status='QUARANTINED' AND expires_at>?
                        """)) {
                    count.setString(1, required(companionId));
                    count.setLong(2, now);
                    try (ResultSet result = count.executeQuery()) {
                        if (result.next() && result.getInt(1) >= MAX_QUARANTINED_SUGGESTIONS) {
                            throw new IllegalStateException("memory suggestion quarantine capacity reached");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO memory_suggestion(suggestion_id,companion_id,kind,suggestion_key,value_json,
                        confidence,status,source,brain_session_id,expires_at,created_at,updated_at,capsule_id)
                        VALUES(?,?,?,?,?,?,'QUARANTINED',?,?,?,?,?,?)
                        """)) {
                    statement.setString(1, id);
                    statement.setString(2, required(companionId));
                    statement.setString(3, java.util.Objects.requireNonNull(kind, "kind").name());
                    statement.setString(4, required(key));
                    statement.setString(5, Json.write(boundedValue));
                    statement.setDouble(6, confidence);
                    statement.setString(7, capsuleId == null ? required(source) : "EPISODE_CAPSULE");
                    statement.setString(8, required(brainSessionId));
                    statement.setLong(9, expires);
                    statement.setLong(10, now);
                    statement.setLong(11, now);
                    if (capsuleId == null) statement.setNull(12, java.sql.Types.VARCHAR);
                    else statement.setString(12, capsuleId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return suggestion(id).orElseThrow();
    }

    public List<MemorySuggestion> suggestions(String companionId, String status, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        long now = clock.millis();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT ms.*,mf.memory_id AS conflicting_memory_id FROM memory_suggestion ms
                LEFT JOIN memory_fact mf ON mf.companion_id=ms.companion_id AND mf.kind=ms.kind
                  AND mf.fact_key=ms.suggestion_key AND mf.verified=1
                WHERE ms.companion_id=? AND ms.status=? AND ms.expires_at>?
                ORDER BY ms.updated_at DESC LIMIT ?
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

    /**
     * Promotes one still-live quarantined candidate through a local-user-only management path.
     * The fact write and review transition share one transaction, so a crash cannot expose a
     * verified fact while leaving the candidate actionable.
     */
    public MemoryFact approveSuggestion(String companionId, String suggestionId, String reviewedBy)
            throws SQLException {
        long now = clock.millis();
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                MemorySuggestion candidate = suggestion(connection, companionId, suggestionId);
                requireReviewable(candidate, now);
                MemoryFact existing = find(connection, companionId, candidate.kind(), candidate.key(), now);
                String memoryId = existing == null ? UUID.randomUUID().toString() : existing.memoryId();
                long createdAt = existing == null ? now : existing.createdAt().toEpochMilli();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO memory_fact(memory_id,companion_id,kind,fact_key,value_json,verified,
                        confidence,source,expires_at,created_at,updated_at)
                        VALUES(?,?,?,?,?,1,1.0,?,?,?,?)
                        ON CONFLICT(companion_id,kind,fact_key) DO UPDATE SET value_json=excluded.value_json,
                        verified=1,confidence=1.0,source=excluded.source,expires_at=excluded.expires_at,
                        updated_at=excluded.updated_at
                        """)) {
                    statement.setString(1, memoryId);
                    statement.setString(2, candidate.companionId());
                    statement.setString(3, candidate.kind().name());
                    statement.setString(4, candidate.key());
                    statement.setString(5, Json.write(candidate.value()));
                    statement.setString(6, candidate.capsuleId() == null ? "USER_APPROVED_SUGGESTION"
                            : "USER_APPROVED_EPISODE_CAPSULE:" + candidate.capsuleId());
                    statement.setLong(7, candidate.expiresAt().toEpochMilli());
                    statement.setLong(8, createdAt);
                    statement.setLong(9, now);
                    statement.executeUpdate();
                }
                transitionSuggestion(connection, candidate.suggestionId(), "APPROVED",
                        required(reviewedBy), null, now);
                MemoryFact promoted = find(connection, companionId, candidate.kind(), candidate.key(), now);
                connection.commit();
                return promoted;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public MemorySuggestion rejectSuggestion(String companionId, String suggestionId,
                                             String reviewedBy, String reason) throws SQLException {
        long now = clock.millis();
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                MemorySuggestion candidate = suggestion(connection, companionId, suggestionId);
                requireReviewable(candidate, now);
                transitionSuggestion(connection, candidate.suggestionId(), "REJECTED",
                        required(reviewedBy), required(reason), now);
                connection.commit();
                return suggestion(connection, companionId, suggestionId);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private java.util.Optional<MemorySuggestion> suggestion(String suggestionId) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                """
                SELECT ms.*,mf.memory_id AS conflicting_memory_id FROM memory_suggestion ms
                LEFT JOIN memory_fact mf ON mf.companion_id=ms.companion_id AND mf.kind=ms.kind
                  AND mf.fact_key=ms.suggestion_key AND mf.verified=1 WHERE ms.suggestion_id=?
                """)) {
            statement.setString(1, required(suggestionId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? java.util.Optional.of(readSuggestion(result)) : java.util.Optional.empty();
            }
        }
    }

    private static MemorySuggestion suggestion(java.sql.Connection connection, String companionId,
                                               String suggestionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ms.*,mf.memory_id AS conflicting_memory_id FROM memory_suggestion ms
                LEFT JOIN memory_fact mf ON mf.companion_id=ms.companion_id AND mf.kind=ms.kind
                  AND mf.fact_key=ms.suggestion_key AND mf.verified=1
                WHERE ms.companion_id=? AND ms.suggestion_id=?
                """)) {
            statement.setString(1, required(companionId));
            statement.setString(2, required(suggestionId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("memory suggestion was not found in companion scope");
                return readSuggestion(result);
            }
        }
    }

    private static void requireReviewable(MemorySuggestion candidate, long now) {
        if (!"QUARANTINED".equals(candidate.status())) {
            throw new IllegalStateException("memory suggestion is already reviewed");
        }
        if (candidate.expiresAt().toEpochMilli() <= now) {
            throw new IllegalStateException("memory suggestion has expired");
        }
    }

    private static void transitionSuggestion(java.sql.Connection connection, String suggestionId,
                                             String status, String reviewedBy, String reason, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE memory_suggestion SET status=?,reviewed_by=?,review_reason=?,reviewed_at=?,updated_at=?
                WHERE suggestion_id=? AND status='QUARANTINED'
                """)) {
            statement.setString(1, status);
            statement.setString(2, reviewedBy);
            if (reason == null) statement.setNull(3, java.sql.Types.VARCHAR); else statement.setString(3, reason);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.setString(6, suggestionId);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("memory suggestion review raced");
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
            try (PreparedStatement suggestions = connection.prepareStatement("""
                    UPDATE memory_suggestion SET status='EXPIRED',reviewed_by='SYSTEM_TTL',
                    review_reason='TTL_EXPIRED',reviewed_at=?,updated_at=?
                    WHERE status='QUARANTINED' AND expires_at<=?
                    """)) {
                suggestions.setLong(1, now); suggestions.setLong(2, now); suggestions.setLong(3, now);
                return expired + suggestions.executeUpdate();
            }
        }
    }

    private static void ensureCapacity(java.sql.Connection connection, String companionId,
                                       MemoryKind kind, long now) throws SQLException {
        int maximum = switch (kind) {
            case WORKING, PREFERENCE -> 128;
            case EPISODIC -> 512;
            case WORLD -> 1_024;
        };
        try (PreparedStatement count = connection.prepareStatement("""
                SELECT COUNT(*) FROM memory_fact
                WHERE companion_id=? AND kind=? AND (expires_at IS NULL OR expires_at>?)
                """)) {
            count.setString(1, required(companionId));
            count.setString(2, kind.name());
            count.setLong(3, now);
            try (ResultSet result = count.executeQuery()) {
                if (!result.next() || result.getInt(1) < maximum) return;
            }
        }
        if (kind != MemoryKind.WORKING) {
            throw new IllegalStateException("memory capacity reached for " + kind.name());
        }
        try (PreparedStatement evict = connection.prepareStatement("""
                DELETE FROM memory_fact WHERE memory_id=(
                  SELECT memory_id FROM memory_fact
                  WHERE companion_id=? AND kind='WORKING'
                    AND (expires_at IS NULL OR expires_at>?)
                  ORDER BY updated_at ASC,memory_id ASC LIMIT 1
                )
                """)) {
            evict.setString(1, companionId);
            evict.setLong(2, now);
            if (evict.executeUpdate() != 1) throw new IllegalStateException("working memory capacity reconciliation failed");
        }
    }

    private static int deleteExpiredSuggestions(java.sql.Connection connection, long now) throws SQLException {
        try (PreparedStatement suggestions = connection.prepareStatement("""
                UPDATE memory_suggestion SET status='EXPIRED',reviewed_by='SYSTEM_TTL',
                review_reason='TTL_EXPIRED',reviewed_at=?,updated_at=?
                WHERE status='QUARANTINED' AND expires_at<=?
                """)) {
            suggestions.setLong(1, now); suggestions.setLong(2, now); suggestions.setLong(3, now);
            return suggestions.executeUpdate();
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
                Instant.ofEpochMilli(result.getLong("updated_at")),
                result.getString("reviewed_by"), result.getString("review_reason"),
                nullableInstant(result, "reviewed_at"), result.getString("capsule_id"),
                result.getString("conflicting_memory_id") != null,
                result.getString("conflicting_memory_id"));
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException("memory identifier is invalid");
        return value.strip();
    }
}
