package com.mccompanion.core.lease;

import com.mccompanion.core.failure.FailureCode;

import java.util.Objects;
import java.util.Optional;

public record LeaseOperationResult(
        boolean accepted,
        ControlLease lease,
        FailureCode failureCode,
        String message) {

    public LeaseOperationResult {
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (accepted != failureCode.success()) {
            throw new IllegalArgumentException("accepted must agree with failureCode");
        }
        if (accepted && lease == null) {
            throw new IllegalArgumentException("accepted lease operation requires a lease");
        }
        if (!accepted && lease != null) {
            throw new IllegalArgumentException("rejected lease operation cannot contain a lease");
        }
    }

    public static LeaseOperationResult accepted(ControlLease lease, String message) {
        return new LeaseOperationResult(true, Objects.requireNonNull(lease, "lease"), FailureCode.OK, message);
    }

    public static LeaseOperationResult rejected(FailureCode code, String message) {
        if (code == FailureCode.OK) {
            throw new IllegalArgumentException("rejection requires a failure code");
        }
        return new LeaseOperationResult(false, null, code, message);
    }

    public Optional<ControlLease> acceptedLease() {
        return Optional.ofNullable(lease);
    }
}
