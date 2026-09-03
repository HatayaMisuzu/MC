package com.mccompanion.runtime.event;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable bounded admission, coalescing and delivery state for meaningful Runtime events. */
public final class RuntimeEventRepository {
    public static final int DEFAULT_PENDING_CAPACITY_PER_COMPANION = 256;
    private static final Duration RETENTION = Duration.ofDays(30);
    private final RuntimeDatabase database;
    private final Clock clock;
    private final int pendingCapacity;

    public RuntimeEventRepository(RuntimeDatabase database) {
        this(database, Clock.systemUTC(), DEFAULT_PENDING_CAPACITY_PER_COMPANION);
    }

    RuntimeEventRepository(RuntimeDatabase database, Clock clock, int pendingCapacity) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pendingCapacity < 1 || pendingCapacity > 1_024) {
            throw new IllegalArgumentException("pendingCapacity must be 1..1024");
        }
        this.pendingCapacity = pendingCapacity;
    }

    public Admission admit(RuntimeEvent event, RuntimeEvent.AdmissionPolicy policy) throws SQLException {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(policy, "policy");
        long now = clock.millis();
        if (event.expiresAt().toEpochMilli() <= now) {
            return new Admission(false, "EVENT_STALE", event.eventId(), false, 0);
        }
        return admitTransaction(event, policy, now, false);
    }

    /** Atomically invalidates the old lifecycle and persists its death edge. */
    public Admission admitDeath(RuntimeEvent event, RuntimeEvent.AdmissionPolicy policy) throws SQLException {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(policy, "policy");
        if (event.category() != RuntimeEvent.Category.SURVIVAL || !event.eventType().equals("DEATH")) {
            throw new IllegalArgumentException("death admission requires a SURVIVAL/DEATH event");
        }
        long now = clock.millis();
        if (event.expiresAt().toEpochMilli() <= now) {
            return new Admission(false, "EVENT_STALE", event.eventId(), false, 0);
        }
        return admitTransaction(event, policy, now, true);
    }

    private Admission admitTransaction(RuntimeEvent event, RuntimeEvent.AdmissionPolicy policy,
                                        long now, boolean invalidateBeforeDeath) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                if (invalidateBeforeDeath) {
                    staleBeforeDeath(connection, event.companionId(), event.occurredAt(), now);
                }
                Admission admission = admit(connection, event, policy, now);
                connection.commit();
                return admission;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private Admission admit(Connection connection, RuntimeEvent event,
                            RuntimeEvent.AdmissionPolicy policy, long now) throws SQLException {
        prune(connection, now, event.companionId());
        Admission duplicate = duplicate(connection, event);
        if (duplicate != null) return duplicate;
        Admission coalesced = coalesce(connection, event, policy, now);
        if (coalesced != null) return coalesced;
        if (coolingDown(connection, event, policy, now)) {
            return new Admission(false, "EVENT_COOLDOWN", event.eventId(), false, 0);
        }
        int pending = pendingCount(connection, event.companionId());
        if (pending >= pendingCapacity && !evictLowerPriority(connection, event)) {
            return new Admission(false, "EVENT_QUEUE_FULL", event.eventId(), false, pending);
        }
        long availableAt = Math.addExact(now, policy.debounce().toMillis());
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO runtime_event(
                  event_id,category,event_type,priority,source,companion_id,task_id,
                  task_graph_execution_id,target_json,dedup_key,coalesce_key,cooldown_key,
                  payload_json,occurrence_count,occurred_at,observed_at,available_at,expires_at,
                  state,attempt_count,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,?)
                """)) {
            bindEvent(insert, event, availableAt, now);
            insert.executeUpdate();
        }
        rememberDedup(connection, event.companionId(), event.dedupKey(), event.eventId(), now);
        return new Admission(true, "EVENT_ADMITTED", event.eventId(), false,
                pendingCount(connection, event.companionId()));
    }

    public Optional<RuntimeEvent> claimReady() throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                markExpired(connection, now);
                RuntimeEvent event = null;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT * FROM runtime_event
                        WHERE state='PENDING' AND available_at<=? AND expires_at>?
                        ORDER BY priority DESC, occurred_at ASC, created_at ASC LIMIT 1
                        """)) {
                    select.setLong(1, now);
                    select.setLong(2, now);
                    try (ResultSet row = select.executeQuery()) {
                        if (row.next()) event = read(row);
                    }
                }
                if (event != null) {
                    try (PreparedStatement claim = connection.prepareStatement("""
                            UPDATE runtime_event SET state='DISPATCHING',attempt_count=attempt_count+1,updated_at=?
                            WHERE event_id=? AND state='PENDING'
                            """)) {
                        claim.setLong(1, now);
                        claim.setString(2, event.eventId());
                        if (claim.executeUpdate() != 1) event = null;
                    }
                }
                connection.commit();
                return Optional.ofNullable(event);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void delivered(String eventId) throws SQLException {
        terminal(eventId, "DELIVERED", clock.millis());
    }

    public void suppressed(String eventId) throws SQLException {
        terminal(eventId, "SUPPRESSED", clock.millis());
    }

    /** Prevents any pre-death event from waking or retrying after lifecycle invalidation. */
    private static int staleBeforeDeath(Connection connection, String companionId,
                                        Instant deathOccurredAt, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE runtime_event SET state='STALE',updated_at=?
                WHERE companion_id=? AND state IN ('PENDING','DISPATCHING')
                  AND event_type NOT IN ('DEATH','RESPAWN') AND occurred_at<=?
                """)) {
            update.setLong(1, now);
            update.setString(2, companionId);
            update.setLong(3, deathOccurredAt.toEpochMilli());
            return update.executeUpdate();
        }
    }

    public void defer(String eventId, Duration delay) throws SQLException {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative() || delay.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("delay must be 0..5 minutes");
        }
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement update = connection.prepareStatement("""
                UPDATE runtime_event SET state='PENDING',available_at=?,updated_at=?
                WHERE event_id=? AND state='DISPATCHING' AND expires_at>?
                """)) {
            update.setLong(1, Math.addExact(now, delay.toMillis()));
            update.setLong(2, now);
            update.setString(3, eventId);
            update.setLong(4, now);
            if (update.executeUpdate() != 1 && !hasState(connection, eventId, "STALE")) {
                throw new IllegalStateException("EVENT_DEFER_CONFLICT");
            }
        }
    }

    /** Requeue only an already durable graph request; the observed timestamp and replan budget stay intact. */
    public void recoverReplan(RuntimeEvent event) throws SQLException {
        long now = clock.millis();
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                try (var death = connection.prepareStatement("""
                        SELECT 1 FROM runtime_event WHERE companion_id=? AND event_type='DEATH' AND occurred_at>? LIMIT 1
                        """)) {
                    death.setString(1, event.companionId()); death.setLong(2, event.occurredAt().toEpochMilli());
                    try (var row = death.executeQuery()) { if (row.next()) { connection.rollback(); return; } }
                }
                try (var insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO runtime_event(
                          event_id,category,event_type,priority,source,companion_id,task_id,
                          task_graph_execution_id,target_json,dedup_key,coalesce_key,cooldown_key,
                          payload_json,occurrence_count,occurred_at,observed_at,available_at,expires_at,
                          state,attempt_count,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,?)
                        """)) {
                    bindEvent(insert, event, now, now); insert.executeUpdate();
                }
                try (var update = connection.prepareStatement("""
                        UPDATE runtime_event SET state='PENDING',available_at=?,expires_at=?,updated_at=?
                        WHERE event_id=? AND companion_id=?
                        """)) {
                    update.setLong(1, now); update.setLong(2, now + Duration.ofMinutes(10).toMillis());
                    update.setLong(3, now); update.setString(4, event.eventId());
                    update.setString(5, event.companionId()); update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    public int recoverInterrupted() throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                markExpired(connection, now);
                int recovered;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE runtime_event SET state='PENDING',available_at=?,updated_at=?
                        WHERE state='DISPATCHING' AND expires_at>?
                        """)) {
                    update.setLong(1, now);
                    update.setLong(2, now);
                    update.setLong(3, now);
                    recovered = update.executeUpdate();
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

    public int pendingCount(String companionId) throws SQLException {
        try (Connection connection = database.open()) {
            return pendingCount(connection, companionId);
        }
    }

    public boolean hasReady() throws SQLException {
        long now = clock.millis();
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM runtime_event
                WHERE state='PENDING' AND available_at<=? AND expires_at>? LIMIT 1
                """)) {
            query.setLong(1, now);
            query.setLong(2, now);
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private void terminal(String eventId, String state, long now) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement update = connection.prepareStatement("""
                UPDATE runtime_event SET state=?,delivered_at=?,updated_at=?
                WHERE event_id=? AND state='DISPATCHING'
                """)) {
            update.setString(1, state);
            update.setLong(2, now);
            update.setLong(3, now);
            update.setString(4, eventId);
            if (update.executeUpdate() != 1 && !hasState(connection, eventId, "STALE")) {
                throw new IllegalStateException("EVENT_DELIVERY_CONFLICT");
            }
        }
    }

    private static boolean hasState(Connection connection, String eventId, String state) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM runtime_event WHERE event_id=? AND state=? LIMIT 1")) {
            query.setString(1, eventId);
            query.setString(2, state);
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private Admission duplicate(Connection connection, RuntimeEvent event) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT d.event_id,e.occurrence_count FROM runtime_event_dedup d
                JOIN runtime_event e ON e.event_id=d.event_id
                WHERE d.companion_id=? AND d.dedup_key=? LIMIT 1
                """)) {
            query.setString(1, event.companionId());
            query.setString(2, event.dedupKey());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new Admission(false, "EVENT_DUPLICATE", row.getString(1),
                        false, row.getInt(2)) : null;
            }
        }
    }

    private Admission coalesce(Connection connection, RuntimeEvent event,
                               RuntimeEvent.AdmissionPolicy policy, long now) throws SQLException {
        if (event.coalesceKey() == null) return null;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT event_id,occurrence_count FROM runtime_event
                WHERE companion_id=? AND coalesce_key=? AND state='PENDING' LIMIT 1
                """)) {
            query.setString(1, event.companionId());
            query.setString(2, event.coalesceKey());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                String existingId = row.getString(1);
                int occurrences = row.getInt(2) + event.occurrenceCount();
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE runtime_event SET event_type=?,priority=MAX(priority,?),source=?,task_id=?,
                          task_graph_execution_id=?,target_json=?,cooldown_key=?,payload_json=?,
                          occurrence_count=?,occurred_at=?,observed_at=?,available_at=?,expires_at=?,updated_at=?
                        WHERE event_id=? AND state='PENDING'
                        """)) {
                    int index = 1;
                    update.setString(index++, event.eventType());
                    update.setInt(index++, event.priority().ordinal());
                    update.setString(index++, event.source());
                    nullable(update, index++, event.taskId());
                    nullable(update, index++, event.taskGraphExecutionId());
                    update.setString(index++, Json.write(event.target()));
                    nullable(update, index++, event.cooldownKey());
                    update.setString(index++, Json.write(event.payload()));
                    update.setInt(index++, occurrences);
                    update.setLong(index++, event.occurredAt().toEpochMilli());
                    update.setLong(index++, event.observedAt().toEpochMilli());
                    update.setLong(index++, Math.addExact(now, policy.debounce().toMillis()));
                    update.setLong(index++, event.expiresAt().toEpochMilli());
                    update.setLong(index++, now);
                    update.setString(index, existingId);
                    update.executeUpdate();
                }
                rememberDedup(connection, event.companionId(), event.dedupKey(), existingId, now);
                return new Admission(true, "EVENT_COALESCED", existingId, true, occurrences);
            }
        }
    }

    private boolean coolingDown(Connection connection, RuntimeEvent event,
                                RuntimeEvent.AdmissionPolicy policy, long now) throws SQLException {
        if (event.cooldownKey() == null || policy.cooldown().isZero()) return false;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT delivered_at FROM runtime_event
                WHERE companion_id=? AND cooldown_key=? AND state='DELIVERED' AND delivered_at IS NOT NULL
                ORDER BY delivered_at DESC LIMIT 1
                """)) {
            query.setString(1, event.companionId());
            query.setString(2, event.cooldownKey());
            try (ResultSet row = query.executeQuery()) {
                return row.next() && now - row.getLong(1) < policy.cooldown().toMillis();
            }
        }
    }

    private boolean evictLowerPriority(Connection connection, RuntimeEvent event) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT event_id FROM runtime_event
                WHERE companion_id=? AND state='PENDING' AND priority<?
                ORDER BY priority ASC,created_at ASC LIMIT 1
                """)) {
            query.setString(1, event.companionId());
            query.setInt(2, event.priority().ordinal());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return false;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE runtime_event SET state='DROPPED',updated_at=?
                        WHERE event_id=? AND state='PENDING'
                        """)) {
                    update.setLong(1, clock.millis());
                    update.setString(2, row.getString(1));
                    return update.executeUpdate() == 1;
                }
            }
        }
    }

    private int pendingCount(Connection connection, String companionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM runtime_event
                WHERE companion_id=? AND state IN ('PENDING','DISPATCHING')
                """)) {
            query.setString(1, companionId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    private void prune(Connection connection, long now, String companionId) throws SQLException {
        markExpired(connection, now);
        try (PreparedStatement deleteDedup = connection.prepareStatement(
                "DELETE FROM runtime_event_dedup WHERE created_at<?")) {
            deleteDedup.setLong(1, now - RETENTION.toMillis());
            deleteDedup.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM runtime_event
                WHERE (updated_at<? AND state NOT IN ('PENDING','DISPATCHING'))
                   OR event_id IN (
                     SELECT event_id FROM runtime_event WHERE companion_id=?
                     AND state NOT IN ('PENDING','DISPATCHING')
                     ORDER BY updated_at DESC LIMIT -1 OFFSET 1024
                   )
                """)) {
            delete.setLong(1, now - RETENTION.toMillis());
            delete.setString(2, companionId);
            delete.executeUpdate();
        }
    }

    private void markExpired(Connection connection, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE runtime_event SET state='STALE',updated_at=?
                WHERE state IN ('PENDING','DISPATCHING') AND expires_at<=?
                """)) {
            update.setLong(1, now);
            update.setLong(2, now);
            update.executeUpdate();
        }
    }

    private static void bindEvent(PreparedStatement insert, RuntimeEvent event,
                                  long availableAt, long now) throws SQLException {
        int index = 1;
        insert.setString(index++, event.eventId());
        insert.setString(index++, event.category().name());
        insert.setString(index++, event.eventType());
        insert.setInt(index++, event.priority().ordinal());
        insert.setString(index++, event.source());
        insert.setString(index++, event.companionId());
        nullable(insert, index++, event.taskId());
        nullable(insert, index++, event.taskGraphExecutionId());
        insert.setString(index++, Json.write(event.target()));
        insert.setString(index++, event.dedupKey());
        nullable(insert, index++, event.coalesceKey());
        nullable(insert, index++, event.cooldownKey());
        insert.setString(index++, Json.write(event.payload()));
        insert.setInt(index++, event.occurrenceCount());
        insert.setLong(index++, event.occurredAt().toEpochMilli());
        insert.setLong(index++, event.observedAt().toEpochMilli());
        insert.setLong(index++, availableAt);
        insert.setLong(index++, event.expiresAt().toEpochMilli());
        insert.setLong(index++, now);
        insert.setLong(index, now);
    }

    private static RuntimeEvent read(ResultSet row) throws SQLException {
        return new RuntimeEvent(row.getString("event_id"),
                RuntimeEvent.Category.valueOf(row.getString("category")),
                row.getString("event_type"),
                RuntimeEvent.Priority.values()[row.getInt("priority")],
                row.getString("source"), row.getString("companion_id"),
                row.getString("task_id"), row.getString("task_graph_execution_id"),
                Json.parse(row.getString("target_json")), row.getString("dedup_key"),
                row.getString("coalesce_key"), row.getString("cooldown_key"),
                Instant.ofEpochMilli(row.getLong("occurred_at")),
                Instant.ofEpochMilli(row.getLong("observed_at")),
                Instant.ofEpochMilli(row.getLong("expires_at")),
                Json.parse(row.getString("payload_json")), row.getInt("occurrence_count"));
    }

    private static void nullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void rememberDedup(Connection connection, String companionId, String dedupKey,
                                      String eventId, long now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO runtime_event_dedup(companion_id,dedup_key,event_id,created_at)
                VALUES(?,?,?,?)
                """)) {
            insert.setString(1, companionId);
            insert.setString(2, dedupKey);
            insert.setString(3, eventId);
            insert.setLong(4, now);
            insert.executeUpdate();
        }
    }

    public record Admission(boolean admitted, String code, String eventId,
                            boolean coalesced, int occurrenceCount) { }
}
