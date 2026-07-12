package com.mccompanion.runtime.lease;

import java.time.Instant;
import java.util.Objects;

public record ControlLease(
        String companionId,
        String controllerId,
        long epoch,
        Instant expiresAt,
        ControlMode mode,
        String token
) {
    public ControlLease {
        if (companionId == null || companionId.isBlank() || controllerId == null || controllerId.isBlank()) {
            throw new IllegalArgumentException("Lease companionId and controllerId must not be blank");
        }
        if (epoch <= 0) {
            throw new IllegalArgumentException("Lease epoch must be positive");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(mode, "mode");
        if (token == null || token.length() < 32 || token.isBlank()) {
            throw new IllegalArgumentException("Lease token is invalid");
        }
    }

    @Override
    public String toString() {
        return "ControlLease[companionId=" + companionId + ", controllerId=" + controllerId
                + ", epoch=" + epoch + ", expiresAt=" + expiresAt + ", mode=" + mode + ", token=<redacted>]";
    }

    public enum ControlMode {
        EXTERNAL_RUNTIME,
        LOCAL_RULES
    }
}
