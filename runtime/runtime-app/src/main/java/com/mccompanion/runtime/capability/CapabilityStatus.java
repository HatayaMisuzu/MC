package com.mccompanion.runtime.capability;

public record CapabilityStatus(String name, CapabilityLifecycleState state, String reason) {
    public CapabilityStatus {
        reason = reason == null ? "" : reason;
    }

    public boolean availableNow() { return state == CapabilityLifecycleState.AVAILABLE_NOW; }
}
