package com.mccompanion.runtime.session;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeSession {
    private final String sessionId;
    private final SessionPeer peer;
    private final Handshake handshake;
    private volatile com.mccompanion.protocol.SessionCapabilitySnapshot capabilitySnapshot;
    private final Instant connectedAt;
    private final long authorityOrder;
    private final AtomicLong outgoingSequence = new AtomicLong(-1);
    private final AtomicLong incomingSequence = new AtomicLong(-1);
    private final Set<String> companionIds = ConcurrentHashMap.newKeySet();
    private volatile Instant lastSeen;

    RuntimeSession(String sessionId, SessionPeer peer, Handshake handshake, Instant connectedAt,
                   long authorityOrder) {
        this.sessionId = sessionId;
        this.peer = peer;
        this.handshake = handshake;
        this.capabilitySnapshot = new com.mccompanion.protocol.SessionCapabilitySnapshot(0, handshake.capabilities());
        this.connectedAt = connectedAt;
        this.authorityOrder = authorityOrder;
        this.lastSeen = connectedAt;
    }

    public String sessionId() { return sessionId; }
    public SessionPeer peer() { return peer; }
    public Handshake handshake() { return handshake.withCapabilities(capabilitySnapshot.capabilities()); }
    public long capabilityRevision() { return capabilitySnapshot.revision(); }
    public com.mccompanion.protocol.SessionCapabilitySnapshot capabilitySnapshot() { return capabilitySnapshot; }
    synchronized boolean updateCapabilities(com.mccompanion.protocol.SessionCapabilitySnapshot snapshot) {
        if (snapshot.revision() <= capabilitySnapshot.revision()) return false;
        capabilitySnapshot = snapshot;
        return true;
    }
    public boolean permits(String capability) {
        return capabilitySnapshot.permits(capability, "1.0");
    }
    public Instant connectedAt() { return connectedAt; }
    long authorityOrder() { return authorityOrder; }
    public Instant lastSeen() { return lastSeen; }
    public long nextSequence() { return outgoingSequence.incrementAndGet(); }
    /** Sequence allocation and transport enqueue form one operation across Runtime workers. */
    public synchronized void send(java.util.function.LongFunction<String> encoder) {
        if (!peer.isOpen()) throw new IllegalStateException("RUNTIME_OFFLINE");
        peer.send(encoder.apply(nextSequence()));
    }
    public void send(com.fasterxml.jackson.databind.node.ObjectNode message) {
        var copy = message.deepCopy();
        send(sequence -> com.mccompanion.runtime.json.Json.write(copy.put("sequence", sequence)));
    }
    /**
     * Incoming sequence is a replay fence meaning "observed by Runtime", not transaction commit.
     * A routed message that receives an error still consumes its sequence; clients retry with a
     * new sequence while keeping the message/command identity stable for idempotent recovery.
     */
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
    void removeCompanion(String id) { companionIds.remove(id); }
}
