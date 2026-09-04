package com.mccompanion.runtime.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;
import com.mccompanion.runtime.task.TaskRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validates Body-authored player/entity edges and maps them to the durable 3A envelope. */
public final class PlayerEntityEventNormalizer {
    private static final Set<String> TASK_RELEVANT = Set.of(
            "FOLLOW_TARGET_LOST", "CURRENT_TARGET_LOST", "TARGET_REAPPEARED",
            "CURRENT_TARGET_DIED", "CURRENT_TARGET_DISAPPEARED",
            "HOSTILE_ENTERED_THREAT_RANGE");
    private static final Map<RuntimeEvent.Priority, Duration> TTL = ttl();
    private final Clock clock;

    public PlayerEntityEventNormalizer() {
        this(Clock.systemUTC());
    }

    PlayerEntityEventNormalizer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Normalized normalize(JsonNode payload, Optional<TaskRecord> activeTask) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("player/entity event payload must be an object");
        }
        String companionId = required(payload, "companionId", 128);
        String bodyEventId = required(payload, "eventId", 160);
        String eventType = required(payload, "eventType", 64).toUpperCase(Locale.ROOT);
        RuntimeEvent.Priority priority = priority(eventType);
        JsonNode bodyTarget = payload.path("target");
        if (!bodyTarget.isObject()) throw new IllegalArgumentException("event target must be an object");
        String entityId = required(bodyTarget, "entityId", 128);
        String entityType = required(bodyTarget, "entityType", 160);
        Instant now = clock.instant();
        Instant occurredAt = instant(payload.path("occurredAt").asText(null), now);
        if (occurredAt.isAfter(now.plusSeconds(30))) {
            throw new IllegalArgumentException("event occurredAt is in the future");
        }

        String behaviorId = optional(payload.path("behaviorId").asText(null), 128);
        String taskId = activeTask.filter(task -> TASK_RELEVANT.contains(eventType))
                .filter(task -> behaviorId != null && behaviorId.equals(task.behaviorId()))
                .map(TaskRecord::taskId).orElse(null);
        ObjectNode target = Json.object().put("entityId", entityId).put("entityType", entityType)
                .put("displayName", bounded(bodyTarget.path("displayName").asText(""), 256))
                .put("player", bodyTarget.path("player").asBoolean(false))
                .put("hostile", bodyTarget.path("hostile").asBoolean(false))
                .put("alive", bodyTarget.path("alive").asBoolean(false));
        double distance = bodyTarget.path("distanceSquared").asDouble(0.0D);
        if (!Double.isFinite(distance) || distance < 0.0D) {
            throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
        }
        target.put("distanceSquared", distance);
        ObjectNode eventPayload = Json.object().put("bodyEventId", bodyEventId)
                .put("behaviorId", behaviorId == null ? "" : behaviorId)
                .put("tick", Math.max(0L, payload.path("tick").asLong(0L)))
                .put("eventType", eventType);
        eventPayload.set("target", target.deepCopy());

        eventPayload.put("localSafetyHandling", payload.path("localSafetyHandling").asBoolean(false));

        String digest = Digests.sha256(bodyEventId);
        String presenceKey = eventType.startsWith("PLAYER_")
                ? "player-presence:" + companionId + ':' + entityId : null;
        String cooldownKey = eventType.equals("HOSTILE_ENTERED_THREAT_RANGE")
                ? "hostile-threat:" + companionId + ':' + entityId : null;
        RuntimeEvent event = new RuntimeEvent("player-entity-event-" + digest.substring(0, 32),
                RuntimeEvent.Category.PLAYER_ENTITY, eventType, priority,
                "MINECRAFT_ENTITY_OBSERVER", companionId, taskId, null, target,
                "player-entity:" + digest, presenceKey, cooldownKey,
                occurredAt, now, occurredAt.plus(TTL.get(priority)), eventPayload);
        RuntimeEvent.AdmissionPolicy policy = eventType.startsWith("PLAYER_")
                ? new RuntimeEvent.AdmissionPolicy(Duration.ofMillis(500), Duration.ZERO)
                : eventType.equals("HOSTILE_ENTERED_THREAT_RANGE")
                ? new RuntimeEvent.AdmissionPolicy(Duration.ZERO, Duration.ofSeconds(10))
                : RuntimeEvent.AdmissionPolicy.immediate();
        return new Normalized(event, policy);
    }

    private static RuntimeEvent.Priority priority(String type) {
        return switch (type) {
            case "PLAYER_ENTERED_RANGE", "PLAYER_LEFT_RANGE" -> RuntimeEvent.Priority.MEDIUM;
            case "FOLLOW_TARGET_LOST", "CURRENT_TARGET_LOST", "TARGET_REAPPEARED",
                    "CURRENT_TARGET_DISAPPEARED" -> RuntimeEvent.Priority.HIGH;
            case "HOSTILE_ENTERED_THREAT_RANGE", "CURRENT_TARGET_DIED" -> RuntimeEvent.Priority.CRITICAL;
            default -> throw new IllegalArgumentException("unsupported player/entity event type");
        };
    }

    private static Map<RuntimeEvent.Priority, Duration> ttl() {
        Map<RuntimeEvent.Priority, Duration> values = new EnumMap<>(RuntimeEvent.Priority.class);
        values.put(RuntimeEvent.Priority.LOW, Duration.ofMinutes(2));
        values.put(RuntimeEvent.Priority.MEDIUM, Duration.ofMinutes(2));
        values.put(RuntimeEvent.Priority.HIGH, Duration.ofMinutes(5));
        values.put(RuntimeEvent.Priority.CRITICAL, Duration.ofMinutes(2));
        return Map.copyOf(values);
    }

    private static Instant instant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("occurredAt must be ISO-8601", invalid); }
    }

    private static String required(JsonNode node, String field, int maximum) {
        String value = node.path(field).asText("").strip();
        if (value.isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is required and bounded");
        }
        return value;
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String bounded = value.strip();
        if (bounded.length() > maximum) throw new IllegalArgumentException("optional identity is too long");
        return bounded;
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        String result = value.strip();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }

    public record Normalized(RuntimeEvent event, RuntimeEvent.AdmissionPolicy policy) { }
}
