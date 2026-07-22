package com.mccompanion.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Produces and stores safe episode capsules without invoking a model or copying raw transcripts. */
public final class EpisodeCapsuleRepository {
    static final int MAX_CAPSULE_CHARS = 32_768;
    private static final int MAX_TASKS = 32;
    private static final int MAX_CHANGES = 64;
    private static final int MAX_LOCATIONS = 32;
    private static final int MAX_DECISIONS = 16;
    private static final int MAX_FAILURES = 32;
    private static final int MAX_EVIDENCE = 128;
    private final RuntimeDatabase database;
    private final Clock clock;

    public EpisodeCapsuleRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    EpisodeCapsuleRepository(RuntimeDatabase database, Clock clock) {
        this.database = java.util.Objects.requireNonNull(database);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public EpisodeCapsule generate(String companionId, String brainSessionId, String sourceSha) throws SQLException {
        required(companionId, "companionId");
        required(brainSessionId, "brainSessionId");
        if (sourceSha == null || !sourceSha.matches("[0-9a-fA-F]{7,64}")) {
            throw new IllegalArgumentException("sourceSha must be a git commit hash");
        }
        try (var connection = database.open()) {
            EpisodeCapsule existing = findBySource(connection, companionId, brainSessionId,
                    sourceSha.toLowerCase(Locale.ROOT));
            if (existing != null) return existing;
            long startedAt;
            long endedAt;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT created_at,updated_at FROM brain_session
                    WHERE session_id=? AND companion_id=?
                    """)) {
                statement.setString(1, brainSessionId); statement.setString(2, companionId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("brain session was not found in companion scope");
                    startedAt = row.getLong(1); endedAt = Math.max(startedAt, row.getLong(2));
                }
            }
            ObjectNode capsule = Json.object().put("companion_id", companionId)
                    .put("started_at", Instant.ofEpochMilli(startedAt).toString())
                    .put("ended_at", Instant.ofEpochMilli(endedAt).toString());
            capsule.set("task_summaries", tasks(connection, companionId, startedAt, endedAt));
            Changes changes = changes(connection, companionId, startedAt, endedAt);
            capsule.set("verified_world_changes", changes.world());
            capsule.set("verified_inventory_changes", changes.inventory());
            capsule.set("verified_locations", locations(connection, brainSessionId));
            Decisions decisions = decisions(connection, companionId, brainSessionId, startedAt, endedAt);
            capsule.set("ask_user_decisions", decisions.decisions());
            capsule.set("user_confirmed_choices", decisions.choices());
            capsule.set("failure_categories", failures(connection, brainSessionId));
            capsule.set("evidence_refs", evidence(connection, brainSessionId, changes.references()));
            capsule.put("source_sha", sourceSha.toLowerCase(Locale.ROOT));
            String episodeId = "episode-" + sha256(Json.write(capsule)).substring(0, 32);
            capsule.put("episode_id", episodeId);
            String serialized = Json.write(capsule);
            if (serialized.length() > MAX_CAPSULE_CHARS) {
                throw new IllegalStateException("episode capsule exceeds deterministic size budget");
            }
            long createdAt = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO episode_capsule(episode_id,companion_id,brain_session_id,started_at,ended_at,
                    capsule_json,source_sha,created_at) VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(companion_id,brain_session_id,source_sha) DO NOTHING
                    """)) {
                statement.setString(1, episodeId); statement.setString(2, companionId);
                statement.setString(3, brainSessionId); statement.setLong(4, startedAt);
                statement.setLong(5, endedAt); statement.setString(6, serialized);
                statement.setString(7, sourceSha.toLowerCase(Locale.ROOT)); statement.setLong(8, createdAt);
                statement.executeUpdate();
            }
            return find(connection, companionId, episodeId);
        }
    }

    public List<EpisodeCapsule> list(String companionId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 20));
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM episode_capsule WHERE companion_id=? ORDER BY ended_at DESC LIMIT ?
                """)) {
            statement.setString(1, required(companionId, "companionId")); statement.setInt(2, bounded);
            List<EpisodeCapsule> values = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) values.add(read(rows)); }
            return List.copyOf(values);
        }
    }

    public EpisodeCapsule require(String companionId, String episodeId) throws SQLException {
        try (var connection = database.open()) { return find(connection, companionId, episodeId); }
    }

    private static EpisodeCapsule find(java.sql.Connection connection, String companionId, String episodeId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM episode_capsule WHERE companion_id=? AND episode_id=?
                """)) {
            statement.setString(1, required(companionId, "companionId"));
            statement.setString(2, required(episodeId, "episodeId"));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("episode capsule was not found in companion scope");
                return read(row);
            }
        }
    }

    private static EpisodeCapsule findBySource(java.sql.Connection connection, String companionId,
                                               String brainSessionId, String sourceSha) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM episode_capsule
                WHERE companion_id=? AND brain_session_id=? AND source_sha=?
                """)) {
            statement.setString(1, required(companionId, "companionId"));
            statement.setString(2, required(brainSessionId, "brainSessionId"));
            statement.setString(3, sourceSha);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? read(row) : null; }
        }
    }

    private static ArrayNode tasks(java.sql.Connection connection, String companionId, long start, long end)
            throws SQLException {
        ArrayNode values = Json.MAPPER.createArrayNode();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id,task_type,state,updated_at FROM task
                WHERE companion_id=? AND updated_at BETWEEN ? AND ? ORDER BY updated_at,task_id LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setLong(2, start); statement.setLong(3, end);
            statement.setInt(4, MAX_TASKS);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) values.addObject()
                    .put("task_id", rows.getString(1)).put("task_type", rows.getString(2))
                    .put("state", rows.getString(3)).put("updated_at", Instant.ofEpochMilli(rows.getLong(4)).toString()); }
        }
        return values;
    }

    private static Changes changes(java.sql.Connection connection, String companionId, long start, long end)
            throws SQLException {
        ArrayNode world = Json.MAPPER.createArrayNode();
        ArrayNode inventory = Json.MAPPER.createArrayNode();
        List<String> refs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.evidence_id,e.action_type,e.created_at FROM action_evidence e
                JOIN task t ON t.task_id=e.task_id
                WHERE t.companion_id=? AND e.forbidden_write_detected=0 AND e.created_at BETWEEN ? AND ?
                ORDER BY e.created_at,e.evidence_id LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setLong(2, start); statement.setLong(3, end);
            statement.setInt(4, MAX_CHANGES * 2);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1); String action = rows.getString(2);
                    ObjectNode summary = Json.object().put("evidence_id", id).put("action_type", action)
                            .put("verified_at", Instant.ofEpochMilli(rows.getLong(3)).toString());
                    (action.toLowerCase(Locale.ROOT).contains("inventory") ? inventory : world).add(summary);
                    refs.add("action_evidence:" + id);
                    if (world.size() >= MAX_CHANGES && inventory.size() >= MAX_CHANGES) break;
                }
            }
        }
        trim(world, MAX_CHANGES); trim(inventory, MAX_CHANGES);
        return new Changes(world, inventory, List.copyOf(refs));
    }

    private static ArrayNode locations(java.sql.Connection connection, String sessionId) throws SQLException {
        ArrayNode values = Json.MAPPER.createArrayNode();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT call_id,observation_json,updated_at FROM brain_tool_call
                WHERE session_id=? AND success=1 AND terminal=1 ORDER BY updated_at,call_id LIMIT 256
                """)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next() && values.size() < MAX_LOCATIONS) {
                    JsonNode value = Json.parse(rows.getString(2));
                    JsonNode position = value.has("position") ? value.path("position") : value;
                    String dimension = position.path("dimension").asText("");
                    if (dimension.isBlank() || !position.path("x").isNumber() || !position.path("y").isNumber()
                            || !position.path("z").isNumber()) continue;
                    String key = dimension + ':' + position.path("x") + ':' + position.path("y") + ':' + position.path("z");
                    if (!seen.add(key)) continue;
                    values.addObject().put("source_call_id", rows.getString(1)).put("dimension", dimension)
                            .put("x", position.path("x").asDouble()).put("y", position.path("y").asDouble())
                            .put("z", position.path("z").asDouble())
                            .put("verified_at", Instant.ofEpochMilli(rows.getLong(3)).toString());
                }
            }
        }
        return values;
    }

    private static Decisions decisions(java.sql.Connection connection, String companionId, String sessionId,
                                       long start, long end) throws SQLException {
        ArrayNode decisions = Json.MAPPER.createArrayNode(); ArrayNode choices = Json.MAPPER.createArrayNode();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT question_id,state,reason,answer_json,updated_at FROM waiting_question
                WHERE companion_id=? AND brain_session_id=? AND updated_at BETWEEN ? AND ?
                ORDER BY updated_at,question_id LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setString(2, sessionId);
            statement.setLong(3, start); statement.setLong(4, end); statement.setInt(5, MAX_DECISIONS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    decisions.addObject().put("question_id", rows.getString(1)).put("state", rows.getString(2))
                            .put("reason", bounded(rows.getString(3), 128))
                            .put("decided_at", Instant.ofEpochMilli(rows.getLong(5)).toString());
                    String answer = rows.getString(4);
                    if (answer != null) {
                        String option = Json.parse(answer).path("optionId").asText("");
                        if (!option.isBlank()) choices.addObject().put("question_id", rows.getString(1))
                                .put("option_id", bounded(option, 128));
                    }
                }
            }
        }
        return new Decisions(decisions, choices);
    }

    private static ArrayNode failures(java.sql.Connection connection, String sessionId) throws SQLException {
        ArrayNode values = Json.MAPPER.createArrayNode(); java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT result_code FROM brain_tool_call WHERE session_id=? AND success=0
                ORDER BY updated_at,call_id LIMIT 128
                """)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next() && values.size() < MAX_FAILURES) if (seen.add(rows.getString(1))) values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static ArrayNode evidence(java.sql.Connection connection, String sessionId, List<String> actionRefs)
            throws SQLException {
        ArrayNode values = Json.MAPPER.createArrayNode();
        for (String ref : actionRefs) if (values.size() < MAX_EVIDENCE) values.add(ref);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT call_id,tool_name,result_code,task_id,behavior_id FROM brain_tool_call
                WHERE session_id=? AND terminal=1 ORDER BY updated_at,call_id LIMIT ?
                """)) {
            statement.setString(1, sessionId); statement.setInt(2, MAX_EVIDENCE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next() && values.size() < MAX_EVIDENCE) {
                    ObjectNode ref = values.addObject().put("call_id", rows.getString(1))
                            .put("tool", rows.getString(2)).put("result_code", rows.getString(3));
                    if (rows.getString(4) != null) ref.put("task_id", rows.getString(4));
                    if (rows.getString(5) != null) ref.put("behavior_id", rows.getString(5));
                }
            }
        }
        return values;
    }

    private static EpisodeCapsule read(ResultSet row) throws SQLException {
        JsonNode value = Json.parse(row.getString("capsule_json"));
        return new EpisodeCapsule(row.getString("episode_id"), row.getString("companion_id"),
                row.getString("brain_session_id"), Instant.ofEpochMilli(row.getLong("started_at")),
                Instant.ofEpochMilli(row.getLong("ended_at")), value.path("task_summaries"),
                value.path("verified_world_changes"), value.path("verified_inventory_changes"),
                value.path("verified_locations"), value.path("ask_user_decisions"),
                value.path("user_confirmed_choices"), value.path("failure_categories"),
                value.path("evidence_refs"), row.getString("source_sha"),
                Instant.ofEpochMilli(row.getLong("created_at")));
    }

    private static void trim(ArrayNode value, int max) { while (value.size() > max) value.remove(value.size() - 1); }
    private static String bounded(String value, int max) {
        if (value == null) return ""; String clean = value.strip(); return clean.length() <= max ? clean : clean.substring(0, max);
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException(name + " is invalid");
        return value.strip();
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Changes(ArrayNode world, ArrayNode inventory, List<String> references) { }
    private record Decisions(ArrayNode decisions, ArrayNode choices) { }
}
