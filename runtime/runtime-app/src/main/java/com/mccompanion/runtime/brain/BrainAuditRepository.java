package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.DurableExecutionReceipt;
import com.mccompanion.runtime.tool.ToolResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;

/** Durable protocol audit. Hidden model reasoning is never stored. */
public final class BrainAuditRepository {
    private final RuntimeDatabase database;
    private final Clock clock;
    public BrainAuditRepository(RuntimeDatabase database) { this(database, Clock.systemUTC()); }
    BrainAuditRepository(RuntimeDatabase database, Clock clock) { this.database = database; this.clock = clock; }

    public int interruptActiveSessions() {
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                long now = clock.millis();
                try (PreparedStatement calls = connection.prepareStatement("""
                        SELECT session_id,call_id,observation_json FROM brain_tool_call
                        WHERE terminal=0 AND durable_active=0
                        """); var rows = calls.executeQuery()) {
                    while (rows.next()) {
                        JsonNode previous = Json.parse(rows.getString("observation_json"));
                        var interrupted = previous.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) previous.deepCopy()
                                : Json.object();
                        interrupted.put("state", "INTERRUPTED").put("message", "Runtime restarted before terminal observation");
                        try (PreparedStatement update = connection.prepareStatement("""
                                UPDATE brain_tool_call SET success=0,result_code='RUNTIME_RESTARTED',
                                observation_json=?,terminal=1,state='INTERRUPTED',durable_active=0,updated_at=?
                                WHERE session_id=? AND call_id=? AND terminal=0
                                """)) {
                            update.setString(1, Json.write(interrupted)); update.setLong(2, now);
                            update.setString(3, rows.getString("session_id")); update.setString(4, rows.getString("call_id"));
                            update.executeUpdate();
                        }
                    }
                }
                int count;
                try (PreparedStatement sessions = connection.prepareStatement("""
                        UPDATE brain_session SET state='INTERRUPTED',last_code='RUNTIME_RESTARTED',updated_at=?
                        WHERE state='ACTIVE'
                        """)) {
                    sessions.setLong(1, now); count = sessions.executeUpdate();
                }
                connection.commit();
                return count;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public Optional<BrainSession> interrupted(String controllerId, String companionId) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT session_id,controller_id,companion_id,created_at FROM brain_session
                WHERE controller_id=? AND companion_id=? AND state='INTERRUPTED'
                ORDER BY updated_at DESC LIMIT 1
                """)) {
            statement.setString(1, controllerId); statement.setString(2, companionId);
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(new BrainSession(row.getString("session_id"),
                        row.getString("controller_id"), row.getString("companion_id"),
                        Instant.ofEpochMilli(row.getLong("created_at")))) : Optional.empty();
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public List<ToolResult> undeliveredTerminal(String sessionId) {
        List<ToolResult> results = new ArrayList<>();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT call_id,tool_name,success,result_code,observation_json FROM brain_tool_call
                WHERE session_id=? AND terminal=1 AND delivered_at IS NULL ORDER BY created_at
                """)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) results.add(new ToolResult(rows.getString("call_id"), rows.getString("tool_name"),
                        rows.getInt("success") != 0, rows.getString("result_code"),
                        Json.parse(rows.getString("observation_json")), true));
            }
            return List.copyOf(results);
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public Optional<AuditedToolCall> tool(String sessionId, String callId) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT tool_name,arguments_json,success,result_code,observation_json,terminal
                FROM brain_tool_call WHERE session_id=? AND call_id=?
                """)) {
            statement.setString(1, sessionId); statement.setString(2, callId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                ToolCall call = new ToolCall(callId, row.getString("tool_name"),
                        Json.parse(row.getString("arguments_json")));
                ToolResult result = new ToolResult(callId, call.name(), row.getInt("success") != 0,
                        row.getString("result_code"), Json.parse(row.getString("observation_json")),
                        row.getInt("terminal") != 0);
                return Optional.of(new AuditedToolCall(call, result));
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void opened(BrainSession session, String provider) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO brain_session(session_id,controller_id,companion_id,provider,state,last_code,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            long now = clock.millis();
            statement.setString(1, session.sessionId()); statement.setString(2, session.controllerId());
            statement.setString(3, session.companionId()); statement.setString(4, provider);
            statement.setString(5, "ACTIVE"); statement.setString(6, "OPENED");
            statement.setLong(7, now); statement.setLong(8, now); statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void tool(String sessionId, ToolCall call, ToolResult result) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO brain_tool_call(session_id,call_id,tool_name,arguments_json,success,result_code,
                observation_json,terminal,created_at,state,task_id,behavior_id,updated_at,durable_active)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(session_id,call_id) DO UPDATE SET success=excluded.success,
                result_code=excluded.result_code,observation_json=excluded.observation_json,
                terminal=excluded.terminal,state=excluded.state,
                task_id=COALESCE(excluded.task_id,brain_tool_call.task_id),
                behavior_id=COALESCE(excluded.behavior_id,brain_tool_call.behavior_id),
                updated_at=excluded.updated_at,durable_active=excluded.durable_active
                """)) {
            long now = clock.millis();
            statement.setString(1, sessionId); statement.setString(2, call.callId()); statement.setString(3, call.name());
            statement.setString(4, Json.write(call.arguments())); statement.setInt(5, result.success() ? 1 : 0);
            statement.setString(6, result.code()); statement.setString(7, Json.write(result.observation()));
            statement.setInt(8, result.terminal() ? 1 : 0); statement.setLong(9, now);
            statement.setString(10, toolState(result));
            statement.setString(11, textOrNull(result.observation(), "taskId"));
            statement.setString(12, textOrNull(result.observation(), "behaviorId"));
            statement.setLong(13, now);
            statement.setInt(14, isDurableActive(result) ? 1 : 0);
            statement.executeUpdate();
            if (DurableExecutionReceipt.isTerminalObservation(result.observation())) {
                Optional<DurableExecutionReceipt.Handle> handle =
                        DurableExecutionReceipt.handleFromObservation(result.observation());
                if (handle.isPresent()) clearDurableHandle(connection, sessionId, handle.get());
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    /** Returns accepted Task/Task Graph calls that still represent controllable durable work. */
    public List<DurableCall> activeDurableCalls() {
        List<DurableCall> recovered = new ArrayList<>();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT c.session_id,c.call_id,c.tool_name,c.arguments_json,
                       s.controller_id,s.companion_id,c.observation_json
                FROM brain_tool_call c JOIN brain_session s ON s.session_id=c.session_id
                WHERE c.durable_active=1 ORDER BY c.updated_at
                """)) {
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Optional<DurableExecutionReceipt.Handle> handle =
                            DurableExecutionReceipt.handleFromObservation(Json.parse(rows.getString("observation_json")));
                    if (handle.isPresent()) {
                        String sessionId = rows.getString("session_id");
                        String callId = rows.getString("call_id");
                        recovered.add(new DurableCall(sessionId, callId,
                                new ToolCall(callId, rows.getString("tool_name"),
                                        Json.parse(rows.getString("arguments_json"))),
                                rows.getString("controller_id"), rows.getString("companion_id"), handle.get()));
                    }
                }
            }
            return List.copyOf(recovered);
        } catch (SQLException failure) { throw persistence(failure); }
    }

    /** Clears one durable identity idempotently after a verified terminal observation. */
    public void clearDurableHandle(String sessionId, DurableExecutionReceipt.Handle handle) {
        try (var connection = database.open()) {
            clearDurableHandle(connection, sessionId, handle);
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void delivered(String sessionId, String callId) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE brain_tool_call SET delivered_at=?,updated_at=?
                WHERE session_id=? AND call_id=? AND terminal=1 AND delivered_at IS NULL
                """)) {
            long now = clock.millis();
            statement.setLong(1, now); statement.setLong(2, now);
            statement.setString(3, sessionId); statement.setString(4, callId); statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public void state(String sessionId, String state, String code) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE brain_session SET state=?,last_code=?,updated_at=? WHERE session_id=?")) {
            statement.setString(1, state); statement.setString(2, code); statement.setLong(3, clock.millis());
            statement.setString(4, sessionId); statement.executeUpdate();
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public SemanticStateSnapshot semanticState(String sessionId) {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT controller_id,companion_id,state_json,revision,authored_at
                FROM brain_semantic_state WHERE session_id=?
                """)) {
            statement.setString(1, sessionId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return null;
                BrainSemanticState state = BrainSemanticState.parse(Json.parse(row.getString("state_json")));
                return new SemanticStateSnapshot(sessionId, row.getString("controller_id"),
                        row.getString("companion_id"), state, row.getLong("revision"),
                        Instant.ofEpochMilli(row.getLong("authored_at")));
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public SemanticStateSnapshot semanticState(String sessionId, String controllerId, String companionId,
                                               BrainSemanticState state) {
        if (state == null) throw new IllegalArgumentException("semantic state is required");
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement scope = connection.prepareStatement("""
                        SELECT controller_id,companion_id FROM brain_session WHERE session_id=?
                        """)) {
                    scope.setString(1, sessionId);
                    try (var row = scope.executeQuery()) {
                        if (!row.next() || !controllerId.equals(row.getString("controller_id"))
                                || !companionId.equals(row.getString("companion_id"))) {
                            throw new IllegalArgumentException("BRAIN_SEMANTIC_STATE_SCOPE_MISMATCH");
                        }
                    }
                }
                long authoredAt = clock.millis();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO brain_semantic_state(session_id,controller_id,companion_id,state_json,revision,authored_at)
                        VALUES(?,?,?,?,1,?)
                        ON CONFLICT(session_id) DO UPDATE SET state_json=excluded.state_json,
                        revision=brain_semantic_state.revision+1,authored_at=excluded.authored_at
                        WHERE brain_semantic_state.controller_id=excluded.controller_id
                        AND brain_semantic_state.companion_id=excluded.companion_id
                        """)) {
                    statement.setString(1, sessionId);
                    statement.setString(2, controllerId);
                    statement.setString(3, companionId);
                    statement.setString(4, Json.write(state.toJson()));
                    statement.setLong(5, authoredAt);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("BRAIN_SEMANTIC_STATE_SCOPE_MISMATCH");
                    }
                }
                long revision;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT revision FROM brain_semantic_state WHERE session_id=?")) {
                    read.setString(1, sessionId);
                    try (var row = read.executeQuery()) {
                        if (!row.next()) throw new SQLException("semantic state write was not visible");
                        revision = row.getLong(1);
                    }
                }
                connection.commit();
                return new SemanticStateSnapshot(sessionId, controllerId, companionId, state, revision,
                        Instant.ofEpochMilli(authoredAt));
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public BrainBehaviorSettings behaviorSettings(String companionId) {
        if (companionId == null || companionId.isBlank() || companionId.length() > 256) {
            throw new IllegalArgumentException("companionId is required and bounded");
        }
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT initiative_mode,personality_mode,revision,updated_by,updated_at
                FROM brain_behavior_settings WHERE companion_id=?
                """)) {
            statement.setString(1, companionId.strip());
            try (var row = statement.executeQuery()) {
                if (!row.next()) return BrainBehaviorSettings.defaults(companionId);
                return new BrainBehaviorSettings(companionId,
                        BrainSemanticState.InitiativeMode.valueOf(row.getString("initiative_mode")),
                        BrainSemanticState.PersonalityMode.valueOf(row.getString("personality_mode")),
                        row.getLong("revision"), row.getString("updated_by"),
                        Instant.ofEpochMilli(row.getLong("updated_at")));
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public BrainBehaviorSettings updateBehaviorSettings(String companionId,
                                                        BrainSemanticState.InitiativeMode initiativeMode,
                                                        BrainSemanticState.PersonalityMode personalityMode,
                                                        String updatedBy) {
        BrainBehaviorSettings requested = new BrainBehaviorSettings(companionId, initiativeMode,
                personalityMode, 0, updatedBy, null);
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO brain_behavior_settings(companion_id,initiative_mode,personality_mode,revision,updated_by,updated_at)
                VALUES(?,?,?,1,?,?)
                ON CONFLICT(companion_id) DO UPDATE SET initiative_mode=excluded.initiative_mode,
                personality_mode=excluded.personality_mode,revision=brain_behavior_settings.revision+1,
                updated_by=excluded.updated_by,updated_at=excluded.updated_at
                """)) {
            long now = clock.millis();
            statement.setString(1, requested.companionId());
            statement.setString(2, requested.initiativeMode().name());
            statement.setString(3, requested.personalityMode().name());
            statement.setString(4, requested.updatedBy().isBlank() ? "LOCAL_MANAGEMENT_USER" : requested.updatedBy());
            statement.setLong(5, now);
            statement.executeUpdate();
            return behaviorSettings(companionId);
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public CompletionClaimSnapshot completionClaim(String sessionId, BrainCompletionClaim claim) {
        if (claim == null) throw new IllegalArgumentException("completion claim is required");
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                long sequence;
                try (PreparedStatement next = connection.prepareStatement("""
                        SELECT COALESCE(MAX(claim_sequence),0)+1 FROM brain_completion_claim WHERE session_id=?
                        """)) {
                    next.setString(1, sessionId);
                    try (var row = next.executeQuery()) {
                        if (!row.next()) throw new SQLException("claim sequence is unavailable");
                        sequence = row.getLong(1);
                    }
                }
                long createdAt = clock.millis();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO brain_completion_claim(session_id,claim_sequence,certainty,claim_text,
                        observation_call_id,task_id,conditions_json,explanation,created_at)
                        VALUES(?,?,?,?,?,?,?,?,?)
                        """)) {
                    statement.setString(1, sessionId);
                    statement.setLong(2, sequence);
                    statement.setString(3, claim.certainty().name());
                    statement.setString(4, claim.claim());
                    if (claim.observationCallId().isBlank()) statement.setNull(5, java.sql.Types.VARCHAR);
                    else statement.setString(5, claim.observationCallId());
                    if (claim.taskId().isBlank()) statement.setNull(6, java.sql.Types.VARCHAR);
                    else statement.setString(6, claim.taskId());
                    statement.setString(7, claim.toJson().path("conditions").toString());
                    statement.setString(8, claim.explanation());
                    statement.setLong(9, createdAt);
                    statement.executeUpdate();
                }
                connection.commit();
                return new CompletionClaimSnapshot(sessionId, sequence, claim, Instant.ofEpochMilli(createdAt));
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw persistence(failure); }
    }

    public int toolCount(String sessionId) throws SQLException {
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM brain_tool_call WHERE session_id=?")) {
            statement.setString(1, sessionId); try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
        }
    }

    public JsonNode inspect(String companionId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        var sessions = Json.MAPPER.createArrayNode();
        try (var connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM brain_session WHERE companion_id=? ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, companionId); statement.setInt(2, bounded);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var session = sessions.addObject().put("sessionId", rows.getString("session_id"))
                            .put("controllerId", rows.getString("controller_id")).put("provider", rows.getString("provider"))
                            .put("state", rows.getString("state")).put("lastCode", rows.getString("last_code"))
                            .put("createdAt", java.time.Instant.ofEpochMilli(rows.getLong("created_at")).toString())
                            .put("updatedAt", java.time.Instant.ofEpochMilli(rows.getLong("updated_at")).toString());
                    SemanticStateSnapshot semantic = semanticState(rows.getString("session_id"));
                    if (semantic != null) {
                        session.set("semanticState", semantic.state().toJson());
                        session.put("semanticStateRevision", semantic.revision())
                                .put("semanticStateAuthoredAt", semantic.authoredAt().toString());
                    }
                    var claims = session.putArray("completionClaims");
                    try (PreparedStatement claimRows = connection.prepareStatement("""
                            SELECT claim_sequence,certainty,claim_text,observation_call_id,task_id,
                                   conditions_json,explanation,created_at
                            FROM brain_completion_claim WHERE session_id=? ORDER BY claim_sequence DESC LIMIT 50
                            """)) {
                        claimRows.setString(1, rows.getString("session_id"));
                        try (var claimResult = claimRows.executeQuery()) {
                            while (claimResult.next()) {
                                var claim = claims.addObject()
                                        .put("sequence", claimResult.getLong("claim_sequence"))
                                        .put("certainty", claimResult.getString("certainty"))
                                        .put("claim", claimResult.getString("claim_text"))
                                        .put("observationCallId", nullToEmpty(claimResult.getString("observation_call_id")))
                                        .put("taskId", nullToEmpty(claimResult.getString("task_id")))
                                        .put("explanation", claimResult.getString("explanation"))
                                        .put("createdAt", Instant.ofEpochMilli(
                                                claimResult.getLong("created_at")).toString());
                                claim.set("conditions", Json.parse(
                                        claimResult.getString("conditions_json")));
                            }
                        }
                    }
                    var tools = session.putArray("toolCalls");
                    try (PreparedStatement calls = connection.prepareStatement("""
                            SELECT * FROM brain_tool_call WHERE session_id=? ORDER BY created_at LIMIT 100
                            """)) {
                        calls.setString(1, rows.getString("session_id"));
                        try (var toolRows = calls.executeQuery()) {
                            while (toolRows.next()) tools.addObject().put("callId", toolRows.getString("call_id"))
                                    .put("toolName", toolRows.getString("tool_name"))
                                    .put("state", toolRows.getString("state"))
                                    .put("success", toolRows.getInt("success") != 0)
                                    .put("code", toolRows.getString("result_code"))
                                    .put("terminal", toolRows.getInt("terminal") != 0)
                                    .put("taskId", toolRows.getString("task_id"))
                                    .put("behaviorId", toolRows.getString("behavior_id"))
                                    .put("delivered", toolRows.getObject("delivered_at") != null)
                                    .set("observation", Json.parse(toolRows.getString("observation_json")));
                        }
                    }
                }
            }
        }
        return sessions;
    }

    private static IllegalStateException persistence(SQLException failure) {
        return new IllegalStateException("BRAIN_PERSISTENCE_ERROR", failure);
    }

    private static String toolState(ToolResult result) {
        String explicit = result.observation().path("state").asText("").strip();
        if (!explicit.isBlank()) return switch (explicit) {
            case "CREATED", "ACCEPTED" -> "ACCEPTED";
            case "RUNNING" -> "RUNNING";
            case "COMPLETED", "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            case "WAITING", "PAUSED", "BLOCKED" -> "BLOCKED";
            case "CANCELLED" -> "CANCELLED";
            case "RECONCILIATION_REQUIRED", "INTERRUPTED" -> "INTERRUPTED";
            default -> result.terminal() ? result.success() ? "SUCCEEDED" : "FAILED" : "ACCEPTED";
        };
        if (!result.terminal()) return result.success() ? "ACCEPTED" : "BLOCKED";
        return result.success() ? "SUCCEEDED" : switch (result.code()) {
            case "TOOL_CANCELLED", "SEARCH_CANCELLED" -> "CANCELLED";
            case "TOOL_INTERRUPTED", "TOOL_TIMEOUT" -> "INTERRUPTED";
            default -> "FAILED";
        };
    }

    private static String textOrNull(JsonNode value, String field) {
        String text = value.path(field).asText("").strip();
        return text.isBlank() ? null : text;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static boolean isDurableActive(ToolResult result) {
        return (!result.terminal() && DurableExecutionReceipt.fromAccepted(result).isPresent())
                || DurableExecutionReceipt.isAcceptedReceipt(result);
    }

    private static void clearDurableHandle(java.sql.Connection connection, String sessionId,
                                           DurableExecutionReceipt.Handle handle) throws SQLException {
        if (handle == null) return;
        if ("TASK".equals(handle.kind())) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE brain_tool_call SET durable_active=0,updated_at=?
                    WHERE session_id=? AND durable_active=1 AND task_id=?
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, sessionId); statement.setString(3, handle.id());
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement calls = connection.prepareStatement("""
                SELECT call_id,observation_json FROM brain_tool_call
                WHERE session_id=? AND durable_active=1
                """)) {
            calls.setString(1, sessionId);
            try (var rows = calls.executeQuery();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE brain_tool_call SET durable_active=0,updated_at=? WHERE session_id=? AND call_id=?")) {
                while (rows.next()) {
                    Optional<DurableExecutionReceipt.Handle> candidate =
                            DurableExecutionReceipt.handleFromObservation(Json.parse(rows.getString("observation_json")));
                    if (candidate.isPresent() && candidate.get().kind().equals(handle.kind())
                            && candidate.get().id().equals(handle.id())) {
                        update.setLong(1, System.currentTimeMillis());
                        update.setString(2, sessionId); update.setString(3, rows.getString("call_id"));
                        update.addBatch();
                    }
                }
                update.executeBatch();
            }
        }
    }

    public record AuditedToolCall(ToolCall call, ToolResult result) { }
    public record DurableCall(String sessionId, String callId, ToolCall call,
                              String controllerId, String companionId,
                              DurableExecutionReceipt.Handle handle) { }
    public record SemanticStateSnapshot(String sessionId, String controllerId, String companionId,
                                        BrainSemanticState state, long revision, Instant authoredAt) { }
    public record CompletionClaimSnapshot(String sessionId, long sequence,
                                          BrainCompletionClaim claim, Instant createdAt) { }
}
