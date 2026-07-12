package com.mccompanion.core.lease;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.LeaseId;
import com.mccompanion.core.id.SessionId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ControlLeaseManager {
    public static final Duration DEFAULT_MAX_TTL = Duration.ofHours(24);

    private final Clock clock;
    private final Duration maxTtl;
    private final Map<CompanionId, Slot> slots = new HashMap<>();

    public ControlLeaseManager(Clock clock) {
        this(clock, DEFAULT_MAX_TTL);
    }

    public ControlLeaseManager(Clock clock, Duration maxTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxTtl = positiveDuration(maxTtl, "maxTtl");
    }

    public synchronized LeaseOperationResult acquire(
            CompanionId companionId,
            SessionId controllerId,
            Duration ttl) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(controllerId, "controllerId");
        requireTtl(ttl);
        Instant now = clock.instant();
        Slot slot = slots.computeIfAbsent(companionId, ignored -> new Slot(LeaseEpoch.NONE, null));
        ControlLease current = slot.current;
        if (current != null && current.unexpiredAt(now)) {
            if (!current.controllerId().equals(controllerId)) {
                return LeaseOperationResult.rejected(FailureCode.UNAUTHORIZED,
                        "Companion is controlled by another active session");
            }
            ControlLease renewed = extend(current, now, ttl);
            slot.current = renewed;
            return LeaseOperationResult.accepted(renewed, "Existing control lease renewed idempotently");
        }

        LeaseEpoch nextEpoch = slot.epoch.next();
        ControlLease acquired = new ControlLease(LeaseId.random(), companionId, controllerId, nextEpoch,
                now, now.plus(ttl));
        slot.epoch = nextEpoch;
        slot.current = acquired;
        return LeaseOperationResult.accepted(acquired, "Control lease acquired");
    }

    public synchronized LeaseOperationResult renew(
            CompanionId companionId,
            SessionId controllerId,
            LeaseId leaseId,
            LeaseEpoch epoch,
            Duration ttl) {
        requireTtl(ttl);
        Instant now = clock.instant();
        LeaseOperationResult validation = validateAt(companionId, controllerId, leaseId, epoch, now);
        if (!validation.accepted()) {
            return validation;
        }
        ControlLease renewed = extend(validation.lease(), now, ttl);
        slots.get(companionId).current = renewed;
        return LeaseOperationResult.accepted(renewed, "Control lease renewed");
    }

    public synchronized LeaseOperationResult release(
            CompanionId companionId,
            SessionId controllerId,
            LeaseId leaseId,
            LeaseEpoch epoch) {
        LeaseOperationResult validation = validate(companionId, controllerId, leaseId, epoch);
        if (!validation.accepted()) {
            return validation;
        }
        slots.get(companionId).current = null;
        return LeaseOperationResult.accepted(validation.lease(), "Control lease released");
    }

    public synchronized LeaseOperationResult validate(
            CompanionId companionId,
            SessionId controllerId,
            LeaseId leaseId,
            LeaseEpoch epoch) {
        return validateAt(companionId, controllerId, leaseId, epoch, clock.instant());
    }

    private LeaseOperationResult validateAt(
            CompanionId companionId,
            SessionId controllerId,
            LeaseId leaseId,
            LeaseEpoch epoch,
            Instant now) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(controllerId, "controllerId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(now, "now");
        Slot slot = slots.get(companionId);
        if (slot == null) {
            return LeaseOperationResult.rejected(FailureCode.LEASE_REQUIRED, "A control lease is required");
        }
        if (!slot.epoch.equals(epoch)) {
            return LeaseOperationResult.rejected(FailureCode.STALE_EPOCH,
                    "Command epoch does not match the current control epoch");
        }
        ControlLease current = slot.current;
        if (current == null) {
            return LeaseOperationResult.rejected(FailureCode.LEASE_REQUIRED, "No active control lease exists");
        }
        if (!current.leaseId().equals(leaseId) || !current.controllerId().equals(controllerId)) {
            return LeaseOperationResult.rejected(FailureCode.UNAUTHORIZED,
                    "Lease does not belong to this control session");
        }
        if (!current.unexpiredAt(now)) {
            slot.current = null;
            return LeaseOperationResult.rejected(FailureCode.LEASE_EXPIRED, "Control lease has expired");
        }
        return LeaseOperationResult.accepted(current, "Control lease is valid");
    }

    public synchronized boolean restore(ControlLease lease) {
        Objects.requireNonNull(lease, "lease");
        Slot current = slots.get(lease.companionId());
        if (current != null && current.epoch.compareTo(lease.epoch()) >= 0) {
            return false;
        }
        ControlLease active = lease.unexpiredAt(clock.instant()) ? lease : null;
        slots.put(lease.companionId(), new Slot(lease.epoch(), active));
        return true;
    }

    public synchronized int revokeController(SessionId controllerId) {
        Objects.requireNonNull(controllerId, "controllerId");
        int revoked = 0;
        for (Slot slot : slots.values()) {
            if (slot.current != null && slot.current.controllerId().equals(controllerId)) {
                slot.current = null;
                revoked++;
            }
        }
        return revoked;
    }

    public synchronized Optional<ControlLease> current(CompanionId companionId) {
        Objects.requireNonNull(companionId, "companionId");
        Slot slot = slots.get(companionId);
        if (slot == null || slot.current == null) {
            return Optional.empty();
        }
        if (!slot.current.unexpiredAt(clock.instant())) {
            slot.current = null;
            return Optional.empty();
        }
        return Optional.of(slot.current);
    }

    public synchronized List<ControlLease> activeLeases() {
        List<ControlLease> active = new ArrayList<>();
        for (CompanionId companionId : List.copyOf(slots.keySet())) {
            current(companionId).ifPresent(active::add);
        }
        return List.copyOf(active);
    }

    public synchronized LeaseEpoch currentEpoch(CompanionId companionId) {
        Slot slot = slots.get(Objects.requireNonNull(companionId, "companionId"));
        return slot == null ? LeaseEpoch.NONE : slot.epoch;
    }

    private void requireTtl(Duration ttl) {
        positiveDuration(ttl, "ttl");
        if (ttl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("ttl exceeds maximum of " + maxTtl);
        }
    }

    private static ControlLease extend(ControlLease lease, Instant now, Duration ttl) {
        Instant candidate = now.plus(ttl);
        Instant expiry = candidate.isAfter(lease.expiresAt()) ? candidate : lease.expiresAt();
        return lease.renewedUntil(expiry);
    }

    private static Duration positiveDuration(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }

    private static final class Slot {
        private LeaseEpoch epoch;
        private ControlLease current;

        private Slot(LeaseEpoch epoch, ControlLease current) {
            this.epoch = epoch;
            this.current = current;
        }
    }
}
