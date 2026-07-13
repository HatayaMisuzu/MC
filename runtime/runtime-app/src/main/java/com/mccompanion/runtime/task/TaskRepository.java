package com.mccompanion.runtime.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Set;
import java.util.UUID;

public final class TaskRepository {
    private static final Set<TaskState> RECOVERABLE = Set.of(
            TaskState.CREATED, TaskState.ACCEPTED, TaskState.RUNNING, TaskState.WAITING, TaskState.PAUSED,
            TaskState.BLOCKED, TaskState.RECONCILIATION_REQUIRED);
    private final RuntimeDatabase database;
    private final TaskEventStore events;
    private final Clock clock;

    public TaskRepository(RuntimeDatabase database, TaskEventStore events) {
        this(database, events, Clock.systemUTC());
    }

    public TaskRepository(RuntimeDatabase database, TaskEventStore events, Clock clock) {
        this.database = database;
        this.events = events;
        this.clock = clock;
    }

    public TaskRecord create(String companionId, TaskType type, String requestText, JsonNode payload) throws SQLException {
        return create(companionId, type, requestText, payload, null, null, 0);
    }

    public TaskRecord create(String companionId, TaskType type, String requestText, JsonNode payload,
                             String commandId, String commandType, long controlEpoch) throws SQLException {
        requireIdentifier(companionId, "companionId");
        java.util.Objects.requireNonNull(type, "type");
        if (requestText != null && requestText.length() > 4_096) {
            throw new IllegalArgumentException("requestText exceeds 4096 characters");
        }
        if ((commandId == null) != (commandType == null)) {
            throw new IllegalArgumentException("commandId and commandType must be supplied together");
        }
        if (controlEpoch < 0) {
            throw new IllegalArgumentException("controlEpoch must be non-negative");
        }
        String taskId = UUID.randomUUID().toString();
        String behaviorId = type == TaskType.STATUS ? null : UUID.randomUUID().toString();
        long now = clock.millis();
        JsonNode safePayload = payload == null ? Json.object() : payload.deepCopy();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO task(task_id, root_task_id, parent_task_id, companion_id, task_type, state,
                          revision, request_text, payload_json, behavior_id, behavior_revision,
                          reconciliation_required, created_at, updated_at, control_epoch)
                        VALUES (?, ?, NULL, ?, ?, ?, 0, ?, ?, ?, 0, 0, ?, ?, ?)
                        """)) {
                    statement.setString(1, taskId);
                    statement.setString(2, taskId);
                    statement.setString(3, companionId);
                    statement.setString(4, type.name());
                    statement.setString(5, TaskState.CREATED.name());
                    statement.setString(6, requestText == null ? "" : requestText);
                    statement.setString(7, Json.write(safePayload));
                    statement.setString(8, behaviorId);
                    statement.setLong(9, now);
                    statement.setLong(10, now);
                    statement.setLong(11, controlEpoch);
                    statement.executeUpdate();
                }
                ObjectNode eventPayload = Json.object().put("taskType", type.name());
                if (behaviorId != null) {
                    eventPayload.put("behaviorId", behaviorId);
                }
                events.append(connection, taskId, 0, "TaskCreated", eventPayload);
                if (commandId != null) {
                    insertCommand(connection, commandId, taskId, commandType);
                }
                upsertSnapshot(connection, taskId);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return get(taskId).orElseThrow();
    }

    public void linkCommand(String commandId, String taskId, String commandType) throws SQLException {
        try (Connection connection = database.open()) {
            insertCommand(connection, commandId, taskId, commandType);
        }
    }

    public Optional<TaskRecord> forCommand(String commandId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT t.* FROM task t JOIN task_command c ON c.task_id=t.task_id WHERE c.command_id=?
                """)) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public Optional<CommandLink> commandLink(String commandId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT t.*, c.command_type FROM task t JOIN task_command c ON c.task_id=t.task_id
                WHERE c.command_id=?
                """)) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new CommandLink(read(result), result.getString("command_type")))
                        : Optional.empty();
            }
        }
    }

    public Optional<TaskRecord> forBehavior(String behaviorId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM task WHERE behavior_id=?")) {
            statement.setString(1, behaviorId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public TaskRecord transition(String taskId, long expectedRevision, TaskState next, String eventType,
                                 JsonNode eventPayload) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                TaskRecord current = get(connection, taskId)
                        .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
                if (current.revision() != expectedRevision) {
                    throw new StaleTaskRevisionException(expectedRevision, current.revision());
                }
                validateTransition(current.state(), next);
                long revision = Math.addExact(current.revision(), 1);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE task SET state=?, revision=?, reconciliation_required=?, updated_at=?
                        WHERE task_id=? AND revision=?
                        """)) {
                    statement.setString(1, next.name());
                    statement.setLong(2, revision);
                    statement.setInt(3, next == TaskState.RECONCILIATION_REQUIRED ? 1 : 0);
                    statement.setLong(4, clock.millis());
                    statement.setString(5, taskId);
                    statement.setLong(6, expectedRevision);
                    if (statement.executeUpdate() != 1) {
                        throw new StaleTaskRevisionException(expectedRevision, -1);
                    }
                }
                events.append(connection, taskId, revision, eventType,
                        eventPayload == null ? Json.object() : eventPayload);
                upsertSnapshot(connection, taskId);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return get(taskId).orElseThrow();
    }

    public TaskRecord updateBehavior(String taskId, long expectedRevision, long behaviorRevision,
                                     TaskState next, String eventType, JsonNode payload) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                TaskRecord current = get(connection, taskId).orElseThrow();
                if (current.revision() != expectedRevision) {
                    throw new StaleTaskRevisionException(expectedRevision, current.revision());
                }
                if (behaviorRevision < current.behaviorRevision()) {
                    throw new StaleTaskRevisionException(current.behaviorRevision(), behaviorRevision);
                }
                validateTransition(current.state(), next);
                long revision = Math.addExact(current.revision(), 1);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE task SET state=?, revision=?, behavior_revision=?, reconciliation_required=0, updated_at=?
                        WHERE task_id=? AND revision=?
                        """)) {
                    statement.setString(1, next.name());
                    statement.setLong(2, revision);
                    statement.setLong(3, behaviorRevision);
                    statement.setLong(4, clock.millis());
                    statement.setString(5, taskId);
                    statement.setLong(6, expectedRevision);
                    if (statement.executeUpdate() != 1) {
                        throw new StaleTaskRevisionException(expectedRevision, -1);
                    }
                }
                events.append(connection, taskId, revision, eventType, payload == null ? Json.object() : payload);
                upsertBehavior(connection, current, behaviorRevision, next, eventType, payload);
                upsertSnapshot(connection, taskId);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return get(taskId).orElseThrow();
    }

    public synchronized List<TaskRecord> markUnfinishedForReconciliation() throws SQLException {
        List<TaskRecord> affected = unfinished();
        List<TaskRecord> marked = new ArrayList<>();
        for (TaskRecord task : affected) {
            if (task.state() == TaskState.RECONCILIATION_REQUIRED) {
                marked.add(task);
            } else {
                ObjectNode payload = Json.object().put("previousState", task.state().name());
                marked.add(transition(task.taskId(), task.revision(), TaskState.RECONCILIATION_REQUIRED,
                        "ReconciliationRequired", payload));
            }
        }
        return List.copyOf(marked);
    }

    public TaskRecord reconcile(String taskId, String remoteBehaviorId, long remoteBehaviorRevision,
                                TaskState remoteState) throws SQLException {
        TaskRecord task = get(taskId).orElseThrow();
        if (task.state() != TaskState.RECONCILIATION_REQUIRED) {
            return task;
        }
        boolean identityMatches = task.behaviorId() != null && task.behaviorId().equals(remoteBehaviorId)
                && remoteBehaviorRevision >= task.behaviorRevision();
        if (!identityMatches || remoteState == null || remoteState == TaskState.RECONCILIATION_REQUIRED) {
            ObjectNode reason = Json.object().put("reason", "BEHAVIOR_ID_OR_REVISION_MISMATCH");
            return transition(taskId, task.revision(), TaskState.CANCELLED, "ReconciliationCancelled", reason);
        }
        return updateBehavior(taskId, task.revision(), remoteBehaviorRevision, remoteState,
                "ReconciliationCompleted", Json.object().put("remoteState", remoteState.name()));
    }

    public Optional<TaskRecord> get(String taskId) throws SQLException {
        try (Connection connection = database.open()) {
            return get(connection, taskId);
        }
    }

    public Optional<TaskRecord> activeForCompanion(String companionId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM task WHERE companion_id=? AND state NOT IN ('COMPLETED','FAILED','CANCELLED')
                ORDER BY updated_at DESC LIMIT 1
                """)) {
            statement.setString(1, companionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public List<TaskEvent> events(String taskId) throws SQLException {
        return events.list(taskId);
    }

    public List<TaskRecord> unfinished() throws SQLException {
        List<TaskRecord> tasks = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM task WHERE state IN
                  ('CREATED','ACCEPTED','RUNNING','WAITING','PAUSED','BLOCKED','RECONCILIATION_REQUIRED')
                ORDER BY created_at
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                TaskRecord task = read(result);
                if (RECOVERABLE.contains(task.state())) {
                    tasks.add(task);
                }
            }
        }
        return List.copyOf(tasks);
    }

    private Optional<TaskRecord> get(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM task WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private void insertCommand(Connection connection, String commandId, String taskId, String commandType)
            throws SQLException {
        requireIdentifier(commandId, "commandId");
        requireIdentifier(taskId, "taskId");
        requireIdentifier(commandType, "commandType");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_command(command_id, task_id, command_type, created_at) VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, commandId);
            statement.setString(2, taskId);
            statement.setString(3, commandType);
            statement.setLong(4, clock.millis());
            statement.executeUpdate();
        }
    }

    private void upsertSnapshot(Connection connection, String taskId) throws SQLException {
        TaskRecord task = get(connection, taskId).orElseThrow();
        ObjectNode snapshot = Json.object()
                .put("taskId", task.taskId())
                .put("companionId", task.companionId())
                .put("type", task.type().name())
                .put("state", task.state().name())
                .put("revision", task.revision())
                .put("behaviorRevision", task.behaviorRevision())
                .put("controlEpoch", task.controlEpoch())
                .put("reconciliationRequired", task.reconciliationRequired());
        if (task.behaviorId() != null) {
            snapshot.put("behaviorId", task.behaviorId());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_snapshot(task_id, revision, snapshot_json, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET revision=excluded.revision,
                  snapshot_json=excluded.snapshot_json, updated_at=excluded.updated_at
                """)) {
            statement.setString(1, taskId);
            statement.setLong(2, task.revision());
            statement.setString(3, Json.write(snapshot));
            statement.setLong(4, clock.millis());
            statement.executeUpdate();
        }
    }

    private void upsertBehavior(Connection connection, TaskRecord task, long behaviorRevision,
                                TaskState state, String eventType, JsonNode payload) throws SQLException {
        if (task.behaviorId() == null) {
            return;
        }
        long now = clock.millis();
        boolean terminal = state.terminal();
        String failureCode = state == TaskState.FAILED && payload != null ? payload.path("code").asText(null) : null;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO behavior_run(behavior_id, task_id, companion_id, behavior_type, revision, state,
                  started_at, updated_at, completed_at, failure_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(behavior_id) DO UPDATE SET revision=excluded.revision, state=excluded.state,
                  updated_at=excluded.updated_at, completed_at=excluded.completed_at,
                  failure_code=excluded.failure_code
                """)) {
            statement.setString(1, task.behaviorId());
            statement.setString(2, task.taskId());
            statement.setString(3, task.companionId());
            statement.setString(4, task.type().name());
            statement.setLong(5, behaviorRevision);
            statement.setString(6, state.name());
            statement.setLong(7, now);
            statement.setLong(8, now);
            if (terminal) statement.setLong(9, now); else statement.setNull(9, java.sql.Types.BIGINT);
            statement.setString(10, failureCode);
            statement.executeUpdate();
        }
    }

    private TaskRecord read(ResultSet result) throws SQLException {
        String parent = result.getString("parent_task_id");
        String behavior = result.getString("behavior_id");
        return new TaskRecord(result.getString("task_id"), result.getString("root_task_id"), parent,
                result.getString("companion_id"), TaskType.valueOf(result.getString("task_type")),
                TaskState.valueOf(result.getString("state")), result.getLong("revision"),
                result.getString("request_text"), Json.parse(result.getString("payload_json")), behavior,
                result.getLong("behavior_revision"), result.getLong("control_epoch"),
                result.getInt("reconciliation_required") != 0,
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")));
    }

    private static void validateTransition(TaskState current, TaskState next) {
        java.util.Objects.requireNonNull(current, "current");
        java.util.Objects.requireNonNull(next, "next");
        boolean allowed = switch (current) {
            case CREATED -> Set.of(TaskState.ACCEPTED, TaskState.FAILED, TaskState.CANCELLED,
                    TaskState.RECONCILIATION_REQUIRED).contains(next);
            case ACCEPTED -> Set.of(TaskState.ACCEPTED, TaskState.RUNNING, TaskState.WAITING, TaskState.PAUSED,
                    TaskState.BLOCKED, TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED,
                    TaskState.RECONCILIATION_REQUIRED).contains(next);
            case RUNNING -> Set.of(TaskState.RUNNING, TaskState.WAITING, TaskState.PAUSED, TaskState.BLOCKED,
                    TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED,
                    TaskState.RECONCILIATION_REQUIRED).contains(next);
            case WAITING -> Set.of(TaskState.WAITING, TaskState.RUNNING, TaskState.PAUSED, TaskState.BLOCKED,
                    TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED,
                    TaskState.RECONCILIATION_REQUIRED).contains(next);
            case PAUSED -> Set.of(TaskState.PAUSED, TaskState.RUNNING, TaskState.WAITING, TaskState.BLOCKED,
                    TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED,
                    TaskState.RECONCILIATION_REQUIRED).contains(next);
            case BLOCKED -> Set.of(TaskState.BLOCKED, TaskState.RUNNING, TaskState.WAITING, TaskState.PAUSED,
                    TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED,
                    TaskState.RECONCILIATION_REQUIRED).contains(next);
            case RECONCILIATION_REQUIRED -> Set.of(TaskState.ACCEPTED, TaskState.RUNNING, TaskState.WAITING,
                    TaskState.PAUSED, TaskState.BLOCKED, TaskState.COMPLETED, TaskState.FAILED,
                    TaskState.CANCELLED).contains(next);
            case COMPLETED, FAILED, CANCELLED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Invalid task transition from " + current + " to " + next);
        }
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(field + " is missing or invalid");
        }
        return value;
    }

    public static final class StaleTaskRevisionException extends IllegalStateException {
        private final long expected;
        private final long actual;

        public StaleTaskRevisionException(long expected, long actual) {
            super("Stale task revision: expected " + expected + ", actual " + actual);
            this.expected = expected;
            this.actual = actual;
        }

        public long expected() { return expected; }
        public long actual() { return actual; }
    }

    public record CommandLink(TaskRecord task, String commandType) { }
}
