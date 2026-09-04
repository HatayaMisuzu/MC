package com.mccompanion.runtime.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRecord;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Normalizes durable task events and drains their bounded queue without doing any planning. */
public final class RuntimeEventService implements CommandService.TaskLifecycleListener,
        TaskGraphRuntime.LifecycleListener, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeEventService.class);
    private static final Duration TASK_EVENT_TTL = Duration.ofMinutes(10);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);
    private final RuntimeEventRepository repository;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Dispatcher dispatcher = event -> DispatchResult.DEFERRED;
    private com.mccompanion.runtime.session.CompanionRepository companions;

    public void attachWorldModel(com.mccompanion.runtime.session.CompanionRepository companions) {
        this.companions = Objects.requireNonNull(companions);
    }

    public RuntimeEventService(RuntimeEventRepository repository) {
        this(repository, Clock.systemUTC());
    }

    RuntimeEventService(RuntimeEventRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcac-runtime-event-dispatch");
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start(Dispatcher dispatcher) throws SQLException {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("EVENT_SERVICE_ALREADY_STARTED");
        repository.recoverInterrupted();
        worker.scheduleWithFixedDelay(this::drainSafely, 0, 100, TimeUnit.MILLISECONDS);
    }

    public RuntimeEventRepository.Admission admit(RuntimeEvent event,
                                                  RuntimeEvent.AdmissionPolicy policy) throws SQLException {
        if (companions != null) companions.updateWorldModel(event.companionId(), model -> model.event(event));
        return repository.admit(event, policy);
    }

    public RuntimeEventRepository.Admission admitSurvival(
            SurvivalEventNormalizer.Normalized normalized) throws SQLException {
        Objects.requireNonNull(normalized, "normalized");
        if (companions != null) companions.updateWorldModel(normalized.event().companionId(),
                model -> model.event(normalized.event()));
        if (normalized.invalidatePreDeath()) {
            return repository.admitDeath(normalized.event(), normalized.policy());
        }
        return repository.admit(normalized.event(), normalized.policy());
    }

    @Override public void onTaskUpdated(TaskRecord task, JsonNode observation) {
        try {
            if (companions != null) companions.updateWorldModel(task.companionId(), model -> model.runtime("task",
                    Json.object().put("taskId", task.taskId()).put("state", task.state().name())
                            .put("type", task.type().name()).put("revision", task.revision()),
                    taskTarget(task.payload()), clock.instant()));
            TaskTransition transition = taskTransition(task, observation);
            if (transition == null) return;
            Instant now = clock.instant();
            Instant occurred = instant(observation.path("occurredAt").asText(null), now);
            ObjectNode payload = Json.object().put("taskId", task.taskId())
                    .put("taskType", task.type().name()).put("state", task.state().name())
                    .put("revision", task.revision()).put("transition", transition.eventType());
            payload.set("observation", boundedObservation(observation));
            JsonNode target = taskTarget(task.payload());
            String eventIdentity = observation.path("eventId").asText("").strip();
            if (eventIdentity.isEmpty()) {
                eventIdentity = task.taskId() + ':' + task.revision() + ':' + transition.eventType();
            }
            String eventId = "task-event-" + Digests.sha256(eventIdentity).substring(0, 32);
            String source = observation.path("source").asText("TASK_RUNTIME").strip();
            if (!source.matches("[A-Z][A-Z0-9_]{0,63}")) source = "TASK_RUNTIME";
            String dedupIdentity = transition.coalesce()
                    ? "task:" + task.taskId() + ":progress:" + progressIdentity(observation)
                    : "task:" + task.taskId() + ':' + task.revision() + ':' + transition.eventType();
            RuntimeEvent event = new RuntimeEvent(eventId, RuntimeEvent.Category.TASK,
                    transition.eventType(), transition.priority(), source, task.companionId(),
                    task.taskId(), null, target,
                    dedupIdentity,
                    transition.coalesce() ? "task:" + task.taskId() + ":progress" : null,
                    transition.coalesce() ? "task:" + task.taskId() + ":progress" : null,
                    occurred, now, occurred.plus(TASK_EVENT_TTL), payload);
            repository.admit(event, transition.policy());
        } catch (SQLException | IllegalArgumentException failure) {
            LOGGER.warn("Unable to admit task event: task={}", task.taskId(), failure);
        }
    }

    @Override public void onLifecycle(TaskGraphExecutionRecord record, String transition,
                                      JsonNode details) {
        try {
            if (companions != null) companions.updateWorldModel(record.companionId(), model -> model.runtime("taskGraph",
                    Json.object().put("executionId", record.executionId()).put("state", record.state())
                            .put("currentNodeId", record.currentNodeId()), null, clock.instant()));
            GraphTransition mapped = graphTransition(transition);
            if (mapped == null) return;
            Instant now = clock.instant();
            JsonNode boundedDetails = boundedObservation(details);
            String identity = record.executionId() + ':' + record.revision() + ':' + mapped.eventType()
                    + ':' + Digests.sha256(Json.canonical(boundedDetails));
            ObjectNode payload = Json.object().put("executionId", record.executionId())
                    .put("graphId", record.graphId()).put("state", record.state())
                    .put("revision", record.revision()).put("transition", mapped.eventType())
                    .put("currentNodeId", record.currentNodeId() == null ? "" : record.currentNodeId())
                    .put("resultCode", record.resultCode() == null ? "" : record.resultCode());
            payload.set("details", boundedDetails);
            RuntimeEvent event = new RuntimeEvent(
                    "task-graph-event-" + Digests.sha256(identity).substring(0, 32),
                    RuntimeEvent.Category.TASK, mapped.eventType(), mapped.priority(),
                    "TASK_GRAPH_RUNTIME", record.companionId(), null, record.executionId(),
                    Json.object().put("currentNodeId",
                            record.currentNodeId() == null ? "" : record.currentNodeId()),
                    "graph:" + identity, mapped.coalesce() ? "graph:" + record.executionId() + ":progress" : null,
                    mapped.coalesce() ? "graph:" + record.executionId() + ":progress" : null,
                    now, now, now.plus(TASK_EVENT_TTL), payload);
            repository.admit(event, mapped.policy());
        } catch (SQLException | IllegalArgumentException failure) {
            LOGGER.warn("Unable to admit Task Graph event: execution={}", record.executionId(), failure);
        }
    }

    void drainOnce() throws Exception {
        var claimed = repository.claimReady();
        if (claimed.isEmpty()) return;
        RuntimeEvent event = claimed.orElseThrow();
        DispatchResult result;
        try {
            result = Objects.requireNonNull(dispatcher.dispatch(event), "dispatch result");
        } catch (com.mccompanion.runtime.brain.LiveBrainBudgetException exhausted) {
            repository.suppressed(event.eventId());
            LOGGER.warn("Runtime event stopped at Brain budget boundary: event={} code={}",
                    event.eventId(), exhausted.getMessage());
            return;
        } catch (Exception failure) {
            repository.retryFailed(event.eventId(), RETRY_DELAY);
            throw failure;
        }
        switch (result) {
            case DELIVERED -> repository.delivered(event.eventId());
            case SUPPRESSED -> repository.suppressed(event.eventId());
            case DEFERRED -> repository.defer(event.eventId(), RETRY_DELAY);
        }
    }

    private void drainSafely() {
        try {
            for (int index = 0; index < 8; index++) {
                int before = totalReadyHint();
                drainOnce();
                if (before == 0) break;
            }
        } catch (Exception failure) {
            LOGGER.warn("Runtime event dispatch iteration stopped safely", failure);
        }
    }

    /** Avoids holding a claimed event just to ask whether another one exists. */
    private int totalReadyHint() throws SQLException {
        return repository.hasReady() ? 1 : 0;
    }

    @Override public void close() {
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Runtime event worker did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static TaskTransition taskTransition(TaskRecord task, JsonNode observation) {
        String event = observation.path("event").asText("").toUpperCase(java.util.Locale.ROOT);
        if (!event.isBlank()) {
            return switch (event) {
                case "STARTED" -> TaskTransition.of("TASK_STARTED", RuntimeEvent.Priority.MEDIUM);
                case "PROGRESS" -> TaskTransition.progress();
                case "WAITING" -> TaskTransition.of("TASK_WAITING", RuntimeEvent.Priority.MEDIUM);
                case "PAUSED" -> TaskTransition.of("TASK_PAUSED", RuntimeEvent.Priority.HIGH);
                case "RESUMED" -> TaskTransition.of("TASK_RESUMED", RuntimeEvent.Priority.MEDIUM);
                case "BLOCKED" -> TaskTransition.of("TASK_BLOCKED", RuntimeEvent.Priority.CRITICAL);
                case "COMPLETED" -> TaskTransition.of("TASK_COMPLETED", RuntimeEvent.Priority.HIGH);
                case "FAILED" -> TaskTransition.of("TASK_FAILED", RuntimeEvent.Priority.CRITICAL);
                case "CANCELLED" -> TaskTransition.of("TASK_CANCELLED", RuntimeEvent.Priority.HIGH);
                default -> null;
            };
        }
        return switch (task.state()) {
            case CREATED, ACCEPTED -> TaskTransition.of("TASK_STARTED", RuntimeEvent.Priority.MEDIUM);
            case RUNNING -> TaskTransition.progress();
            case WAITING -> TaskTransition.of("TASK_WAITING", RuntimeEvent.Priority.MEDIUM);
            case PAUSED -> TaskTransition.of("TASK_PAUSED", RuntimeEvent.Priority.HIGH);
            case BLOCKED, RECONCILIATION_REQUIRED ->
                    TaskTransition.of("TASK_BLOCKED", RuntimeEvent.Priority.CRITICAL);
            case COMPLETED -> TaskTransition.of("TASK_COMPLETED", RuntimeEvent.Priority.HIGH);
            case FAILED -> TaskTransition.of("TASK_FAILED", RuntimeEvent.Priority.CRITICAL);
            case CANCELLED -> TaskTransition.of("TASK_CANCELLED", RuntimeEvent.Priority.HIGH);
        };
    }

    private static GraphTransition graphTransition(String transition) {
        return switch (transition == null ? "" : transition.toUpperCase(java.util.Locale.ROOT)) {
            case "STARTED" -> GraphTransition.of("TASK_GRAPH_STARTED", RuntimeEvent.Priority.MEDIUM);
            case "PROGRESS" -> GraphTransition.progress();
            case "CHECKPOINT" -> GraphTransition.of("TASK_GRAPH_CHECKPOINT", RuntimeEvent.Priority.MEDIUM);
            case "PAUSED" -> GraphTransition.of("TASK_GRAPH_PAUSED", RuntimeEvent.Priority.HIGH);
            case "RESUMED" -> GraphTransition.of("TASK_GRAPH_RESUMED", RuntimeEvent.Priority.MEDIUM);
            case "CANCELLED" -> GraphTransition.of("TASK_GRAPH_CANCELLED", RuntimeEvent.Priority.HIGH);
            case "SUCCEEDED" -> GraphTransition.of("TASK_GRAPH_TERMINAL", RuntimeEvent.Priority.HIGH);
            case "FAILED" -> GraphTransition.of("TASK_GRAPH_TERMINAL", RuntimeEvent.Priority.CRITICAL);
            default -> null;
        };
    }

    private static JsonNode boundedObservation(JsonNode value) {
        JsonNode result = value == null ? Json.object() : value.deepCopy();
        if (!result.isObject()) result = Json.object().set("value", result);
        String serialized = Json.write(result);
        if (serialized.length() <= 12_000) return result;
        return Json.object().put("truncated", true)
                .put("sha256", Digests.sha256(serialized))
                .put("originalCharacters", serialized.length());
    }

    private static JsonNode taskTarget(JsonNode taskPayload) {
        if (taskPayload == null || !taskPayload.isObject()) return Json.object();
        ObjectNode target = Json.object();
        if (taskPayload.path("target").isObject()) {
            taskPayload.path("target").fields().forEachRemaining(entry ->
                    target.set(entry.getKey(), entry.getValue().deepCopy()));
        }
        for (String field : List.of("dimension", "x", "y", "z", "targetId", "entityId",
                "partnerEntityId", "item", "itemId", "block", "blockId", "station",
                "vehicleType", "crop", "type", "slot", "hand")) {
            if (taskPayload.has(field) && !taskPayload.path(field).isContainerNode()) {
                target.set(field, taskPayload.path(field).deepCopy());
            }
        }
        return target;
    }

    private static Instant instant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String progressIdentity(JsonNode observation) {
        JsonNode progress = observation.path("progress");
        if (progress.isNumber() && Double.isFinite(progress.asDouble())) {
            int bucket = Math.max(0, Math.min(10, (int) Math.floor(progress.asDouble() * 10.0D)));
            return Integer.toString(bucket);
        }
        return Digests.sha256(Json.canonical(boundedObservation(observation))).substring(0, 16);
    }

    @FunctionalInterface public interface Dispatcher {
        DispatchResult dispatch(RuntimeEvent event) throws Exception;
    }

    public enum DispatchResult { DELIVERED, SUPPRESSED, DEFERRED }

    private record TaskTransition(String eventType, RuntimeEvent.Priority priority,
                                  boolean coalesce, RuntimeEvent.AdmissionPolicy policy) {
        static TaskTransition of(String type, RuntimeEvent.Priority priority) {
            return new TaskTransition(type, priority, false, RuntimeEvent.AdmissionPolicy.immediate());
        }

        static TaskTransition progress() {
            return new TaskTransition("TASK_PROGRESS", RuntimeEvent.Priority.LOW, true,
                    new RuntimeEvent.AdmissionPolicy(Duration.ofMillis(500), Duration.ofSeconds(5)));
        }
    }

    private record GraphTransition(String eventType, RuntimeEvent.Priority priority,
                                   boolean coalesce, RuntimeEvent.AdmissionPolicy policy) {
        static GraphTransition of(String type, RuntimeEvent.Priority priority) {
            return new GraphTransition(type, priority, false, RuntimeEvent.AdmissionPolicy.immediate());
        }

        static GraphTransition progress() {
            return new GraphTransition("TASK_GRAPH_PROGRESS", RuntimeEvent.Priority.LOW, true,
                    new RuntimeEvent.AdmissionPolicy(Duration.ofMillis(250), Duration.ofSeconds(1)));
        }
    }
}
