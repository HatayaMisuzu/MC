package com.mccompanion.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.worldmodel.WorldModel;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CompanionRepository {
    private final RuntimeDatabase database;
    private final Clock clock;

    public CompanionRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    public CompanionRepository(RuntimeDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public synchronized void upsert(String companionId, String sessionId, String worldId, String ownerId,
                       String displayName, JsonNode status) throws SQLException {
        JsonNode previous = get(companionId).map(CompanionRecord::status).orElseGet(Json::object);
        WorldModel model = new WorldModel(previous.path(WorldModel.STORAGE_FIELD));
        model.observe(worldId, sessionId, status == null ? Json.object() : status, clock.instant());
        ObjectNode persisted = status != null && status.isObject() ? status.deepCopy() : Json.object();
        // Reserved Runtime state is never accepted from the Body payload.
        persisted.set(WorldModel.STORAGE_FIELD, model.saved());
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion(companion_id, session_id, world_id, owner_id, display_name, status_json, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(companion_id) DO UPDATE SET session_id=excluded.session_id,
                  world_id=excluded.world_id, owner_id=excluded.owner_id,
                  display_name=excluded.display_name, status_json=excluded.status_json,
                  last_seen_at=excluded.last_seen_at
                """)) {
            statement.setString(1, companionId);
            statement.setString(2, sessionId);
            statement.setString(3, worldId);
            statement.setString(4, ownerId);
            statement.setString(5, displayName == null || displayName.isBlank() ? companionId : displayName);
            statement.setString(6, Json.write(persisted));
            statement.setLong(7, clock.millis());
            statement.executeUpdate();
        }
    }

    public WorldModel worldModel(String companionId) throws SQLException {
        return new WorldModel(get(companionId).map(CompanionRecord::status)
                .orElseGet(Json::object).path(WorldModel.STORAGE_FIELD));
    }

    public Instant observationTime() { return clock.instant(); }

    /** All updates share the repository monitor with snapshots; no lost invalidations. */
    public synchronized void updateWorldModel(String companionId, java.util.function.Consumer<WorldModel> update)
            throws SQLException {
        var companion = get(companionId);
        if (companion.isEmpty()) return;
        ObjectNode status = (ObjectNode) companion.orElseThrow().status().deepCopy();
        WorldModel model = new WorldModel(status.path(WorldModel.STORAGE_FIELD));
        update.accept(model);
        status.set(WorldModel.STORAGE_FIELD, model.saved());
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE companion SET status_json=? WHERE companion_id=?")) {
            statement.setString(1, Json.write(status)); statement.setString(2, companionId);
            statement.executeUpdate();
        }
    }

    public void invalidateWorldModel(String companionId, String reason) throws SQLException {
        updateWorldModel(companionId, model -> model.invalidateAll(reason, clock.instant()));
    }

    public Optional<CompanionRecord> get(String companionId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM companion WHERE companion_id=?")) {
            statement.setString(1, companionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public List<CompanionRecord> list() throws SQLException {
        List<CompanionRecord> values = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM companion ORDER BY display_name, companion_id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                values.add(read(result));
            }
        }
        return List.copyOf(values);
    }

    private static CompanionRecord read(ResultSet result) throws SQLException {
        return new CompanionRecord(result.getString("companion_id"), result.getString("session_id"),
                result.getString("world_id"), result.getString("owner_id"), result.getString("display_name"),
                Json.parse(result.getString("status_json")), Instant.ofEpochMilli(result.getLong("last_seen_at")));
    }
}
