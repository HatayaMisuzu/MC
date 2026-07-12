package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CommandType {
    START_BEHAVIOR,
    PAUSE_BEHAVIOR,
    RESUME_BEHAVIOR,
    CANCEL_BEHAVIOR,
    QUERY_STATUS,
    ACQUIRE_LEASE,
    RENEW_LEASE,
    RELEASE_LEASE;

    @JsonCreator
    public static CommandType fromWire(String value) {
        return WireValue.parse(CommandType.class, value);
    }

    @JsonValue
    public String toWire() {
        return WireValue.of(this);
    }
}
