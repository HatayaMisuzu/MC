package com.mccompanion.runtime.workspace;

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

/** Durable generated Skill review, approval, disable, and rollback state. */
public final class SkillRepository {
    private final RuntimeDatabase database;
    private final Clock clock;

    public SkillRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC());
    }

    SkillRepository(RuntimeDatabase database, Clock clock) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public SkillVersion requestPromotion(String profileId, String companionId, String skillId,
                                         String format, String document, String sha256,
                                         JsonNode permissions, JsonNode provenance, JsonNode validation,
                                         String controllerId, String brainSessionId) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                Optional<SkillVersion> duplicate = findByHash(connection, profileId, companionId, skillId, sha256);
                if (duplicate.isPresent() && SetStatus.REUSABLE.contains(duplicate.get().status())) {
                    connection.rollback();
                    return duplicate.get();
                }
                long version = nextVersion(connection, profileId, companionId, skillId);
                long now = clock.millis();
                String requestId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO skill_version(request_id,profile_id,companion_id,skill_id,version,format,
                        document,sha256,permissions_json,provenance_json,validation_json,status,status_reason,
                        controller_id,brain_session_id,approved_by,approved_at,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING_REVIEW',NULL,?,?,NULL,NULL,?,?)
                        """)) {
                    statement.setString(1, requestId);
                    statement.setString(2, required(profileId));
                    statement.setString(3, required(companionId));
                    statement.setString(4, required(skillId));
                    statement.setLong(5, version);
                    statement.setString(6, required(format));
                    statement.setString(7, requiredDocument(document));
                    statement.setString(8, requiredSha(sha256));
                    statement.setString(9, Json.write(requiredNode(permissions)));
                    statement.setString(10, Json.write(requiredNode(provenance)));
                    statement.setString(11, Json.write(requiredNode(validation)));
                    statement.setString(12, required(controllerId));
                    statement.setString(13, required(brainSessionId));
                    statement.setLong(14, now);
                    statement.setLong(15, now);
                    statement.executeUpdate();
                }
                connection.commit();
                return get(requestId).orElseThrow();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** User/UI or explicit policy boundary; never exposed as an external Brain Tool. */
    public SkillVersion approve(String requestId, String approvedBy) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                SkillVersion requested = get(connection, requestId).orElseThrow(
                        () -> new IllegalArgumentException("skill promotion request does not exist"));
                if (requested.status().equals("ACTIVE")) {
                    connection.rollback();
                    return requested;
                }
                if (!requested.status().equals("PENDING_REVIEW")) {
                    throw new IllegalArgumentException("skill promotion request is not pending review");
                }
                long now = clock.millis();
                supersedeActive(connection, requested.profileId(), requested.companionId(), requested.skillId(),
                        "NEW_VERSION_APPROVED", now);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE skill_version SET status='ACTIVE',status_reason='USER_APPROVED',
                        approved_by=?,approved_at=?,updated_at=? WHERE request_id=? AND status='PENDING_REVIEW'
                        """)) {
                    statement.setString(1, required(approvedBy));
                    statement.setLong(2, now);
                    statement.setLong(3, now);
                    statement.setString(4, requestId);
                    if (statement.executeUpdate() != 1) throw new SQLException("skill approval lost its revision");
                }
                connection.commit();
                return get(requestId).orElseThrow();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public SkillVersion disable(String profileId, String companionId, String skillId,
                                String actor, String reason) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                Optional<SkillVersion> active = active(connection, profileId, companionId, skillId);
                if (active.isEmpty()) {
                    SkillVersion latest = latest(connection, profileId, companionId, skillId).orElseThrow(
                            () -> new IllegalArgumentException("skill has no version"));
                    if (latest.status().equals("DISABLED")) {
                        connection.rollback();
                        return latest;
                    }
                    throw new IllegalArgumentException("skill has no active approved version");
                }
                long now = clock.millis();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE skill_version SET status='DISABLED',status_reason=?,updated_at=?
                        WHERE request_id=? AND status='ACTIVE'
                        """)) {
                    statement.setString(1, boundedReason(actor, reason));
                    statement.setLong(2, now);
                    statement.setString(3, active.get().requestId());
                    statement.executeUpdate();
                }
                connection.commit();
                return get(active.get().requestId()).orElseThrow();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public SkillVersion reject(String requestId, String rejectedBy, String reason) throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE skill_version SET status='REJECTED',status_reason=?,updated_at=?
                WHERE request_id=? AND status='PENDING_REVIEW'
                """)) {
            statement.setString(1, boundedReason(rejectedBy, reason));
            statement.setLong(2, now);
            statement.setString(3, required(requestId));
            if (statement.executeUpdate() != 1) {
                SkillVersion current = get(requestId).orElseThrow(
                        () -> new IllegalArgumentException("skill promotion request does not exist"));
                if (current.status().equals("REJECTED")) return current;
                throw new IllegalArgumentException("skill promotion request is not pending review");
            }
        }
        return get(requestId).orElseThrow();
    }

    public SkillVersion rollback(String profileId, String companionId, String skillId, long version,
                                 String actor, String reason) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                SkillVersion target = byVersion(connection, profileId, companionId, skillId, version).orElseThrow(
                        () -> new IllegalArgumentException("skill rollback version does not exist"));
                if (target.status().equals("ACTIVE")) {
                    connection.rollback();
                    return target;
                }
                if (target.approvedAt() == null || !java.util.Set.of("SUPERSEDED", "DISABLED").contains(target.status())) {
                    throw new IllegalArgumentException("skill rollback target was never approved");
                }
                long now = clock.millis();
                supersedeActive(connection, profileId, companionId, skillId,
                        boundedReason(actor, "ROLLBACK: " + reason), now);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE skill_version SET status='ACTIVE',status_reason=?,updated_at=?
                        WHERE request_id=? AND status IN ('SUPERSEDED','DISABLED')
                        """)) {
                    statement.setString(1, boundedReason(actor, "ROLLBACK_TARGET: " + reason));
                    statement.setLong(2, now);
                    statement.setString(3, target.requestId());
                    if (statement.executeUpdate() != 1) throw new SQLException("skill rollback lost its revision");
                }
                connection.commit();
                return get(target.requestId()).orElseThrow();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<SkillVersion> list(String profileId, String companionId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM skill_version WHERE profile_id=? AND companion_id=?
                ORDER BY skill_id,version DESC LIMIT 256
                """)) {
            statement.setString(1, required(profileId));
            statement.setString(2, required(companionId));
            List<SkillVersion> values = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(read(result));
            }
            return List.copyOf(values);
        }
    }

    public Optional<SkillVersion> get(String requestId) throws SQLException {
        try (Connection connection = database.open()) {
            return get(connection, requestId);
        }
    }

    public Optional<SkillVersion> version(String profileId, String companionId, String skillId,
                                          long version) throws SQLException {
        try (Connection connection = database.open()) {
            return byVersion(connection, profileId, companionId, skillId, version);
        }
    }

    public Optional<SkillVersion> active(String profileId, String companionId, String skillId)
            throws SQLException {
        try (Connection connection = database.open()) {
            return active(connection, profileId, companionId, skillId);
        }
    }

    public SkillTrialLease requestTrial(String profileId, String companionId, String controllerId,
                                        String brainSessionId, String skillId, String format,
                                        String document, String sha256, JsonNode tools,
                                        JsonNode permissions, JsonNode limits,
                                        java.time.Duration duration) throws SQLException {
        if (duration == null || duration.compareTo(java.time.Duration.ofSeconds(60)) < 0
                || duration.compareTo(java.time.Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("trial duration must be 60..900 seconds");
        }
        long now = clock.millis();
        long expiresAt = Math.addExact(now, duration.toMillis());
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                expireTrials(connection, now);
                try (PreparedStatement live = connection.prepareStatement("""
                        SELECT COUNT(*) FROM skill_trial_lease
                        WHERE profile_id=? AND companion_id=? AND brain_session_id=?
                          AND status IN ('AVAILABLE','RUNNING')
                        """)) {
                    live.setString(1, required(profileId));
                    live.setString(2, required(companionId));
                    live.setString(3, required(brainSessionId));
                    try (ResultSet result = live.executeQuery()) {
                        if (result.next() && result.getInt(1) > 0) {
                            throw new IllegalStateException("SKILL_TRIAL_ALREADY_ACTIVE");
                        }
                    }
                }
                String leaseId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO skill_trial_lease(lease_id,profile_id,companion_id,controller_id,
                        brain_session_id,skill_id,format,document,sha256,tools_json,permissions_json,
                        limits_json,status,remaining_uses,expires_at,execution_id,evidence_json,
                        revoked_by,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'AVAILABLE',1,?,NULL,'{}',NULL,?,?)
                        """)) {
                    statement.setString(1, leaseId);
                    statement.setString(2, required(profileId));
                    statement.setString(3, required(companionId));
                    statement.setString(4, required(controllerId));
                    statement.setString(5, required(brainSessionId));
                    statement.setString(6, required(skillId));
                    statement.setString(7, required(format));
                    statement.setString(8, requiredDocument(document));
                    statement.setString(9, requiredSha(sha256));
                    statement.setString(10, Json.write(requiredNode(tools)));
                    statement.setString(11, Json.write(requiredNode(permissions)));
                    statement.setString(12, Json.write(requiredNode(limits)));
                    statement.setLong(13, expiresAt);
                    statement.setLong(14, now);
                    statement.setLong(15, now);
                    statement.executeUpdate();
                }
                SkillTrialLease created = trial(connection, leaseId).orElseThrow();
                connection.commit();
                return created;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public SkillTrialLease claimTrial(String leaseId, String profileId, String companionId,
                                      String controllerId, String brainSessionId,
                                      String executionId) throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                expireTrials(connection, now);
                SkillTrialLease lease = trial(connection, leaseId).orElseThrow(
                        () -> new IllegalArgumentException("Skill trial lease does not exist"));
                requireTrialScope(lease, profileId, companionId, controllerId, brainSessionId);
                if (!lease.status().equals("AVAILABLE") || lease.remainingUses() != 1) {
                    throw new IllegalStateException("SKILL_TRIAL_NOT_AVAILABLE");
                }
                long requiredMillis = Math.multiplyExact(
                        lease.limits().path("maxWallTimeSeconds").asLong(), 1_000L);
                if (lease.expiresAt().toEpochMilli() - now < requiredMillis) {
                    throw new IllegalStateException("SKILL_TRIAL_INSUFFICIENT_TIME");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE skill_trial_lease SET status='RUNNING',remaining_uses=0,
                        execution_id=?,updated_at=?
                        WHERE lease_id=? AND status='AVAILABLE' AND remaining_uses=1
                        """)) {
                    statement.setString(1, required(executionId));
                    statement.setLong(2, now);
                    statement.setString(3, lease.leaseId());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("SKILL_TRIAL_NOT_AVAILABLE");
                    }
                }
                SkillTrialLease claimed = trial(connection, leaseId).orElseThrow();
                connection.commit();
                return claimed;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public SkillTrialLease finishTrial(String leaseId, String executionId, JsonNode evidence)
            throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE skill_trial_lease SET status='CONSUMED',evidence_json=?,updated_at=?
                WHERE lease_id=? AND execution_id=? AND status='RUNNING'
                """)) {
            statement.setString(1, Json.write(requiredNode(evidence)));
            statement.setLong(2, now);
            statement.setString(3, required(leaseId));
            statement.setString(4, required(executionId));
            if (statement.executeUpdate() != 1) {
                SkillTrialLease current = trial(leaseId).orElseThrow(
                        () -> new IllegalArgumentException("Skill trial lease does not exist"));
                if (!java.util.Set.of("REVOKED", "EXPIRED", "CONSUMED").contains(current.status())) {
                    throw new IllegalStateException("SKILL_TRIAL_EVIDENCE_CONFLICT");
                }
                return current;
            }
        }
        return trial(leaseId).orElseThrow();
    }

    public SkillTrialLease revokeTrial(String profileId, String companionId, String leaseId,
                                       String actor) throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                expireTrials(connection, now);
                SkillTrialLease current = trial(connection, leaseId).orElseThrow(
                        () -> new IllegalArgumentException("Skill trial lease does not exist"));
                if (!current.profileId().equals(required(profileId))
                        || !current.companionId().equals(required(companionId))) {
                    throw new IllegalArgumentException("Skill trial lease is outside management scope");
                }
                if (java.util.Set.of("REVOKED", "EXPIRED", "CONSUMED").contains(current.status())) {
                    connection.rollback();
                    return current;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE skill_trial_lease SET status='REVOKED',revoked_by=?,updated_at=?
                        WHERE lease_id=? AND status IN ('AVAILABLE','RUNNING')
                        """)) {
                    statement.setString(1, required(actor));
                    statement.setLong(2, now);
                    statement.setString(3, current.leaseId());
                    statement.executeUpdate();
                }
                SkillTrialLease revoked = trial(connection, leaseId).orElseThrow();
                connection.commit();
                return revoked;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<SkillTrialLease> trial(String leaseId) throws SQLException {
        try (Connection connection = database.open()) {
            expireTrials(connection, clock.millis());
            return trial(connection, leaseId);
        }
    }

    public List<SkillTrialLease> trials(String profileId, String companionId) throws SQLException {
        try (Connection connection = database.open()) {
            expireTrials(connection, clock.millis());
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM skill_trial_lease WHERE profile_id=? AND companion_id=?
                    ORDER BY updated_at DESC LIMIT 100
                    """)) {
                statement.setString(1, required(profileId));
                statement.setString(2, required(companionId));
                List<SkillTrialLease> values = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(readTrial(result));
                }
                return List.copyOf(values);
            }
        }
    }

    /**
     * A trial is deliberately not resumable after a runtime restart. Its matching Task Graph
     * execution is reconciled separately, while the capability lease remains consumed and
     * visibly records why it cannot be reused.
     */
    public int recoverInterruptedTrials() throws SQLException {
        long now = clock.millis();
        JsonNode evidence = Json.object()
                .put("success", false)
                .put("code", "SKILL_TRIAL_INTERRUPTED")
                .put("state", "REVOKED");
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                expireTrials(connection, now);
                int recovered;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE skill_trial_lease
                        SET status='REVOKED',evidence_json=?,revoked_by='RUNTIME_RESTART',updated_at=?
                        WHERE status='RUNNING'
                        """)) {
                    statement.setString(1, Json.write(evidence));
                    statement.setLong(2, now);
                    recovered = statement.executeUpdate();
                }
                connection.commit();
                return recovered;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void requireTrialScope(SkillTrialLease lease, String profileId, String companionId,
                                          String controllerId, String brainSessionId) {
        if (!lease.profileId().equals(required(profileId))
                || !lease.companionId().equals(required(companionId))
                || !lease.controllerId().equals(required(controllerId))
                || !lease.brainSessionId().equals(required(brainSessionId))) {
            throw new IllegalArgumentException("Skill trial lease is outside exact execution scope");
        }
    }

    private static void expireTrials(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE skill_trial_lease SET status='EXPIRED',updated_at=?
                WHERE status='AVAILABLE' AND expires_at<=?
                """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.executeUpdate();
        }
    }

    private static Optional<SkillTrialLease> trial(Connection connection, String leaseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM skill_trial_lease WHERE lease_id=?")) {
            statement.setString(1, required(leaseId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTrial(result)) : Optional.empty();
            }
        }
    }

    private static SkillTrialLease readTrial(ResultSet result) throws SQLException {
        return new SkillTrialLease(result.getString("lease_id"), result.getString("profile_id"),
                result.getString("companion_id"), result.getString("controller_id"),
                result.getString("brain_session_id"), result.getString("skill_id"),
                result.getString("format"), result.getString("document"), result.getString("sha256"),
                Json.parse(result.getString("tools_json")),
                Json.parse(result.getString("permissions_json")),
                Json.parse(result.getString("limits_json")), result.getString("status"),
                result.getInt("remaining_uses"), Instant.ofEpochMilli(result.getLong("expires_at")),
                result.getString("execution_id"), Json.parse(result.getString("evidence_json")),
                result.getString("revoked_by"), Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")));
    }

    private static Optional<SkillVersion> get(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM skill_version WHERE request_id=?")) {
            statement.setString(1, required(requestId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<SkillVersion> findByHash(Connection connection, String profileId, String companionId,
                                                     String skillId, String sha256) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM skill_version WHERE profile_id=? AND companion_id=? AND skill_id=? AND sha256=?
                ORDER BY version DESC LIMIT 1
                """)) {
            statement.setString(1, required(profileId));
            statement.setString(2, required(companionId));
            statement.setString(3, required(skillId));
            statement.setString(4, requiredSha(sha256));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<SkillVersion> active(Connection connection, String profileId, String companionId,
                                                 String skillId) throws SQLException {
        return byStatus(connection, profileId, companionId, skillId, "ACTIVE");
    }

    private static Optional<SkillVersion> latest(Connection connection, String profileId, String companionId,
                                                 String skillId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM skill_version WHERE profile_id=? AND companion_id=? AND skill_id=?
                ORDER BY version DESC LIMIT 1
                """)) {
            statement.setString(1, required(profileId));
            statement.setString(2, required(companionId));
            statement.setString(3, required(skillId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<SkillVersion> byStatus(Connection connection, String profileId, String companionId,
                                                   String skillId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM skill_version WHERE profile_id=? AND companion_id=? AND skill_id=? AND status=?
                ORDER BY version DESC LIMIT 1
                """)) {
            statement.setString(1, required(profileId));
            statement.setString(2, required(companionId));
            statement.setString(3, required(skillId));
            statement.setString(4, status);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<SkillVersion> byVersion(Connection connection, String profileId, String companionId,
                                                    String skillId, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM skill_version WHERE profile_id=? AND companion_id=? AND skill_id=? AND version=?
                """)) {
            statement.setString(1, required(profileId));
            statement.setString(2, required(companionId));
            statement.setString(3, required(skillId));
            statement.setLong(4, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static long nextVersion(Connection connection, String profileId, String companionId,
                                    String skillId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(version),0)+1 FROM skill_version
                WHERE profile_id=? AND companion_id=? AND skill_id=?
                """)) {
            statement.setString(1, required(profileId));
            statement.setString(2, required(companionId));
            statement.setString(3, required(skillId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 1;
            }
        }
    }

    private static void supersedeActive(Connection connection, String profileId, String companionId,
                                        String skillId, String reason, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE skill_version SET status='SUPERSEDED',status_reason=?,updated_at=?
                WHERE profile_id=? AND companion_id=? AND skill_id=? AND status='ACTIVE'
                """)) {
            statement.setString(1, reason);
            statement.setLong(2, now);
            statement.setString(3, required(profileId));
            statement.setString(4, required(companionId));
            statement.setString(5, required(skillId));
            statement.executeUpdate();
        }
    }

    private static SkillVersion read(ResultSet result) throws SQLException {
        long approvedValue = result.getLong("approved_at");
        Long approved = result.wasNull() ? null : approvedValue;
        return new SkillVersion(result.getString("request_id"), result.getString("profile_id"),
                result.getString("companion_id"), result.getString("skill_id"), result.getLong("version"),
                result.getString("format"), result.getString("document"), result.getString("sha256"),
                Json.parse(result.getString("permissions_json")), Json.parse(result.getString("provenance_json")),
                Json.parse(result.getString("validation_json")), result.getString("status"),
                result.getString("status_reason"), result.getString("controller_id"),
                result.getString("brain_session_id"), result.getString("approved_by"),
                approved == null ? null : Instant.ofEpochMilli(approved),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")));
    }

    private static JsonNode requiredNode(JsonNode value) {
        if (value == null) throw new IllegalArgumentException("skill metadata is required");
        return value;
    }

    private static String requiredDocument(String value) {
        if (value == null || value.isBlank() || value.length() > 65_536) {
            throw new IllegalArgumentException("skill document is invalid");
        }
        return value;
    }

    private static String requiredSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("skill sha256 is invalid");
        }
        return value;
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("skill identifier is invalid");
        }
        return value.strip();
    }

    private static String boundedReason(String actor, String reason) {
        String value = required(actor) + ": " + required(reason);
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static final class SetStatus {
        private static final java.util.Set<String> REUSABLE =
                java.util.Set.of("PENDING_REVIEW", "ACTIVE");
    }
}
