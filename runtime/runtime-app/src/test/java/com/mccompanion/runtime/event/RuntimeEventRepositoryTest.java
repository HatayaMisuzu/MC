package com.mccompanion.runtime.event;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeEventRepositoryTest {
    @TempDir Path temporary;

    @Test
    void deduplicatesAndRejectsAlreadyStaleEvents() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        try (RuntimeDatabase database = database("dedup.db")) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);

            assertTrue(repository.admit(event("one", "same", null, null,
                    RuntimeEvent.Priority.MEDIUM, clock), RuntimeEvent.AdmissionPolicy.immediate()).admitted());
            RuntimeEventRepository.Admission duplicate = repository.admit(
                    event("two", "same", null, null, RuntimeEvent.Priority.MEDIUM, clock),
                    RuntimeEvent.AdmissionPolicy.immediate());
            assertFalse(duplicate.admitted());
            assertEquals("EVENT_DUPLICATE", duplicate.code());
            RuntimeEvent stale = new RuntimeEvent("stale", RuntimeEvent.Category.TASK, "TASK_PROGRESS",
                    RuntimeEvent.Priority.LOW, "TASK_RUNTIME", "companion", "task", null,
                    Json.object(), "stale-key", null, null,
                    clock.instant().minusSeconds(20), clock.instant(), clock.instant().minusSeconds(1),
                    Json.object());
            assertEquals("EVENT_STALE",
                    repository.admit(stale, RuntimeEvent.AdmissionPolicy.immediate()).code());
            assertEquals(1, repository.pendingCount("companion"));
        }
    }

    @Test
    void debouncesAndCoalescesProgressToTheLatestPayload() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T01:00:00Z"));
        try (RuntimeDatabase database = database("coalesce.db")) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);
            RuntimeEvent.AdmissionPolicy policy =
                    new RuntimeEvent.AdmissionPolicy(Duration.ofSeconds(2), Duration.ZERO);
            RuntimeEvent first = event("progress-1", "progress-1", "task-progress", null,
                    RuntimeEvent.Priority.LOW, clock, 1);
            assertEquals("EVENT_ADMITTED", repository.admit(first, policy).code());
            clock.advance(Duration.ofSeconds(1));
            RuntimeEvent second = event("progress-2", "progress-2", "task-progress", null,
                    RuntimeEvent.Priority.HIGH, clock, 2);
            RuntimeEventRepository.Admission coalesced = repository.admit(second, policy);
            assertTrue(coalesced.admitted());
            assertTrue(coalesced.coalesced());
            assertEquals(2, coalesced.occurrenceCount());
            assertTrue(repository.claimReady().isEmpty());

            clock.advance(Duration.ofSeconds(2));
            RuntimeEvent claimed = repository.claimReady().orElseThrow();
            assertEquals("progress-1", claimed.eventId());
            assertEquals(RuntimeEvent.Priority.HIGH, claimed.priority());
            assertEquals(2, claimed.payload().path("step").asInt());
            assertEquals(2, claimed.occurrenceCount());
            repository.defer(claimed.eventId(), Duration.ZERO);
            assertEquals("EVENT_DUPLICATE", repository.admit(first, policy).code());
            assertEquals("EVENT_DUPLICATE", repository.admit(second, policy).code());
            clock.advance(Duration.ofSeconds(2));
            assertEquals(2, repository.claimReady().orElseThrow().occurrenceCount());
        }
    }

    @Test
    void enforcesCooldownAfterDelivery() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T02:00:00Z"));
        try (RuntimeDatabase database = database("cooldown.db")) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);
            RuntimeEvent.AdmissionPolicy policy =
                    new RuntimeEvent.AdmissionPolicy(Duration.ZERO, Duration.ofSeconds(10));
            assertTrue(repository.admit(event("first", "first", null, "task-state",
                    RuntimeEvent.Priority.MEDIUM, clock), policy).admitted());
            repository.delivered(repository.claimReady().orElseThrow().eventId());

            assertEquals("EVENT_COOLDOWN", repository.admit(event("second", "second", null,
                    "task-state", RuntimeEvent.Priority.MEDIUM, clock), policy).code());
            clock.advance(Duration.ofSeconds(11));
            assertTrue(repository.admit(event("third", "third", null, "task-state",
                    RuntimeEvent.Priority.MEDIUM, clock), policy).admitted());
        }
    }

    @Test
    void boundsPendingEventsAndLetsHigherPriorityEvictOneLowerEvent() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T03:00:00Z"));
        try (RuntimeDatabase database = database("capacity.db")) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 2);
            assertTrue(repository.admit(event("low-1", "low-1", null, null,
                    RuntimeEvent.Priority.LOW, clock), RuntimeEvent.AdmissionPolicy.immediate()).admitted());
            assertTrue(repository.admit(event("low-2", "low-2", null, null,
                    RuntimeEvent.Priority.LOW, clock), RuntimeEvent.AdmissionPolicy.immediate()).admitted());
            assertEquals("EVENT_QUEUE_FULL", repository.admit(event("low-3", "low-3", null, null,
                    RuntimeEvent.Priority.LOW, clock), RuntimeEvent.AdmissionPolicy.immediate()).code());
            assertTrue(repository.admit(event("critical", "critical", null, null,
                    RuntimeEvent.Priority.CRITICAL, clock), RuntimeEvent.AdmissionPolicy.immediate()).admitted());
            assertEquals(2, repository.pendingCount("companion"));
            assertEquals("critical", repository.claimReady().orElseThrow().eventId());
        }
    }

    @Test
    void recoversOnlyUnexpiredInterruptedDelivery() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T04:00:00Z"));
        try (RuntimeDatabase database = database("recovery.db")) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);
            assertTrue(repository.admit(event("recover", "recover", null, null,
                    RuntimeEvent.Priority.HIGH, clock), RuntimeEvent.AdmissionPolicy.immediate()).admitted());
            assertEquals("recover", repository.claimReady().orElseThrow().eventId());
            assertEquals(1, repository.recoverInterrupted());
            assertEquals("recover", repository.claimReady().orElseThrow().eventId());
            clock.advance(Duration.ofMinutes(6));
            assertEquals(0, repository.recoverInterrupted());
            assertEquals(0, repository.pendingCount("companion"));
        }
    }

    private RuntimeDatabase database(String name) throws Exception {
        RuntimeDatabase database = new RuntimeDatabase(temporary.resolve(name));
        database.initialize();
        return database;
    }

    private static RuntimeEvent event(String id, String dedup, String coalesce, String cooldown,
                                      RuntimeEvent.Priority priority, MutableClock clock) {
        return event(id, dedup, coalesce, cooldown, priority, clock, 1);
    }

    private static RuntimeEvent event(String id, String dedup, String coalesce, String cooldown,
                                      RuntimeEvent.Priority priority, MutableClock clock, int step) {
        Instant now = clock.instant();
        return new RuntimeEvent(id, RuntimeEvent.Category.TASK, "TASK_PROGRESS", priority,
                "TASK_RUNTIME", "companion", "task", null,
                Json.object().put("kind", "task"), dedup, coalesce, cooldown,
                now, now, now.plus(Duration.ofMinutes(5)), Json.object().put("step", step));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
