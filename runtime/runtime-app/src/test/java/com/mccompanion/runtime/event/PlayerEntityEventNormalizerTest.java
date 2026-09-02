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
import static org.junit.jupiter.api.Assertions.assertNull;

final class PlayerEntityEventNormalizerTest {
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");
    @TempDir Path temporary;

    @Test
    void admittedLocalThreatKeepsTaskBindingPriorityAndCooldownWithoutBrainReplan() {
        var body = payload("HOSTILE_ENTERED_THREAT_RANGE", "behavior-1", NOW).put("localSafetyHandling", true);
        var normalized = new PlayerEntityEventNormalizer(clock()).normalize(body, Optional.of(task("behavior-1")));
        assertEquals(RuntimeEvent.Priority.CRITICAL, normalized.event().priority());
        assertEquals("task-1", normalized.event().taskId());
        org.junit.jupiter.api.Assertions.assertFalse(RuntimeEventBrainDispatcher.wakeEligible(normalized.event()));
        assertEquals(java.time.Duration.ofSeconds(10), normalized.policy().cooldown());
    }

    @Test
    void bindsOnlyBehaviorRelevantEdgesAndUsesServerOwnedPriority() {
        PlayerEntityEventNormalizer normalizer = new PlayerEntityEventNormalizer(clock());
        ObjectNode targetLost = payload("CURRENT_TARGET_LOST", "behavior-1", NOW);
        targetLost.put("priority", "LOW");
        RuntimeEvent event = normalizer.normalize(targetLost, Optional.of(task("behavior-1"))).event();
        assertEquals(RuntimeEvent.Priority.HIGH, event.priority());
        assertEquals("task-1", event.taskId());
        assertEquals("entity-1", event.target().path("entityId").asText());

        RuntimeEvent player = normalizer.normalize(
                payload("PLAYER_ENTERED_RANGE", "behavior-1", NOW), Optional.of(task("behavior-1"))).event();
        assertNull(player.taskId());
    }

    @Test
    void behaviorMismatchCannotBindAnUnrelatedTask() {
        RuntimeEvent event = new PlayerEntityEventNormalizer(clock()).normalize(
                payload("CURRENT_TARGET_DIED", "stale-behavior", NOW), Optional.of(task("behavior-1"))).event();
        assertNull(event.taskId());
        assertEquals(RuntimeEvent.Priority.CRITICAL, event.priority());
    }

    @Test
    void staleBodyEventIsRejectedByTheDurableRepository() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("events.db"))) {
            database.initialize();
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock(), 8);
            PlayerEntityEventNormalizer.Normalized normalized = new PlayerEntityEventNormalizer(clock()).normalize(
                    payload("HOSTILE_ENTERED_THREAT_RANGE", "behavior-1", NOW.minusSeconds(121)),
                    Optional.of(task("behavior-1")));
            RuntimeEventRepository.Admission admission = repository.admit(
                    normalized.event(), normalized.policy());
            assertEquals("EVENT_STALE", admission.code());
            assertEquals(0, repository.pendingCount("companion"));
        }
    }

    @Test
    void acceptedTargetEdgePersistsWithStableIdentityAndBinding() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("accepted.db"))) {
            database.initialize();
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock(), 8);
            PlayerEntityEventNormalizer.Normalized normalized = new PlayerEntityEventNormalizer(clock()).normalize(
                    payload("TARGET_REAPPEARED", "behavior-1", NOW), Optional.of(task("behavior-1")));
            assertEquals("EVENT_ADMITTED",
                    repository.admit(normalized.event(), normalized.policy()).code());
            RuntimeEvent persisted = repository.claimReady().orElseThrow();
            assertEquals(RuntimeEvent.Category.PLAYER_ENTITY, persisted.category());
            assertEquals("task-1", persisted.taskId());
            assertEquals("MINECRAFT_ENTITY_OBSERVER", persisted.source());
        }
    }

    private static ObjectNode payload(String type, String behaviorId, Instant occurredAt) {
        ObjectNode payload = Json.object().put("eventId", "body-event-1")
                .put("eventType", type).put("companionId", "companion")
                .put("behaviorId", behaviorId).put("tick", 42)
                .put("occurredAt", occurredAt.toString());
        payload.set("target", Json.object().put("entityId", "entity-1")
                .put("entityType", "minecraft:zombie").put("displayName", "Zombie")
                .put("player", false).put("hostile", true).put("alive", true)
                .put("distanceSquared", 9.0D));
        return payload;
    }

    private static TaskRecord task(String behaviorId) {
        return new TaskRecord("task-1", "task-1", null, "companion", TaskType.SKILL,
                TaskState.RUNNING, 3, "attack target", Json.object().put("entityId", "entity-1"),
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
