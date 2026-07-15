package com.mccompanion.runtime.execution;

/** Adapter implemented by tested Minecraft-side actions; one call must never block a game tick. */
public interface PrimitiveDriver {
    default void start() { }
    PrimitiveObservation tick();
    default void stop() { }
}
