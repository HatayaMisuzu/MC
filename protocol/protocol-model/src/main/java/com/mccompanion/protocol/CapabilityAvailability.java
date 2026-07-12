package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CapabilityAvailability {
    AVAILABLE,
    UNAVAILABLE,
    DEGRADED;

    @JsonCreator
    public static CapabilityAvailability fromWire(String value) {
        return WireValue.parse(CapabilityAvailability.class, value);
    }

    @JsonValue
    public String toWire() {
        return WireValue.of(this);
    }
}
