package com.mccompanion.runtime.agent;

public enum StepState {
    PENDING, READY, RUNNING, PAUSED, BLOCKED, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
