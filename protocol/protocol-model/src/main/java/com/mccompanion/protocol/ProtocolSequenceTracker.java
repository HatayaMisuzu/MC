package com.mccompanion.protocol;

import java.util.concurrent.atomic.AtomicLong;

public final class ProtocolSequenceTracker {
    private final AtomicLong lastAccepted;

    public ProtocolSequenceTracker() {
        this(-1);
    }

    public ProtocolSequenceTracker(long lastAccepted) {
        if (lastAccepted < -1) {
            throw new IllegalArgumentException("lastAccepted must be at least -1");
        }
        this.lastAccepted = new AtomicLong(lastAccepted);
    }

    public boolean accept(long sequence) {
        if (sequence < 0) {
            return false;
        }
        while (true) {
            long previous = lastAccepted.get();
            if (sequence <= previous) {
                return false;
            }
            if (lastAccepted.compareAndSet(previous, sequence)) {
                return true;
            }
        }
    }

    public long lastAccepted() {
        return lastAccepted.get();
    }
}
