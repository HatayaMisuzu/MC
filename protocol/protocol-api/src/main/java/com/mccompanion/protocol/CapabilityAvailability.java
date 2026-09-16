package com.mccompanion.protocol;


public enum CapabilityAvailability {
    AVAILABLE,
    UNAVAILABLE,
    DEGRADED;

    public static CapabilityAvailability fromWire(String value) {
        return WireValue.parse(CapabilityAvailability.class, value);
    }

    public String toWire() {
        return WireValue.of(this);
    }
}
