package com.mccompanion.core.behavior;

public enum BehaviorState {
    CREATED,
    STARTING,
    RUNNING,
    WAITING,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean active() {
        return this == STARTING || this == RUNNING || this == WAITING || this == PAUSED || this == BLOCKED;
    }
}
