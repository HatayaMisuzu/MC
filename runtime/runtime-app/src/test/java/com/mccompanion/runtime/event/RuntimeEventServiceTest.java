package com.mccompanion.runtime.event;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.task.TaskType;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeEventServiceTest {
    @TempDir Path temporary;

    @Test
    void permanentBrainBudgetFailureIsNotRetried() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        try (RuntimeDatabase database = database("budget.db");
             RuntimeEventService service = service(database, clock)) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);
            service.onTaskUpdated(task(2, TaskState.COMPLETED, Json.object()), Json.object());
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            service.start(event -> {
                attempts.incrementAndGet();
                throw new com.mccompanion.runtime.brain.LiveBrainBudgetException("BRAIN_INPUT_TOKEN_BUDGET_EXCEEDED",
                        com.mccompanion.runtime.brain.LiveBrainFailureCategory.RATE_LIMIT);
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (repository.pendingCount("companion") != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, repository.pendingCount("companion"));
            service.drainOnce();
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void normalizesBlockedTaskWithTaskAndTargetBindings() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T05:00:00Z"));
        try (RuntimeDatabase database = database("task.db");
             RuntimeEventService service = service(database, clock)) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);
            TaskRecord task = task(4, TaskState.BLOCKED, Json.object().set("target",
                    Json.object().put("dimension", "minecraft:overworld")
                            .put("x", 12).put("y", 64).put("z", -3)));
            service.onTaskUpdated(task, Json.object().put("event", "BLOCKED")
                    .put("eventId", "body-event-4").put("code", "PATH_UNREACHABLE")
                    .put("occurredAt", clock.instant().toString()));

            RuntimeEvent event = repository.claimReady().orElseThrow();
            assertEquals("TASK_BLOCKED", event.eventType());
            assertEquals(RuntimeEvent.Priority.CRITICAL, event.priority());
            assertEquals("task-1", event.taskId());
            assertEquals("minecraft:overworld", event.target().path("dimension").asText());
            assertEquals("PATH_UNREACHABLE",
                    event.payload().path("observation").path("code").asText());
        }
    }

    @Test
    void coalescesRapidTaskProgressAtOnePendingBoundary() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T06:00:00Z"));
        try (RuntimeDatabase database = database("progress.db");
             RuntimeEventService service = service(database, clock)) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);
            service.onTaskUpdated(task(5, TaskState.RUNNING, Json.object()),
                    Json.object().put("event", "PROGRESS").put("eventId", "progress-5")
                            .put("progress", 0.25).put("occurredAt", clock.instant().toString()));
            clock.advance(Duration.ofMillis(100));
            service.onTaskUpdated(task(6, TaskState.RUNNING, Json.object()),
                    Json.object().put("event", "PROGRESS").put("eventId", "progress-6")
                            .put("progress", 0.75).put("occurredAt", clock.instant().toString()));
            service.onTaskUpdated(task(7, TaskState.RUNNING, Json.object()),
                    Json.object().put("event", "PROGRESS").put("eventId", "progress-7")
                            .put("progress", 0.76).put("occurredAt", clock.instant().toString()));

            assertEquals(1, repository.pendingCount("companion"));
            assertTrue(repository.claimReady().isEmpty());
            clock.advance(Duration.ofMillis(600));
            RuntimeEvent event = repository.claimReady().orElseThrow();
            assertEquals("TASK_PROGRESS", event.eventType());
            assertEquals(2, event.occurrenceCount());
            assertEquals(0.75, event.payload().path("observation").path("progress").asDouble());
        }
    }

    @Test
    void normalizesTaskGraphCheckpointAndTerminalIndependently() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T07:00:00Z"));
        try (RuntimeDatabase database = database("graph.db");
             RuntimeEventService service = service(database, clock)) {
            RuntimeEventRepository repository = new RuntimeEventRepository(database, clock, 8);
            TaskGraphExecutionRecord running = graph(3, "RUNNING", "");
            service.onLifecycle(running, "CHECKPOINT",
                    Json.object().put("nodeId", "checkpoint-1").put("content", "ore collected"));
            RuntimeEvent checkpoint = repository.claimReady().orElseThrow();
            assertEquals("TASK_GRAPH_CHECKPOINT", checkpoint.eventType());
            assertEquals("execution-1", checkpoint.taskGraphExecutionId());
            repository.delivered(checkpoint.eventId());

            TaskGraphExecutionRecord failed = graph(4, "FAILED", "TOOL_BLOCKED");
            service.onLifecycle(failed, "FAILED", Json.object().put("reasonCode", "TOOL_BLOCKED"));
            RuntimeEvent terminal = repository.claimReady().orElseThrow();
            assertEquals("TASK_GRAPH_TERMINAL", terminal.eventType());
            assertEquals(RuntimeEvent.Priority.CRITICAL, terminal.priority());
            assertEquals("TOOL_BLOCKED", terminal.payload().path("resultCode").asText());
        }
    }

    private RuntimeDatabase database(String name) throws Exception {
        RuntimeDatabase database = new RuntimeDatabase(temporary.resolve(name));
        database.initialize();
        return database;
    }

    private static RuntimeEventService service(RuntimeDatabase database, MutableClock clock) {
        return new RuntimeEventService(new RuntimeEventRepository(database, clock, 8), clock);
    }

    private static TaskRecord task(long revision, TaskState state, com.fasterxml.jackson.databind.JsonNode payload) {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        return new TaskRecord("task-1", "task-1", null, "companion", TaskType.TRAVEL,
                state, revision, "go there", payload, "behavior-1", revision, 7,
                state == TaskState.RECONCILIATION_REQUIRED, now, now);
    }

    private static TaskGraphExecutionRecord graph(long revision, String state, String resultCode) {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        return new TaskGraphExecutionRecord("execution-1", "controller", "brain-session", "companion",
                "graph", "1", "hash", Json.object(), state, "node-1",
                Json.MAPPER.createArrayNode(), Json.object(), Json.object(), Json.object(), Json.object(),
                Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode(), Json.object(),
                Json.MAPPER.createArrayNode(), Json.object(), Json.object(), Json.object(),
                revision, resultCode, now, now, Json.object());
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
