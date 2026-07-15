package com.mccompanion.runtime.capability;

/** Runtime truth, ordered from roadmap declaration to executable-now. */
public enum CapabilityLifecycleState {
    DECLARED,
    IMPLEMENTED,
    CONNECTED,
    AVAILABLE_NOW,
    TEMPORARILY_BLOCKED,
    UNSUPPORTED
}
