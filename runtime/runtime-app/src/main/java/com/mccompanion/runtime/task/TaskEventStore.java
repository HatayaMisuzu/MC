package com.mccompanion.runtime.task;

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

public final class TaskEventStore {
    private final RuntimeDatabase database;
    private final Clock clock;

    public TaskEventStore(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    public TaskEventStore(RuntimeDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    long append(Connection connection, String taskId, long revision, String type, JsonNode payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_event(task_id, revision, event_type, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, taskId);
            statement.setLong(2, revision);
            statement.setString(3, type);
            statement.setString(4, Json.write(payload == null ? Json.object() : payload));
            statement.setLong(5, clock.millis());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Task event sequence was not generated");
                }
                return keys.getLong(1);
            }
        }
    }

    public List<TaskEvent> list(String taskId) throws SQLException {
        List<TaskEvent> events = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT seq, task_id, revision, event_type, payload_json, created_at
                FROM task_event WHERE task_id=? ORDER BY seq
                """)) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new TaskEvent(result.getLong(1), result.getString(2), result.getLong(3),
                            result.getString(4), Json.parse(result.getString(5)),
                            Instant.ofEpochMilli(result.getLong(6))));
                }
            }
        }
        return List.copyOf(events);
    }
}
