package com.mccompanion.runtime.taskgraph;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskGraphExecutionControl {
    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final Set<String> activeCallIds = ConcurrentHashMap.newKeySet();

    public void requestPause() { pauseRequested.set(true); }
    public void requestCancel() { cancelRequested.set(true); }
    public boolean pauseRequested() { return pauseRequested.get(); }
    public boolean cancelRequested() { return cancelRequested.get(); }
    public Set<String> activeCallIds() { return Set.copyOf(activeCallIds); }
    void callStarted(String callId) { activeCallIds.add(callId); }
    void callFinished(String callId) { activeCallIds.remove(callId); }
}
