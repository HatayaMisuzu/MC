package com.mccompanion.core.failure;

public enum FailureCode {
    OK(false),
    INVALID_REQUEST(false),
    UNAUTHORIZED(false),
    LEASE_REQUIRED(true),
    LEASE_EXPIRED(true),
    STALE_EPOCH(true),
    COMPANION_NOT_FOUND(false),
    OWNER_OFFLINE(true),
    WORLD_CHANGED(true),
    PATH_NOT_FOUND(false),
    PATH_BLOCKED(true),
    STUCK(true),
    TARGET_UNLOADED(true),
    DIMENSION_UNSUPPORTED(false),
    UNSUPPORTED_PLATFORM(false),
    RUNTIME_OFFLINE(true),
    PROVIDER_ERROR(true),
    BEHAVIOR_CANCELLED(false),
    BEHAVIOR_TIMEOUT(true),
    FORBIDDEN_WRITE_DETECTED(false),
    INTERNAL_ERROR(true);

    private final boolean recoverable;

    FailureCode(boolean recoverable) {
        this.recoverable = recoverable;
    }

    public boolean success() {
        return this == OK;
    }

    public boolean failure() {
        return this != OK;
    }

    public boolean recoverable() {
        return recoverable;
    }
}
