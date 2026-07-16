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
