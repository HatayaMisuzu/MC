package com.mccompanion.runtime.execution;

public enum PrimitiveState {
    CREATED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED;
    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
