package com.mccompanion.runtime.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A bounded, source-observed event that may be delivered to the external Brain. */
public record RuntimeEvent(
        String eventId,
        Category category,
        String eventType,
        Priority priority,
        String source,
        String companionId,
        String taskId,
        String taskGraphExecutionId,
        JsonNode target,
        String dedupKey,
        String coalesceKey,
        String cooldownKey,
        Instant occurredAt,
        Instant observedAt,
        Instant expiresAt,
        JsonNode payload,
        int occurrenceCount) {

    private static final int MAX_JSON_CHARACTERS = 16_384;

    public RuntimeEvent {
        eventId = bounded(eventId, "eventId", 160);
        Objects.requireNonNull(category, "category");
        eventType = code(eventType, "eventType");
        Objects.requireNonNull(priority, "priority");
        source = code(source, "source");
        companionId = bounded(companionId, "companionId", 128);
        taskId = optional(taskId, "taskId", 128);
        taskGraphExecutionId = optional(taskGraphExecutionId, "taskGraphExecutionId", 128);
        if (taskId != null && taskGraphExecutionId != null) {
            throw new IllegalArgumentException("event cannot bind both task and task graph execution");
        }
        target = object(target, "target");
        dedupKey = bounded(dedupKey, "dedupKey", 256);
        coalesceKey = optional(coalesceKey, "coalesceKey", 256);
        cooldownKey = optional(cooldownKey, "cooldownKey", 256);
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (expiresAt.isBefore(occurredAt) || expiresAt.equals(occurredAt)) {
            throw new IllegalArgumentException("expiresAt must be after occurredAt");
        }
        payload = object(payload, "payload");
        if (occurrenceCount < 1) throw new IllegalArgumentException("occurrenceCount must be positive");
        if (Json.write(target).length() + Json.write(payload).length() > MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException("event JSON exceeds the bounded size");
        }
    }

    public RuntimeEvent(String eventId, Category category, String eventType, Priority priority,
                        String source, String companionId, String taskId,
                        String taskGraphExecutionId, JsonNode target, String dedupKey,
                        String coalesceKey, String cooldownKey, Instant occurredAt,
                        Instant observedAt, Instant expiresAt, JsonNode payload) {
        this(eventId, category, eventType, priority, source, companionId, taskId,
                taskGraphExecutionId, target, dedupKey, coalesceKey, cooldownKey,
                occurredAt, observedAt, expiresAt, payload, 1);
    }

    @Override public JsonNode target() { return target.deepCopy(); }

    @Override public JsonNode payload() { return payload.deepCopy(); }

    public boolean taskBound() { return taskId != null || taskGraphExecutionId != null; }

    public enum Category { PLAYER_ENTITY, SURVIVAL, TASK, INVENTORY, WORLD }

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL;

        public boolean higherThan(Priority other) { return ordinal() > other.ordinal(); }
    }

    public record AdmissionPolicy(Duration debounce, Duration cooldown) {
        public AdmissionPolicy {
            debounce = Objects.requireNonNull(debounce, "debounce");
            cooldown = Objects.requireNonNull(cooldown, "cooldown");
            if (debounce.isNegative() || debounce.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("debounce must be 0..30 seconds");
            }
            if (cooldown.isNegative() || cooldown.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("cooldown must be 0..1 hour");
            }
        }

        public static AdmissionPolicy immediate() {
            return new AdmissionPolicy(Duration.ZERO, Duration.ZERO);
        }
    }

    private static JsonNode object(JsonNode value, String field) {
        JsonNode bounded = value == null ? Json.object() : value.deepCopy();
        if (!bounded.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return bounded;
    }

    private static String code(String value, String field) {
        String result = bounded(value, field, 64);
        if (!result.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(field + " must be an uppercase code");
        }
        return result;
    }

    private static String bounded(String value, String field, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String result = value.strip();
        if (result.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return result;
    }

    private static String optional(String value, String field, int maximum) {
        return value == null || value.isBlank() ? null : bounded(value, field, maximum);
    }
}
