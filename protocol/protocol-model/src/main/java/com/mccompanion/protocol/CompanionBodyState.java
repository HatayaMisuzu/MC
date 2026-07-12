package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CompanionBodyState {
    SPAWNED,
    SLEEPING,
    DESPAWNED,
    DEAD,
    RECOVERING;

    @JsonCreator
    public static CompanionBodyState fromWire(String value) {
        return WireValue.parse(CompanionBodyState.class, value);
    }

    @JsonValue
    public String toWire() {
        return WireValue.of(this);
    }
}
