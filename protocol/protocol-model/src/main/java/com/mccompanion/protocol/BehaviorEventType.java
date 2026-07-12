package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BehaviorEventType {
    STARTED,
    PROGRESS,
    WAITING,
    PAUSED,
    RESUMED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED;

    @JsonCreator
    public static BehaviorEventType fromWire(String value) {
        return WireValue.parse(BehaviorEventType.class, value);
    }

    @JsonValue
    public String toWire() {
        return WireValue.of(this);
    }
}
