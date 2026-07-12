package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProtocolBehaviorState {
    CREATED,
    STARTING,
    RUNNING,
    WAITING,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED;

    @JsonCreator
    public static ProtocolBehaviorState fromWire(String value) {
        return WireValue.parse(ProtocolBehaviorState.class, value);
    }

    @JsonValue
    public String toWire() {
        return WireValue.of(this);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
