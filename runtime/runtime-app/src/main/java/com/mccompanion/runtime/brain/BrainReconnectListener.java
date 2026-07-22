package com.mccompanion.runtime.brain;

import java.time.Duration;

/** Lifecycle signal only; it cannot choose a goal or alter the external Brain's strategy. */
public interface BrainReconnectListener {
    BrainReconnectListener NONE = new BrainReconnectListener() { };

    default void safeIdle(BrainSession session, int attempt, Duration delay,
                          LiveBrainFailureCategory category) { }
    default void recovered(BrainSession session, int attempts) { }
}
