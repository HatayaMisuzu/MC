package com.mccompanion.minecraft.bridge;

import java.util.Objects;

/** Fences asynchronous callbacks so an obsolete connection cannot mutate current state. */
public final class ConnectionEpochGate<T> {
  private long epoch;
  private T current;

  public synchronized long beginAttempt() {
    current = null;
    return ++epoch;
  }

  public synchronized boolean activate(long attempt, T connection) {
    Objects.requireNonNull(connection, "connection");
    if (attempt != epoch || current != null) return false;
    current = connection;
    return true;
  }

  public synchronized boolean isCurrent(long attempt, T connection) {
    return attempt == epoch && current == connection;
  }

  /** Used by deferred disconnect cleanup: false as soon as any reconnect attempt starts. */
  public synchronized boolean isLatestAttempt(long attempt) {
    return attempt == epoch;
  }

  public synchronized boolean deactivate(long attempt, T connection) {
    if (!isCurrent(attempt, connection)) return false;
    current = null;
    return true;
  }

  public synchronized void invalidate() {
    current = null;
    epoch++;
  }
}
