package com.mccompanion.core.lease;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.SessionId;
import com.mccompanion.core.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlLeaseManagerTest {
    private static final Instant START = Instant.parse("2026-07-12T00:00:00Z");

    @Test
    void acquiresRenewsWithoutShorteningAndRejectsCompetingController() {
        MutableClock clock = new MutableClock(START);
        ControlLeaseManager manager = new ControlLeaseManager(clock);
        CompanionId companion = CompanionId.random();
        SessionId first = SessionId.random();
        SessionId second = SessionId.random();

        ControlLease lease = manager.acquire(companion, first, Duration.ofSeconds(30)).lease();
        clock.advance(Duration.ofSeconds(1));
        ControlLease idempotent = manager.acquire(companion, first, Duration.ofSeconds(5)).lease();

        assertEquals(lease.leaseId(), idempotent.leaseId());
        assertEquals(lease.epoch(), idempotent.epoch());
        assertEquals(lease.expiresAt(), idempotent.expiresAt(), "idempotent acquire must not shorten TTL");
        assertEquals(FailureCode.UNAUTHORIZED,
                manager.acquire(companion, second, Duration.ofSeconds(30)).failureCode());

        ControlLease extended = manager.renew(companion, first, lease.leaseId(), lease.epoch(),
                Duration.ofSeconds(60)).lease();
        assertEquals(START.plusSeconds(61), extended.expiresAt());
    }

    @Test
    void expirationAdvancesEpochAndRejectsStaleCommands() {
        MutableClock clock = new MutableClock(START);
        ControlLeaseManager manager = new ControlLeaseManager(clock);
        CompanionId companion = CompanionId.random();
        SessionId controller = SessionId.random();
        ControlLease first = manager.acquire(companion, controller, Duration.ofSeconds(10)).lease();

        clock.advance(Duration.ofSeconds(10));
        assertEquals(FailureCode.LEASE_EXPIRED,
                manager.validate(companion, controller, first.leaseId(), first.epoch()).failureCode());

        ControlLease second = manager.acquire(companion, controller, Duration.ofSeconds(10)).lease();
        assertEquals(first.epoch().next(), second.epoch());
        assertEquals(FailureCode.STALE_EPOCH,
                manager.validate(companion, controller, first.leaseId(), first.epoch()).failureCode());
    }

    @Test
    void releaseAndControllerRevocationEnterSafeNoLeaseState() {
        MutableClock clock = new MutableClock(START);
        ControlLeaseManager manager = new ControlLeaseManager(clock);
        SessionId controller = SessionId.random();
        CompanionId one = CompanionId.random();
        CompanionId two = CompanionId.random();
        ControlLease first = manager.acquire(one, controller, Duration.ofMinutes(1)).lease();
        manager.acquire(two, controller, Duration.ofMinutes(1));

        assertTrue(manager.release(one, controller, first.leaseId(), first.epoch()).accepted());
        assertTrue(manager.current(one).isEmpty());
        assertEquals(1, manager.revokeController(controller));
        assertTrue(manager.activeLeases().isEmpty());
        assertEquals(first.epoch(), manager.currentEpoch(one));
    }

    @Test
    void clockRollbackFailsClosedInsteadOfGrantingControlToAnotherSession() {
        MutableClock clock = new MutableClock(START);
        ControlLeaseManager manager = new ControlLeaseManager(clock);
        CompanionId companion = CompanionId.random();
        SessionId controller = SessionId.random();
        ControlLease lease = manager.acquire(companion, controller, Duration.ofMinutes(1)).lease();

        clock.set(START.minusSeconds(30));

        assertEquals(FailureCode.UNAUTHORIZED,
                manager.acquire(companion, SessionId.random(), Duration.ofSeconds(10)).failureCode());
        assertTrue(manager.validate(companion, controller, lease.leaseId(), lease.epoch()).accepted());
        assertEquals(lease.expiresAt(),
                manager.renew(companion, controller, lease.leaseId(), lease.epoch(),
                        Duration.ofSeconds(5)).lease().expiresAt());
    }

    @Test
    void restorePreservesHighestEpochEvenWhenLeaseAlreadyExpired() {
        MutableClock clock = new MutableClock(START.plusSeconds(20));
        ControlLeaseManager manager = new ControlLeaseManager(clock);
        CompanionId companion = CompanionId.random();
        ControlLease expired = new ControlLease(com.mccompanion.core.id.LeaseId.random(), companion,
                SessionId.random(), new LeaseEpoch(7), START, START.plusSeconds(10));

        assertTrue(manager.restore(expired));
        assertEquals(new LeaseEpoch(7), manager.currentEpoch(companion));
        assertTrue(manager.current(companion).isEmpty());
        assertFalse(manager.restore(expired));

        ControlLease acquired = manager.acquire(companion, SessionId.random(), Duration.ofSeconds(10)).lease();
        assertEquals(new LeaseEpoch(8), acquired.epoch());
    }

    @Test
    void validatesTtlAndLeaseResultInvariants() {
        MutableClock clock = new MutableClock(START);
        ControlLeaseManager manager = new ControlLeaseManager(clock, Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class,
                () -> manager.acquire(CompanionId.random(), SessionId.random(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> manager.acquire(CompanionId.random(), SessionId.random(), Duration.ofSeconds(31)));

        ControlLease lease = manager.acquire(CompanionId.random(), SessionId.random(),
                Duration.ofSeconds(10)).lease();
        assertThrows(IllegalArgumentException.class,
                () -> lease.renewedUntil(lease.expiresAt().minusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaseOperationResult(false, lease, FailureCode.LEASE_EXPIRED, "expired"));
    }
}
