package com.mccompanion.runtime.lease;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.test.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

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
}

