package com.mccompanion.runtime.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SurvivalEventNormalizerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    @TempDir Path temporary;

    @Test
    void localRecoveryRetainsDurableTaskAndExistingDeduplicationWithoutHidingOtherHazards() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("local-recovery.db"))) {
            database.initialize();
            var tasks = new com.mccompanion.runtime.task.TaskRepository(database,
                    new com.mccompanion.runtime.task.TaskEventStore(database));
            var task = tasks.create("companion", com.mccompanion.runtime.task.TaskType.TRAVEL,
                    "existing route", Json.object().put("completedSteps", 2));
            task = tasks.transition(task.taskId(), task.revision(), com.mccompanion.runtime.task.TaskState.ACCEPTED,
                    "CommandAccepted", Json.object());
            task = tasks.transition(task.taskId(), task.revision(), com.mccompanion.runtime.task.TaskState.RUNNING,
                    "BehaviorStarted", Json.object());
            ObjectNode body = payload("LOW_HEALTH", "ACTIVE", NOW)
                    .put("behaviorId", task.behaviorId()).put("localSafetyHandling", true);
            var normalizer = new SurvivalEventNormalizer(clock());
            var low = normalizer.normalize(body, Optional.of(task));
            var events = new RuntimeEventRepository(database, clock(), 8);
            assertEquals("EVENT_ADMITTED", events.admit(low.event(), low.policy()).code());
            assertEquals("EVENT_DUPLICATE", events.admit(low.event(), low.policy()).code());
            assertEquals(task.taskId(), low.event().taskId());
            assertEquals(RuntimeEvent.Priority.CRITICAL, low.event().priority());
            assertFalse(RuntimeEventBrainDispatcher.wakeEligible(low.event()));
            var persisted = tasks.get(task.taskId()).orElseThrow();
            assertEquals(task, persisted);
            assertEquals(2, persisted.payload().path("completedSteps").asInt());
            assertTrue(RuntimeEventBrainDispatcher.wakeEligible(normalizer.normalize(
                    body.put("localSafetyHandling", false), Optional.of(task)).event()));
            for (String type : java.util.List.of("FIRE", "LAVA", "LOW_AIR", "DEATH")) {
                var hazard = payload(type, type.equals("DEATH") ? "DEAD" : "ACTIVE", NOW)
                        .put("localSafetyHandling", true);
                assertTrue(RuntimeEventBrainDispatcher.wakeEligible(normalizer.normalize(hazard, Optional.of(task)).event()));
            }
        }
    }

    @Test
    void assignsServerOwnedSemanticsAndCoalescesDamage() {
        SurvivalEventNormalizer normalizer = new SurvivalEventNormalizer(clock());
        ObjectNode body = payload("DAMAGE", "ACTIVE", NOW);
        body.put("priority", "LOW");
        body.put("damageAmount", 4.0D).put("previousHealth", 20.0D);
        RuntimeEvent event = normalizer.normalize(body, Optional.empty()).event();
        assertEquals(RuntimeEvent.Priority.HIGH, event.priority());
        assertEquals(RuntimeEvent.Category.SURVIVAL, event.category());
        assertEquals("survival-damage:companion", event.coalesceKey());
        assertEquals(4.0D, event.payload().path("damageAmount").asDouble());
        assertFalse(RuntimeEventBrainDispatcher.wakeEligible(event));

        RuntimeEvent fire = normalizer.normalize(payload("FIRE", "ACTIVE", NOW), Optional.empty()).event();
        assertEquals(RuntimeEvent.Priority.CRITICAL, fire.priority());
        assertTrue(RuntimeEventBrainDispatcher.wakeEligible(fire));
    }

    @Test
    void deathInvalidatesPendingPreDeathSurvivalEventsBeforeDurableAdmission() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("death.db"));
             RuntimeEventService service = service(database)) {
            SurvivalEventNormalizer normalizer = new SurvivalEventNormalizer(clock());
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock(), 8);
            SurvivalEventNormalizer.Normalized damage = normalizer.normalize(
                    payload("DAMAGE", "ACTIVE", NOW.minusSeconds(1)), Optional.empty());
            repository.admit(damage.event(), RuntimeEvent.AdmissionPolicy.immediate());
            RuntimeEvent dispatchingDamage = repository.claimReady().orElseThrow();
            service.admitSurvival(normalizer.normalize(
                    payload("LOW_HEALTH", "ACTIVE", NOW.minusMillis(500)), Optional.empty()));
            SurvivalEventNormalizer.Normalized death = normalizer.normalize(
                    payload("DEATH", "DEAD", NOW), Optional.empty());
            assertTrue(death.invalidatePreDeath());
            assertEquals("EVENT_ADMITTED", service.admitSurvival(death).code());

            repository.defer(dispatchingDamage.eventId(), java.time.Duration.ZERO);
            assertEquals(1, repository.pendingCount("companion"));
            RuntimeEvent claimed = repository.claimReady().orElseThrow();
            assertEquals("DEATH", claimed.eventType());
            assertEquals(RuntimeEvent.Priority.CRITICAL, claimed.priority());
        }
    }

    private static RuntimeEventService service(RuntimeDatabase database) throws Exception {
        database.initialize();
        return new RuntimeEventService(new RuntimeEventRepository(database, clock(), 8), clock());
    }

    private static ObjectNode payload(String type, String lifecycle, Instant occurredAt) {
        ObjectNode payload = Json.object().put("eventId", "body-" + type + '-' + occurredAt)
                .put("eventType", type).put("companionId", "companion")
                .put("behaviorId", "behavior").put("tick", 42)
                .put("occurredAt", occurredAt.toString())
                .put("previousHealth", type.equals("DEATH") ? 4.0D : 20.0D)
                .put("damageAmount", type.equals("DAMAGE") ? 1.0D : 0.0D);
        boolean dead = lifecycle.equals("DEAD");
        payload.set("vitals", Json.object().put("lifecycle", lifecycle)
                .put("health", dead ? 0.0D : type.equals("LOW_HEALTH") ? 5.0D : 19.0D)
                .put("maxHealth", dead ? 0.0D : 20.0D)
                .put("air", dead ? 0 : 300).put("maxAir", dead ? 0 : 300)
                .put("onFire", type.equals("FIRE")).put("inLava", false)
                .put("onGround", true).put("fallDistance", 0.0D));
        return payload;
    }

    private static Clock clock() {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return NOW; }
        };
    }
}
