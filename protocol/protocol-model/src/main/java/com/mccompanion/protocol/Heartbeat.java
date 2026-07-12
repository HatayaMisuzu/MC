package com.mccompanion.protocol;

import java.time.Instant;
import java.util.Objects;

public record Heartbeat(String sessionId, long sequence, Instant sentAt) {
    public Heartbeat {
        sessionId = ProtocolFields.identifier(sessionId, "sessionId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Objects.requireNonNull(sentAt, "sentAt");
    }
}
