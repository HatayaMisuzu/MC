package com.mccompanion.runtime.lease;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.test.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LeaseServiceTest {
    @TempDir Path temporary;
    private RuntimeDatabase database;
    private MutableClock clock;
    private LeaseService leases;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeDatabase(temporary.resolve("lease.db"));
        database.initialize();
        clock = new MutableClock(Instant.parse("2026-07-12T00:00:00Z"));
        leases = new LeaseService(database, clock, new SecureRandom(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void enforcesBearerEpochExpiryAndMonotonicReacquire() throws Exception {
        ControlLease first = leases.acquire("companion-1", "runtime-main", Duration.ofSeconds(30),
                ControlLease.ControlMode.EXTERNAL_RUNTIME);
        assertEquals(1, first.epoch());
        assertTrue(first.token().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"),
                "generated lease token must be a valid protocol wire identifier");
        assertEquals(first, leases.validate("companion-1", "runtime-main", first.token(), 1));
        assertEquals("STALE_EPOCH", assertThrows(LeaseException.class,
                () -> leases.validate("companion-1", "runtime-main", first.token(), 2)).code());
        assertEquals("LEASE_DENIED", assertThrows(LeaseException.class,
                () -> leases.validate("companion-1", "runtime-main", "x".repeat(43), 1)).code());
        clock.advance(Duration.ofSeconds(31));
        assertEquals("LEASE_EXPIRED", assertThrows(LeaseException.class,
                () -> leases.validate("companion-1", "runtime-main", first.token(), 1)).code());
        ControlLease second = leases.acquire("companion-1", "runtime-main", Duration.ofSeconds(30),
                ControlLease.ControlMode.EXTERNAL_RUNTIME);
        assertEquals(2, second.epoch());
        assertNotEquals(first.token(), second.token());
    }

    @Test
    void renewalNeverShortensExistingLease() throws Exception {
        ControlLease lease = leases.acquire("companion-2", "runtime-main", Duration.ofMinutes(2),
                ControlLease.ControlMode.EXTERNAL_RUNTIME);
        clock.advance(Duration.ofSeconds(5));
        ControlLease renewed = leases.renew(lease, Duration.ofSeconds(10));
        assertEquals(lease.expiresAt(), renewed.expiresAt());
    }

    @Test
    void recoveredBearerlessLeasesAreInvalidatedButEpochPersists() throws Exception {
        leases.acquire("companion-3", "runtime-main", Duration.ofMinutes(1),
                ControlLease.ControlMode.EXTERNAL_RUNTIME);
        LeaseService restarted = new LeaseService(database, clock, new SecureRandom());
        assertEquals(1, restarted.invalidateRecoveredLeases());
        ControlLease next = restarted.acquire("companion-3", "runtime-main", Duration.ofMinutes(1),
                ControlLease.ControlMode.EXTERNAL_RUNTIME);
        assertEquals(2, next.epoch());
    }

    @Test
    void expiryAtomicallyReturnsRemovedLeasesAndClearsProcessBearer() throws Exception {
        ControlLease expired = leases.acquire("companion-expired", "runtime-main", Duration.ofSeconds(1),
                ControlLease.ControlMode.EXTERNAL_RUNTIME);
        leases.acquire("companion-live", "runtime-main", Duration.ofMinutes(1),
                ControlLease.ControlMode.EXTERNAL_RUNTIME);
        clock.advance(Duration.ofSeconds(2));

        assertEquals(java.util.List.of(new LeaseService.ExpiredLease(expired.companionId(),
                expired.controllerId(), expired.epoch(), expired.mode())), leases.expireDue());
        assertTrue(leases.processLease("companion-expired").isEmpty());
        assertTrue(leases.processLease("companion-live").isPresent());
        assertTrue(leases.expireDue().isEmpty(), "an expired lease must be returned only once");
    }

    @Test
    void acquisitionWaitsForConcurrentWriterBeforeReadingEpoch() throws Exception {
        try (Connection writer = database.open()) {
            writer.setAutoCommit(false);
            try (PreparedStatement statement = writer.prepareStatement(
                    "INSERT INTO control_epoch(companion_id,last_epoch) VALUES (?,?)")) {
                statement.setString(1, "companion-contended");
                statement.setLong(2, 4);
                statement.executeUpdate();
            }

            CompletableFuture<ControlLease> acquisition = CompletableFuture.supplyAsync(() -> {
                try {
                    return leases.acquire("companion-contended", "runtime-main", Duration.ofMinutes(1),
                            ControlLease.ControlMode.EXTERNAL_RUNTIME);
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            Thread.sleep(100);
            writer.commit();
            writer.setAutoCommit(true);

            assertEquals(5, acquisition.get(5, TimeUnit.SECONDS).epoch());
        }
    }
}
