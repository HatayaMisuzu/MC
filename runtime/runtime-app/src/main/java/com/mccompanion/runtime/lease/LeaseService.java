package com.mccompanion.runtime.lease;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.security.Digests;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LeaseService {
    private final RuntimeDatabase database;
    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, ControlLease> processLeases = new ConcurrentHashMap<>();

    public LeaseService(RuntimeDatabase database) {
        this(database, Clock.systemUTC(), new SecureRandom());
    }

    public LeaseService(RuntimeDatabase database, Clock clock, SecureRandom random) {
        this.database = database;
        this.clock = clock;
        this.random = random;
    }

    public synchronized ControlLease acquire(
            String companionId, String controllerId, Duration duration, ControlLease.ControlMode mode) throws SQLException {
        validateIdentifier(companionId, "companionId");
        validateIdentifier(controllerId, "controllerId");
        java.util.Objects.requireNonNull(mode, "mode");
        validateDuration(duration);
        Instant now = clock.instant();
        ControlLease local = processLeases.get(companionId);
        if (local != null && local.controllerId().equals(controllerId) && local.expiresAt().isAfter(now)) {
            return renew(local, duration);
        }
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                LeaseRow existing = selectLease(connection, companionId).orElse(null);
                if (existing != null && existing.expiresAt > now.toEpochMilli()) {
                    throw new LeaseException("LEASE_HELD", "Companion is controlled by another active lease");
                }
                long epoch = nextEpoch(connection, companionId);
                String token = newToken();
                Instant expiresAt = now.plus(duration);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO control_lease(companion_id, controller_id, epoch, lease_token_hash, mode, expires_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(companion_id) DO UPDATE SET
                          controller_id=excluded.controller_id, epoch=excluded.epoch,
                          lease_token_hash=excluded.lease_token_hash, mode=excluded.mode,
                          expires_at=excluded.expires_at, updated_at=excluded.updated_at
                        """)) {
                    statement.setString(1, companionId);
                    statement.setString(2, controllerId);
                    statement.setLong(3, epoch);
                    statement.setString(4, Digests.sha256(token));
                    statement.setString(5, mode.name());
                    statement.setLong(6, expiresAt.toEpochMilli());
                    statement.setLong(7, now.toEpochMilli());
                    statement.executeUpdate();
                }
                connection.commit();
                ControlLease lease = new ControlLease(companionId, controllerId, epoch, expiresAt, mode, token);
                processLeases.put(companionId, lease);
                return lease;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public synchronized ControlLease renew(ControlLease lease, Duration duration) throws SQLException {
        validateDuration(duration);
        validate(lease.companionId(), lease.controllerId(), lease.token(), lease.epoch());
        Instant requestedExpiry = clock.instant().plus(duration);
        Instant expiresAt = requestedExpiry.isAfter(lease.expiresAt()) ? requestedExpiry : lease.expiresAt();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE control_lease SET expires_at=?, updated_at=?
                WHERE companion_id=? AND controller_id=? AND epoch=? AND lease_token_hash=?
                """)) {
            statement.setLong(1, expiresAt.toEpochMilli());
            statement.setLong(2, clock.millis());
            statement.setString(3, lease.companionId());
            statement.setString(4, lease.controllerId());
            statement.setLong(5, lease.epoch());
            statement.setString(6, Digests.sha256(lease.token()));
            if (statement.executeUpdate() != 1) {
                throw new LeaseException("STALE_EPOCH", "Lease changed while it was being renewed");
            }
        }
        ControlLease renewed = new ControlLease(lease.companionId(), lease.controllerId(), lease.epoch(),
                expiresAt, lease.mode(), lease.token());
        processLeases.put(lease.companionId(), renewed);
        return renewed;
    }

    public ControlLease validate(String companionId, String controllerId, String token, long epoch) throws SQLException {
        if (token == null || token.isBlank()) {
            throw new LeaseException("LEASE_DENIED", "Control lease credentials do not match");
        }
        LeaseRow row;
        try (Connection connection = database.open()) {
            row = selectLease(connection, companionId)
                    .orElseThrow(() -> new LeaseException("LEASE_EXPIRED", "No active control lease"));
        }
        if (row.expiresAt <= clock.millis()) {
            throw new LeaseException("LEASE_EXPIRED", "Control lease has expired");
        }
        if (row.epoch != epoch) {
            throw new LeaseException("STALE_EPOCH", "Control epoch does not match the active lease");
        }
        if (!row.controllerId.equals(controllerId)
                || !Digests.constantTimeHexEquals(row.tokenHash, Digests.sha256(token))) {
            throw new LeaseException("LEASE_DENIED", "Control lease credentials do not match");
        }
        ControlLease.ControlMode mode = ControlLease.ControlMode.valueOf(row.mode);
        return new ControlLease(companionId, controllerId, epoch, Instant.ofEpochMilli(row.expiresAt), mode, token);
    }

    public synchronized void release(ControlLease lease) throws SQLException {
        validate(lease.companionId(), lease.controllerId(), lease.token(), lease.epoch());
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM control_lease WHERE companion_id=? AND epoch=? AND lease_token_hash=?")) {
            statement.setString(1, lease.companionId());
            statement.setLong(2, lease.epoch());
            statement.setString(3, Digests.sha256(lease.token()));
            statement.executeUpdate();
        }
        processLeases.remove(lease.companionId(), lease);
    }

    /** Invalidates leases whose unpersisted bearer token was lost in a previous process. Epochs remain monotonic. */
    public synchronized int invalidateRecoveredLeases() throws SQLException {
        processLeases.clear();
        try (Connection connection = database.open(); StatementCounter counter = new StatementCounter(connection,
                "DELETE FROM control_lease")) {
            return counter.execute();
        }
    }

    public synchronized List<ExpiredLease> expireDue() throws SQLException {
        long now = clock.millis();
        List<ExpiredLease> expired = new ArrayList<>();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT companion_id, controller_id, epoch, mode FROM control_lease WHERE expires_at<=?")) {
                select.setLong(1, now);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        expired.add(new ExpiredLease(result.getString(1), result.getString(2), result.getLong(3),
                                ControlLease.ControlMode.valueOf(result.getString(4))));
                    }
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM control_lease WHERE expires_at<=?")) {
                delete.setLong(1, now);
                delete.executeUpdate();
            }
            connection.commit();
        }
        expired.forEach(lease -> processLeases.remove(lease.companionId()));
        return expired;
    }

    public Optional<ControlLease> processLease(String companionId) {
        ControlLease lease = processLeases.get(companionId);
        return lease == null || !lease.expiresAt().isAfter(clock.instant()) ? Optional.empty() : Optional.of(lease);
    }

    public List<ControlLease> processLeases() {
        Instant now = clock.instant();
        return processLeases.values().stream().filter(lease -> lease.expiresAt().isAfter(now)).toList();
    }

    /** Revokes the local runtime's lease without requiring a bearer token from a disconnected peer. */
    public synchronized void revoke(String companionId) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM control_lease WHERE companion_id=?")) {
            statement.setString(1, companionId);
            statement.executeUpdate();
        }
        processLeases.remove(companionId);
    }

    private Optional<LeaseRow> selectLease(Connection connection, String companionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT controller_id, epoch, lease_token_hash, mode, expires_at
                FROM control_lease WHERE companion_id=?
                """)) {
            statement.setString(1, companionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LeaseRow(result.getString(1), result.getLong(2), result.getString(3),
                        result.getString(4), result.getLong(5)));
            }
        }
    }

    private long nextEpoch(Connection connection, String companionId) throws SQLException {
        long current = 0;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT last_epoch FROM control_epoch WHERE companion_id=?")) {
            select.setString(1, companionId);
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    current = result.getLong(1);
                }
            }
        }
        long next = Math.addExact(current, 1);
        try (PreparedStatement update = connection.prepareStatement("""
                INSERT INTO control_epoch(companion_id, last_epoch) VALUES (?, ?)
                ON CONFLICT(companion_id) DO UPDATE SET last_epoch=excluded.last_epoch
                """)) {
            update.setString(1, companionId);
            update.setLong(2, next);
            update.executeUpdate();
        }
        return next;
    }

    private String newToken() {
        byte[] data = new byte[32];
        random.nextBytes(data);
        // A lease bearer is also carried as a protocol wire identifier. Base64URL
        // can begin with '-' or '_', while protocol identifiers must begin with an
        // ASCII letter or digit. Keep the full 256 bits of entropy and add a stable
        // alphabetic prefix so every generated token is protocol-valid.
        return "lease-" + Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static void validateDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Lease duration must be between 1 ms and 10 minutes");
        }
    }

    private static void validateIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(field + " is missing or invalid");
        }
    }

    private record LeaseRow(String controllerId, long epoch, String tokenHash, String mode, long expiresAt) {
    }

    public record ExpiredLease(String companionId, String controllerId, long epoch, ControlLease.ControlMode mode) {
    }

    private static final class StatementCounter implements AutoCloseable {
        private final PreparedStatement statement;

        private StatementCounter(Connection connection, String sql) throws SQLException {
            this.statement = connection.prepareStatement(sql);
        }

        private int execute() throws SQLException {
            return statement.executeUpdate();
        }

        @Override
        public void close() throws SQLException {
            statement.close();
        }
    }
}
