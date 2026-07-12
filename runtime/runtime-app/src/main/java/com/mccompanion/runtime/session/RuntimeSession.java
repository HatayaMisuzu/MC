package com.mccompanion.runtime.session;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeSession {
    private final String sessionId;
    private final SessionPeer peer;
    private final Handshake handshake;
    private final Instant connectedAt;
    private final AtomicLong outgoingSequence = new AtomicLong(-1);
    private final AtomicLong incomingSequence = new AtomicLong(-1);
    private final Set<String> companionIds = ConcurrentHashMap.newKeySet();
    private volatile Instant lastSeen;

    RuntimeSession(String sessionId, SessionPeer peer, Handshake handshake, Instant connectedAt) {
        this.sessionId = sessionId;
        this.peer = peer;
        this.handshake = handshake;
        this.connectedAt = connectedAt;
        this.lastSeen = connectedAt;
    }

    public String sessionId() { return sessionId; }
    public SessionPeer peer() { return peer; }
    public Handshake handshake() { return handshake; }
    public Instant connectedAt() { return connectedAt; }
    public Instant lastSeen() { return lastSeen; }
    public long nextSequence() { return outgoingSequence.incrementAndGet(); }
    public boolean acceptIncomingSequence(long sequence) {
        if (sequence < 0) return false;
        while (true) {
            long previous = incomingSequence.get();
            if (sequence <= previous) return false;
            if (incomingSequence.compareAndSet(previous, sequence)) return true;
        }
    }
    public Set<String> companionIds() { return Set.copyOf(companionIds); }
    public void touch(Instant time) { lastSeen = time; }
    void addCompanion(String id) { companionIds.add(id); }
}
