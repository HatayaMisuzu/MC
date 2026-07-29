package com.mccompanion.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded reconnect-stable event-id window shared by Loader bridges.
 *
 * <p>The bridge instance survives a WebSocket reconnect. A conversation event whose ACK was lost
 * is therefore acknowledged again without displaying the same message to the player twice.</p>
 */
public final class ConversationDeliveryWindow {
    private final int capacity;
    private final Map<String, Boolean> delivered = new LinkedHashMap<>();

    public ConversationDeliveryWindow(int capacity) {
        if (capacity < 1 || capacity > 65_536) {
            throw new IllegalArgumentException("capacity must be 1..65536");
        }
        this.capacity = capacity;
    }

    /** Returns true exactly once for an event id while it remains in the bounded window. */
    public synchronized boolean firstDelivery(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 256) {
            throw new IllegalArgumentException("eventId must be 1..256 characters");
        }
        if (delivered.containsKey(eventId)) return false;
        delivered.put(eventId, Boolean.TRUE);
        while (delivered.size() > capacity) {
            delivered.remove(delivered.keySet().iterator().next());
        }
        return true;
    }

    public synchronized int size() {
        return delivered.size();
    }
}
