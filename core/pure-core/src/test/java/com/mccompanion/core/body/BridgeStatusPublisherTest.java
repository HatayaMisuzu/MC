package com.mccompanion.core.body;

import com.mccompanion.minecraft.bridge.BridgeStatusPublisher;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgeStatusPublisherTest {
    private final BridgeStatusPublisher publisher = new BridgeStatusPublisher();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<String> published = new ArrayList<>();

    @Test void acceptanceDoesNotCompleteAndObservedCompletionPublishesOnceWithEvidence() {
        publisher.accepted("body", "behavior", "RUNNING");
        publish("RUNNING", "", true);
        assertTrue(events.isEmpty());
        publish("IDLE", "success=true", true);
        publish("IDLE", "success=true", true);
        assertEquals(1, events.size());
        assertEquals("completed", events.get(0).get("event"));
        var evidence = (Map<?, ?>) events.get(0).get("snapshot");
        assertEquals("world", evidence.get("worldId"));
        assertEquals(9L, evidence.get("controlEpoch"));
        assertEquals(1.0, evidence.get("positionX"));
        assertEquals(List.of("behavior"), published);
    }

    @Test void rejectedDeliveryIsRetainedUntilItCanBeQueued() {
        publisher.accepted("body", "behavior", "RUNNING");
        publish("IDLE", "success=true", false);
        assertTrue(published.isEmpty());
        publish("IDLE", "success=true", true);
        assertEquals(List.of("behavior"), published);
    }

    @Test void safetyPauseProducesBlockedEvidenceAndDisconnectForgetsOldLifecycle() {
        publisher.accepted("body", "behavior", "RUNNING");
        publish("PAUSED", "failure=SAFETY_PREEMPTED reason=threat", true);
        assertEquals("blocked", events.get(0).get("event"));
        assertEquals("SAFETY_PREEMPTED", events.get(0).get("failureCode"));
        assertTrue(publisher.announced("body"));
        publisher.clear();
        assertFalse(publisher.announced("body"));
        publish("IDLE", "success=true", true);
        assertEquals(1, events.size());
    }

    private void publish(String state, String evidence, boolean acceptEvent) {
        var snapshot = new BodySnapshots.RuntimeSnapshot("body", "owner", "Companion", "overworld",
                1, 2, 3, "ACTIVE", "behavior", state, 4, 9, true,
                20, 20, 20, 300, false, false, 36, Map.of(), List.of(), evidence, null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        publisher.publish(List.of(new BridgeStatusPublisher.ObservedBody(snapshot, Map.of(), Map.of(), List.of())),
                "world", 20, (type, payload) -> {
                    if (!type.equals("behavior_event")) return true;
                    if (acceptEvent) events.add(payload);
                    return acceptEvent;
                }, published::add);
    }
}
