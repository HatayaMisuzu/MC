package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.UUID;

/** Durable short-horizon task graph with optimistic revisions and restart-safe boundaries. */
public final class AgentPlanRepository {
    private final RuntimeDatabase database;
    private final Clock clock;

    public AgentPlanRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    AgentPlanRepository(RuntimeDatabase database, Clock clock) { this.database = database; this.clock = clock; }

    public DurablePlan create(String companionId, String requestText, AgentDecision decision) throws SQLException {
        if (decision.kind() != DecisionKind.CREATE_PLAN && decision.kind() != DecisionKind.REPLAN) {
            throw new IllegalArgumentException("Only plan decisions can be persisted");
        }
        if (decision.steps().isEmpty()) throw new IllegalArgumentException("Plan must have steps");
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement plan = connection.prepareStatement("""
                    INSERT INTO agent_plan(plan_id,companion_id,request_text,decision_json,state,revision,current_step,created_at,updated_at)
                    VALUES(?,?,?,?,?,0,0,?,?)
                    """)) {
                plan.setString(1, id); plan.setString(2, required(companionId)); plan.setString(3, required(requestText));
                plan.setString(4, encode(decision)); plan.setString(5, StepState.READY.name());
                plan.setLong(6, now); plan.setLong(7, now); plan.executeUpdate();
                try (PreparedStatement step = connection.prepareStatement("""
                        INSERT INTO agent_step(plan_id,step_index,state,definition_json,updated_at) VALUES(?,?,?,?,?)
                        """)) {
                    for (int index = 0; index < decision.steps().size(); index++) {
                        step.setString(1, id); step.setInt(2, index);
                        step.setString(3, index == 0 ? StepState.READY.name() : StepState.PENDING.name());
                        step.setString(4, encode(decision.steps().get(index))); step.setLong(5, now); step.addBatch();
                    }
                    step.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback(); throw failure;
            } finally { connection.setAutoCommit(true); }
        }
        return get(id).orElseThrow();
    }

    public DurablePlan transitionStep(String planId, long expectedRevision, int index, StepState next,
                                      JsonNode observation, String failureCode) throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                PlanHeader header = header(connection, planId);
                if (header.revision != expectedRevision) throw new IllegalStateException("STALE_PLAN_REVISION");
                StepState current = stepState(connection, planId, index);
                validateTransition(current, next);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE agent_step SET state=?,attempt=attempt+?,failure_code=?,observation_json=?,updated_at=?
                        WHERE plan_id=? AND step_index=?
                        """)) {
                    update.setString(1, next.name()); update.setInt(2, next == StepState.RUNNING ? 1 : 0);
                    update.setString(3, failureCode); update.setString(4, Json.write(observation == null ? Json.object() : observation));
                    update.setLong(5, now); update.setString(6, planId); update.setInt(7, index); update.executeUpdate();
                }
                StepState planState = next;
                int nextIndex = index;
                if (next == StepState.SUCCEEDED && index + 1 < header.stepCount) {
                    nextIndex = index + 1; planState = StepState.READY;
                    try (PreparedStatement ready = connection.prepareStatement(
                            "UPDATE agent_step SET state='READY',updated_at=? WHERE plan_id=? AND step_index=? AND state='PENDING'")) {
                        ready.setLong(1, now); ready.setString(2, planId); ready.setInt(3, nextIndex); ready.executeUpdate();
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE agent_plan SET state=?,revision=revision+1,current_step=?,updated_at=?
                        WHERE plan_id=? AND revision=?
                        """)) {
                    update.setString(1, planState.name()); update.setInt(2, nextIndex); update.setLong(3, now);
                    update.setString(4, planId); update.setLong(5, expectedRevision);
                    if (update.executeUpdate() != 1) throw new IllegalStateException("STALE_PLAN_REVISION");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        }
        return get(planId).orElseThrow();
    }

    public int pauseRunningForRecovery() throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement steps = connection.prepareStatement("""
                    UPDATE agent_step SET state='PAUSED',failure_code='RECOVERY_REQUIRED',updated_at=? WHERE state='RUNNING'
                    """); PreparedStatement plans = connection.prepareStatement("""
                    UPDATE agent_plan SET state='PAUSED',revision=revision+1,updated_at=? WHERE state='RUNNING'
                    """)) {
                steps.setLong(1, now); steps.executeUpdate(); plans.setLong(1, now); int changed = plans.executeUpdate();
                connection.commit(); return changed;
            } catch (SQLException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        }
    }

    public DurablePlan linkTask(String planId, long expectedRevision, int index, String taskId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_step SET task_id=?,updated_at=? WHERE plan_id=? AND step_index=? AND state='RUNNING'
                AND EXISTS(SELECT 1 FROM agent_plan WHERE plan_id=? AND revision=?)
                """)) {
            statement.setString(1, required(taskId)); statement.setLong(2, clock.millis()); statement.setString(3, planId);
            statement.setInt(4, index); statement.setString(5, planId); statement.setLong(6, expectedRevision);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("STALE_PLAN_REVISION");
        }
        return get(planId).orElseThrow();
    }

    public Optional<DurablePlan> forTask(String taskId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT plan_id FROM agent_step WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? get(result.getString(1)) : Optional.empty(); }
        }
    }

    public Optional<DurablePlan> get(String id) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_plan WHERE plan_id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                AgentDecision decision = decode(result.getString("decision_json"), AgentDecision.class);
                List<DurablePlan.DurableStep> steps = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM agent_step WHERE plan_id=? ORDER BY step_index")) {
                    query.setString(1, id);
                    try (ResultSet values = query.executeQuery()) {
                        while (values.next()) steps.add(new DurablePlan.DurableStep(values.getInt("step_index"),
                                decode(values.getString("definition_json"), PlanStep.class),
                                StepState.valueOf(values.getString("state")), values.getInt("attempt"),
                                values.getString("task_id"), values.getString("failure_code"),
                                Json.parse(values.getString("observation_json"))));
                    }
                }
                return Optional.of(new DurablePlan(id, result.getString("companion_id"), result.getString("request_text"),
                        decision, StepState.valueOf(result.getString("state")), result.getLong("revision"),
                        result.getInt("current_step"), List.copyOf(steps), Instant.ofEpochMilli(result.getLong("created_at")),
                        Instant.ofEpochMilli(result.getLong("updated_at"))));
            }
        }
    }

    private static PlanHeader header(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.revision,(SELECT COUNT(*) FROM agent_step s WHERE s.plan_id=p.plan_id) step_count
                FROM agent_plan p WHERE p.plan_id=?
                """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("PLAN_NOT_FOUND");
                return new PlanHeader(result.getLong(1), result.getInt(2));
            }
        }
    }

    private static StepState stepState(Connection connection, String id, int index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state FROM agent_step WHERE plan_id=? AND step_index=?")) {
            statement.setString(1, id); statement.setInt(2, index);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("STEP_NOT_FOUND");
                return StepState.valueOf(result.getString(1));
            }
        }
    }

    private static void validateTransition(StepState current, StepState next) {
        boolean valid = switch (current) {
            case PENDING -> next == StepState.READY || next == StepState.CANCELLED;
            case READY -> next == StepState.RUNNING || next == StepState.CANCELLED;
            case RUNNING -> next == StepState.PAUSED || next == StepState.BLOCKED || next.terminal();
            case PAUSED, BLOCKED -> next == StepState.READY || next == StepState.CANCELLED || next == StepState.FAILED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
        if (!valid) throw new IllegalStateException("Invalid step transition " + current + " -> " + next);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required value is blank");
        return value.strip();
    }
    private static String encode(Object value) { try { return Json.MAPPER.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); } }
    private static <T> T decode(String value, Class<T> type) { try { return Json.MAPPER.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private record PlanHeader(long revision, int stepCount) { }
}
