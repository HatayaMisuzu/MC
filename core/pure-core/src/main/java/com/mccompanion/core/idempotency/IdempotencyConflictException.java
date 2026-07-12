package com.mccompanion.core.idempotency;

import com.mccompanion.core.id.CommandId;

public final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(CommandId commandId) {
        super("Command id " + commandId + " was reused with different content");
    }
}
