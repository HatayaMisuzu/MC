package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBrainContextAssemblerTest {
    @Test void clipsWorldModelByWholeEntriesAndEnforcesTotalSerializedBudget() {
        var world = Json.object().put("source", "WORLD_MODEL").put("emittedEntries", 30).put("omittedEntries", 0);
        var entries = world.putArray("current");
        for (int i = 0; i < 30; i++) entries.addObject().put("identity", "entry-" + i)
                .put("source", "CONNECTED_BODY_OBSERVATION").put("observedAt", "2026-09-02T00:00:00Z")
                .put("ttlMillis", 1000).put("stale", true).put("verified", false).put("invalidation", "TTL_EXPIRED")
                .put("value", "x".repeat(100));
        var clipped = BoundedBrainContextAssembler.bounded(world, 4000, Json.object(), "world");
        assertTrue(Json.write(clipped).length() <= 4000);
        for (var entry : clipped.path("current")) {
            assertTrue(entry.path("stale").asBoolean());
            assertFalse(entry.path("verified").asBoolean());
            assertEquals("TTL_EXPIRED", entry.path("invalidation").asText());
            assertTrue(entry.has("observedAt"));
        }
        var large = Json.object();
        for (int i = 0; i < 30; i++) large.put("field-" + i, "\\\"\n".repeat(1000));
        var result = BoundedBrainContextAssembler.assemble(new AgentContext("companion", world,
                java.util.Collections.nCopies(12, "x".repeat(2000)), large, List.of(), List.of(),
                large, large, large, large, 5));
        assertTrue(Json.write(result.context()).length() <= BoundedBrainContextAssembler.DEFAULT_TOTAL_CHARS);
        assertFalse(Json.write(result.context().path("verifiedWorld")).contains("\"verified\":true"));
    }

    @Test void clipsEachCategoryDeduplicatesMemoryAndNeverEmitsUnboundedGraphOrToolLog() {
        String privateBody = "sensitive-body-".repeat(4_000);
        var world = Json.object().put("position", "verified").put("oversized", privateBody);
        var task = Json.object().put("state", "RUNNING").put("completeGraph", "node-log-".repeat(4_000));
        var memory = Json.object().putArray("approved").add("same-memory").add("same-memory")
                .add("m".repeat(10_000));
        List<String> conversation = new ArrayList<>();
        for (int index = 0; index < 30; index++) conversation.add("turn-" + index + '-' + "x".repeat(2_000));
        AgentContext input = new AgentContext("companion", world, conversation, task,
                List.of("home", "home"), List.of("Observe", "Observe"), memory, 5);
        var result = BoundedBrainContextAssembler.assemble(input);
        String emitted = Json.write(result.context());
        assertTrue(emitted.length() <= BoundedBrainContextAssembler.DEFAULT_TOTAL_CHARS + 1_000, emitted.length() + " chars");
        assertTrue(result.clippingStats().path("verifiedWorldClipped").asBoolean());
        assertTrue(result.clippingStats().path("activeTaskClipped").asBoolean());
        assertEquals(1, result.context().path("knownLandmarks").size());
        assertEquals(1, result.context().path("availableCapabilities").size());
        assertFalse(emitted.contains(privateBody));
        assertFalse(emitted.contains("node-log-".repeat(2_000)));
    }
}
