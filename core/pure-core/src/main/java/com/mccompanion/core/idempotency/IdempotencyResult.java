package com.mccompanion.core.idempotency;

import java.util.Objects;

public record IdempotencyResult<R>(R value, boolean duplicate) {
    public IdempotencyResult {
        Objects.requireNonNull(value, "value");
    }
}
