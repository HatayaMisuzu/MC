package com.mccompanion.core.lease;

import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.LeaseId;
import com.mccompanion.core.id.SessionId;

import java.time.Instant;
import java.util.Objects;

public record ControlLease(
        LeaseId leaseId,
        CompanionId companionId,
        SessionId controllerId,
        LeaseEpoch epoch,
        Instant acquiredAt,
        Instant expiresAt) {

    public ControlLease {
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(controllerId, "controllerId");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (epoch.equals(LeaseEpoch.NONE)) {
            throw new IllegalArgumentException("an active lease must have a positive epoch");
        }
        if (!expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("expiresAt must be after acquiredAt");
        }
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(acquiredAt) && instant.isBefore(expiresAt);
    }

    /**
     * Returns whether the lease has not reached its expiry. Unlike {@link #activeAt(Instant)}, this
     * deliberately treats a clock rollback before {@code acquiredAt} as unexpired so control fails
     * closed instead of being handed to another controller.
     */
    public boolean unexpiredAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return instant.isBefore(expiresAt);
    }

    public ControlLease renewedUntil(Instant newExpiry) {
        Objects.requireNonNull(newExpiry, "newExpiry");
        if (newExpiry.isBefore(expiresAt)) {
            throw new IllegalArgumentException("renewal cannot shorten a control lease");
        }
        return new ControlLease(leaseId, companionId, controllerId, epoch, acquiredAt, newExpiry);
    }
}
