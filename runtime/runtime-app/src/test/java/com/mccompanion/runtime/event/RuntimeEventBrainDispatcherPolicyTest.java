package com.mccompanion.runtime.event;

import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeEventBrainDispatcherPolicyTest {
    @Test
    void onlyThreatMayWakeUnboundWhileTargetDeathRequiresCurrentTaskBinding() {
        assertTrue(RuntimeEventBrainDispatcher.wakeEligible(event(
                "HOSTILE_ENTERED_THREAT_RANGE", RuntimeEvent.Priority.CRITICAL, null)));
        assertFalse(RuntimeEventBrainDispatcher.wakeEligible(event(
                "CURRENT_TARGET_DIED", RuntimeEvent.Priority.CRITICAL, null)));
        assertTrue(RuntimeEventBrainDispatcher.wakeEligible(event(
                "CURRENT_TARGET_DIED", RuntimeEvent.Priority.CRITICAL, "task-1")));
        assertFalse(RuntimeEventBrainDispatcher.wakeEligible(event(
                "PLAYER_ENTERED_RANGE", RuntimeEvent.Priority.MEDIUM, null)));
        assertTrue(RuntimeEventBrainDispatcher.wakeEligible(survival(
                "DEATH", RuntimeEvent.Priority.CRITICAL)));
        assertFalse(RuntimeEventBrainDispatcher.wakeEligible(survival(
                "RESPAWN", RuntimeEvent.Priority.HIGH)));
        assertTrue(RuntimeEventBrainDispatcher.wakeEligible(inventoryWorld(
                RuntimeEvent.Category.INVENTORY, "INVENTORY_FULL", RuntimeEvent.Priority.CRITICAL, null)));
        assertTrue(RuntimeEventBrainDispatcher.wakeEligible(inventoryWorld(
                RuntimeEvent.Category.INVENTORY, "INVENTORY_FULL", RuntimeEvent.Priority.CRITICAL, "task-1")));
        assertFalse(RuntimeEventBrainDispatcher.wakeEligible(inventoryWorld(
                RuntimeEvent.Category.WORLD, "WEATHER_CHANGED", RuntimeEvent.Priority.MEDIUM, null)));
    }

    private static RuntimeEvent event(String type, RuntimeEvent.Priority priority, String taskId) {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        return new RuntimeEvent("event-" + type, RuntimeEvent.Category.PLAYER_ENTITY, type, priority,
                "MINECRAFT_ENTITY_OBSERVER", "companion", taskId, null,
                Json.object().put("entityId", "entity"), "dedup-" + type, null, null,
                now, now, now.plusSeconds(60), Json.object());
    }

    private static RuntimeEvent survival(String type, RuntimeEvent.Priority priority) {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        return new RuntimeEvent("event-" + type, RuntimeEvent.Category.SURVIVAL, type, priority,
                "MINECRAFT_SURVIVAL_OBSERVER", "companion", null, null,
                Json.object().put("companionId", "companion"), "dedup-" + type, null, null,
                now, now, now.plusSeconds(60), Json.object());
    }

    private static RuntimeEvent inventoryWorld(RuntimeEvent.Category category, String type,
                                                RuntimeEvent.Priority priority, String taskId) {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        return new RuntimeEvent("event-" + type, category, type, priority,
                "MINECRAFT_INVENTORY_WORLD_OBSERVER", "companion", taskId, null,
                Json.object().put("companionId", "companion"), "dedup-" + type, null, null,
                now, now, now.plusSeconds(60), Json.object());
    }
}
