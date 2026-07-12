package com.mccompanion.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;

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

    public void upsert(String companionId, String sessionId, String worldId, String ownerId,
                       String displayName, JsonNode status) throws SQLException {
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
            statement.setString(6, Json.write(status == null ? Json.object() : status));
            statement.setLong(7, clock.millis());
            statement.executeUpdate();
        }
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
