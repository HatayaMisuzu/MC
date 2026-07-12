package com.mccompanion.protocol;

import java.time.Instant;
import java.util.Objects;

public record CommandAccepted(
        String commandId,
        boolean duplicate,
        String behaviorId,
        long behaviorRevision,
        Instant acceptedAt) {

    public CommandAccepted {
        commandId = ProtocolFields.identifier(commandId, "commandId");
        if (behaviorId != null) {
            behaviorId = ProtocolFields.identifier(behaviorId, "behaviorId");
        }
        if (behaviorRevision < 0) {
            throw new IllegalArgumentException("behaviorRevision must be non-negative");
        }
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
