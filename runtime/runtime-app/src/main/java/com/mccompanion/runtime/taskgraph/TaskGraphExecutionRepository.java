package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;
import com.mccompanion.runtime.tool.ToolContext;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Durable execution state. Hidden reasoning is never stored. */
public final class TaskGraphExecutionRepository {
    private final RuntimeDatabase database;
    private final Clock clock;

    public TaskGraphExecutionRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    TaskGraphExecutionRepository(RuntimeDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public TaskGraphExecutionRecord create(String executionId, ToolContext context, JsonNode graph,
                                           TaskGraphLimits limits, JsonNode inputs, JsonNode provenance) throws SQLException {
        String canonical = Json.canonical(graph);
        String hash = Digests.sha256(canonical);
        long now = clock.millis();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_graph_execution(execution_id,controller_id,brain_session_id,companion_id,
                graph_id,graph_version,graph_hash,graph_json,state,current_node_id,completed_nodes_json,
                tool_results_json,variables_json,checkpoints_json,waiting_question_json,permissions_json,
                limits_json,provenance_json,inputs_json,evidence_json,revision,result_code,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, '[]',0,'CREATED',?,?)
                """)) {
            statement.setString(1, required(executionId)); statement.setString(2, context.controllerId());
            statement.setString(3, context.brainSessionId()); statement.setString(4, context.companionId());
            statement.setString(5, graph.path("id").asText()); statement.setString(6, graph.path("version").asText());
            statement.setString(7, hash); statement.setString(8, canonical); statement.setString(9, "READY");
            statement.setString(10, null); statement.setString(11, "[]"); statement.setString(12, "{}");
            statement.setString(13, "{}"); statement.setString(14, "[]"); statement.setString(15, null);
            statement.setString(16, Json.write(graph.path("permissions")));
            statement.setString(17, Json.write(limits.toJson()));
            statement.setString(18, Json.write(provenance == null ? Json.object() : provenance));
            statement.setString(19, Json.write(inputs == null ? Json.object() : inputs));
            statement.setLong(20, now); statement.setLong(21, now); statement.executeUpdate();
        }
        return get(executionId).orElseThrow();
    }

    public TaskGraphExecutionRecord save(String executionId, long expectedRevision, String state,
                                         String currentNodeId, JsonNode completedNodes, JsonNode toolResults,
                                         JsonNode variables, JsonNode outputs, JsonNode checkpoints, JsonNode evidence,
                                         JsonNode waitingQuestion,
                                         JsonNode result, String resultCode) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE task_graph_execution SET state=?,current_node_id=?,completed_nodes_json=?,
                tool_results_json=?,variables_json=?,outputs_json=?,checkpoints_json=?,waiting_question_json=?,
                evidence_json=?,result_json=?,result_code=?,revision=revision+1,updated_at=?
                WHERE execution_id=? AND revision=?
                """)) {
            statement.setString(1, required(state)); statement.setString(2, blankToNull(currentNodeId));
            statement.setString(3, Json.write(completedNodes)); statement.setString(4, Json.write(toolResults));
            statement.setString(5, Json.write(variables)); statement.setString(6, Json.write(outputs));
            statement.setString(7, Json.write(checkpoints));
            statement.setString(8, waitingQuestion == null || waitingQuestion.isNull() ? null : Json.write(waitingQuestion));
            statement.setString(9, Json.write(evidence));
            statement.setString(10, Json.write(result == null ? Json.MAPPER.nullNode() : result));
            statement.setString(11, required(resultCode)); statement.setLong(12, clock.millis());
            statement.setString(13, required(executionId)); statement.setLong(14, expectedRevision);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("STALE_TASK_GRAPH_REVISION");
        }
        return get(executionId).orElseThrow();
    }

    public int markUnfinishedForReconciliation() throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE task_graph_execution SET state='RECONCILIATION_REQUIRED',
                result_code='RUNTIME_RESTARTED',revision=revision+1,updated_at=?
                WHERE state IN ('READY','RUNNING')
                """)) {
            statement.setLong(1, clock.millis());
            return statement.executeUpdate();
        }
    }

    public Optional<TaskGraphExecutionRecord> get(String executionId) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM task_graph_execution WHERE execution_id=?")) {
            statement.setString(1, required(executionId));
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                String waiting = row.getString("waiting_question_json");
                return Optional.of(new TaskGraphExecutionRecord(row.getString("execution_id"),
                        row.getString("controller_id"), row.getString("brain_session_id"),
                        row.getString("companion_id"), row.getString("graph_id"),
                        row.getString("graph_version"), row.getString("graph_hash"),
                        Json.parse(row.getString("graph_json")), row.getString("state"),
                        row.getString("current_node_id"), Json.parse(row.getString("completed_nodes_json")),
                        Json.parse(row.getString("tool_results_json")), Json.parse(row.getString("inputs_json")),
                        Json.parse(row.getString("variables_json")), Json.parse(row.getString("outputs_json")),
                        Json.parse(row.getString("checkpoints_json")),
                        Json.parse(row.getString("evidence_json")),
                        waiting == null ? Json.MAPPER.nullNode() : Json.parse(waiting),
                        Json.parse(row.getString("permissions_json")), Json.parse(row.getString("limits_json")),
                        Json.parse(row.getString("provenance_json")), Json.parse(row.getString("result_json")),
                        row.getLong("revision"),
                        row.getString("result_code"), Instant.ofEpochMilli(row.getLong("created_at")),
                        Instant.ofEpochMilli(row.getLong("updated_at"))));
            }
        }
    }

    public List<String> waitingTimeExecutionIds() throws SQLException {
        ArrayList<String> values = new ArrayList<>();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT execution_id FROM task_graph_execution
                WHERE state='WAITING' AND json_extract(waiting_question_json,'$.kind')='TIME'
                ORDER BY created_at, execution_id
                """);
             var rows = statement.executeQuery()) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return List.copyOf(values);
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException("invalid value");
        return value.strip();
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
