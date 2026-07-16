package com.mccompanion.runtime.taskgraph;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TaskGraphExecutionControl {
    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicReference<String> activeCallId = new AtomicReference<>();

    public void requestPause() { pauseRequested.set(true); }
    public void requestCancel() { cancelRequested.set(true); }
    public boolean pauseRequested() { return pauseRequested.get(); }
    public boolean cancelRequested() { return cancelRequested.get(); }
    public String activeCallId() { return activeCallId.get(); }
    void activeCallId(String value) { activeCallId.set(value); }
}
