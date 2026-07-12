package com.mccompanion.runtime.task;

public enum TaskState {
    CREATED,
    ACCEPTED,
    RUNNING,
    WAITING,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECONCILIATION_REQUIRED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
