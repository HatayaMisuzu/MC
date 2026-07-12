package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PlatformLoader {
    FABRIC,
    NEOFORGE,
    FORGE;

    @JsonCreator
    public static PlatformLoader fromWire(String value) {
        return WireValue.parse(PlatformLoader.class, value);
    }

    @JsonValue
    public String toWire() {
        return WireValue.of(this);
    }
}
