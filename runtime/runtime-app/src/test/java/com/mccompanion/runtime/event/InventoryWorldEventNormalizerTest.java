package com.mccompanion.runtime.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryWorldEventNormalizerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");
    @TempDir Path temporary;

    @Test
    void ownsPriorityAndBindsOnlyTheMatchingActiveBehavior() {
        InventoryWorldEventNormalizer normalizer = new InventoryWorldEventNormalizer(clock());
        ObjectNode full = payload("INVENTORY_FULL", "INVENTORY", NOW);
        full.put("priority", "LOW");
        RuntimeEvent event = normalizer.normalize(full, Optional.of(task("behavior"))).event();
        assertEquals(RuntimeEvent.Category.INVENTORY, event.category());
        assertEquals(RuntimeEvent.Priority.CRITICAL, event.priority());
        assertEquals("task-1", event.taskId());
        assertEquals(0, event.target().path("freeSlots").asInt());
        assertTrue(RuntimeEventBrainDispatcher.wakeEligible(event));

        RuntimeEvent stale = normalizer.normalize(
                payload("TARGET_BLOCK_CHANGED", "WORLD", NOW),
                Optional.of(task("other-behavior"))).event();
        assertNull(stale.taskId());
        assertFalse(RuntimeEventBrainDispatcher.wakeEligible(stale));
    }

    @Test
    void coalescesTargetStateAndRejectsStaleEventsAtDurableAdmission() throws Exception {
        InventoryWorldEventNormalizer.Normalized normalized = new InventoryWorldEventNormalizer(clock()).normalize(
                payload("TARGET_CONTAINER_CHANGED", "WORLD", NOW.minusSeconds(301)),
                Optional.of(task("behavior")));
        assertEquals("inventory-world-state:companion:TARGET_CONTAINER_CHANGED",
                normalized.event().coalesceKey());
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("events.db"))) {
            database.initialize();
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock(), 8);
            assertEquals("EVENT_STALE",
                    repository.admit(normalized.event(), normalized.policy()).code());
            assertEquals(0, repository.pendingCount("companion"));
        }
    }

    private static ObjectNode payload(String type, String category, Instant occurredAt) {
        int freeSlots = type.equals("INVENTORY_FULL") ? 0 : 4;
        ObjectNode payload = Json.object().put("eventId", "body-" + type + '-' + occurredAt)
                .put("eventType", type).put("category", category)
                .put("companionId", "companion").put("behaviorId", "behavior")
                .put("tick", 42).put("occurredAt", occurredAt.toString())
                .put("previousValue", "before").put("currentValue", "after");
        payload.set("inventory", Json.object().put("slots", 36).put("freeSlots", freeSlots)
                .set("counts", Json.object().put("minecraft:diamond", 2)));
        payload.set("world", Json.object().put("dimension", "minecraft:overworld")
                .put("timeOfDay", "DAY").put("weather", "CLEAR"));
        payload.set("target", Json.object().put("identity", "minecraft:overworld:1,2,3")
                .put("kind", "CONTAINER").put("present", true)
                .put("blockId", "minecraft:chest").put("blockFingerprint", "chest-state")
                .put("containerType", "minecraft:chest")
                .put("containerFingerprint", "slot0=diamond:2"));
        return payload;
    }

    private static TaskRecord task(String behaviorId) {
        return new TaskRecord("task-1", "task-1", null, "companion", TaskType.SKILL,
                TaskState.RUNNING, 3, "collect diamonds", Json.object().put("itemId", "minecraft:diamond"),
                behaviorId, 2, 1, false, NOW, NOW);
    }

    private static Clock clock() {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return NOW; }
        };
    }
}
