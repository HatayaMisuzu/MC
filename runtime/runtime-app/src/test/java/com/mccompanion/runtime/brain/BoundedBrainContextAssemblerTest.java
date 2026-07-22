package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBrainContextAssemblerTest {
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
