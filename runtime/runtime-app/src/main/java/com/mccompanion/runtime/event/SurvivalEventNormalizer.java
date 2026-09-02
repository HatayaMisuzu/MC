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

/** Validates Body-authored survival/lifecycle edges and maps them to the durable event envelope. */
public final class SurvivalEventNormalizer {
    private static final Set<String> TASK_RELEVANT = Set.of(
            "DAMAGE", "LOW_HEALTH", "FIRE", "LAVA", "LOW_AIR", "FALL_DANGER");
    private static final Set<String> RECOVERY = Set.of(
            "HEALTH_RECOVERED", "FIRE_CLEARED", "LAVA_CLEARED", "AIR_RECOVERED",
            "FALL_DANGER_CLEARED", "RESPAWN");
    private static final Map<RuntimeEvent.Priority, Duration> TTL = ttl();
    private final Clock clock;

    public SurvivalEventNormalizer() {
        this(Clock.systemUTC());
    }

    SurvivalEventNormalizer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Normalized normalize(JsonNode payload, Optional<TaskRecord> activeTask) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("survival event payload must be an object");
        }
        String companionId = required(payload, "companionId", 128);
        String bodyEventId = required(payload, "eventId", 160);
        String eventType = required(payload, "eventType", 64).toUpperCase(Locale.ROOT);
        RuntimeEvent.Priority priority = priority(eventType);
        String behaviorId = optional(payload.path("behaviorId").asText(null), 128);
        String taskId = activeTask.filter(task -> TASK_RELEVANT.contains(eventType))
                .filter(task -> behaviorId != null && behaviorId.equals(task.behaviorId()))
                .map(TaskRecord::taskId).orElse(null);

        Instant now = clock.instant();
        Instant occurredAt = instant(payload.path("occurredAt").asText(null), now);
        if (occurredAt.isAfter(now.plusSeconds(30))) {
            throw new IllegalArgumentException("event occurredAt is in the future");
        }
        JsonNode bodyVitals = payload.path("vitals");
        if (!bodyVitals.isObject()) throw new IllegalArgumentException("event vitals must be an object");
        String lifecycle = required(bodyVitals, "lifecycle", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DEAD", "SLEEPING").contains(lifecycle)) {
            throw new IllegalArgumentException("unsupported lifecycle state");
        }
        if (eventType.equals("DEATH") && !lifecycle.equals("DEAD")) {
            throw new IllegalArgumentException("death event requires DEAD lifecycle");
        }
        if (eventType.equals("RESPAWN") && !lifecycle.equals("ACTIVE")) {
            throw new IllegalArgumentException("respawn event requires ACTIVE lifecycle");
        }
        if (!eventType.equals("DEATH") && !lifecycle.equals("ACTIVE")) {
            throw new IllegalArgumentException("non-death survival event requires ACTIVE lifecycle");
        }

        double health = finite(bodyVitals, "health", 0.0D, 1_024.0D);
        double maxHealth = finite(bodyVitals, "maxHealth", 0.0D, 1_024.0D);
        if (health > maxHealth) throw new IllegalArgumentException("health exceeds maxHealth");
        int air = integer(bodyVitals, "air", 0, 100_000);
        int maxAir = integer(bodyVitals, "maxAir", 0, 100_000);
        if (air > maxAir) throw new IllegalArgumentException("air exceeds maxAir");
        double fallDistance = finite(bodyVitals, "fallDistance", 0.0D, 1_000_000.0D);
        double previousHealth = finite(payload, "previousHealth", 0.0D, 1_024.0D);
        double damageAmount = finite(payload, "damageAmount", 0.0D, 1_024.0D);

        ObjectNode vitals = Json.object().put("lifecycle", lifecycle)
                .put("health", health).put("maxHealth", maxHealth)
                .put("air", air).put("maxAir", maxAir)
                .put("onFire", bodyVitals.path("onFire").asBoolean(false))
                .put("inLava", bodyVitals.path("inLava").asBoolean(false))
                .put("onGround", bodyVitals.path("onGround").asBoolean(false))
                .put("fallDistance", fallDistance);
        ObjectNode eventPayload = Json.object().put("bodyEventId", bodyEventId)
                .put("behaviorId", behaviorId == null ? "" : behaviorId)
                .put("tick", Math.max(0L, payload.path("tick").asLong(0L)))
                .put("eventType", eventType).put("previousHealth", previousHealth)
                .put("damageAmount", damageAmount);
        eventPayload.set("vitals", vitals.deepCopy());

        eventPayload.put("localSafetyHandling", payload.path("localSafetyHandling").asBoolean(false));

        String digest = Digests.sha256(bodyEventId);
        String coalesceKey = eventType.equals("DAMAGE") ? "survival-damage:" + companionId : null;
        String cooldownKey = switch (eventType) {
            case "LOW_HEALTH", "FIRE", "LAVA", "LOW_AIR", "FALL_DANGER" ->
                    "survival-edge:" + companionId + ':' + eventType;
            default -> null;
        };
        RuntimeEvent event = new RuntimeEvent("survival-event-" + digest.substring(0, 32),
                RuntimeEvent.Category.SURVIVAL, eventType, priority,
                "MINECRAFT_SURVIVAL_OBSERVER", companionId, taskId, null,
                Json.object().put("companionId", companionId).set("vitals", vitals.deepCopy()),
                "survival:" + digest, coalesceKey, cooldownKey,
                occurredAt, now, occurredAt.plus(TTL.get(priority)), eventPayload);
        RuntimeEvent.AdmissionPolicy policy;
        if (eventType.equals("DAMAGE")) {
            policy = new RuntimeEvent.AdmissionPolicy(Duration.ofMillis(500), Duration.ZERO);
        } else if (cooldownKey != null) {
            policy = new RuntimeEvent.AdmissionPolicy(Duration.ZERO, Duration.ofSeconds(5));
        } else if (RECOVERY.contains(eventType)) {
            policy = new RuntimeEvent.AdmissionPolicy(Duration.ofMillis(250), Duration.ZERO);
        } else {
            policy = RuntimeEvent.AdmissionPolicy.immediate();
        }
        return new Normalized(event, policy, eventType.equals("DEATH"));
    }

    private static RuntimeEvent.Priority priority(String type) {
        return switch (type) {
            case "DAMAGE", "RESPAWN" -> RuntimeEvent.Priority.HIGH;
            case "HEALTH_RECOVERED", "FIRE_CLEARED", "LAVA_CLEARED", "AIR_RECOVERED",
                    "FALL_DANGER_CLEARED" -> RuntimeEvent.Priority.MEDIUM;
            case "LOW_HEALTH", "FIRE", "LAVA", "LOW_AIR", "FALL_DANGER", "DEATH" ->
                    RuntimeEvent.Priority.CRITICAL;
            default -> throw new IllegalArgumentException("unsupported survival event type");
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

    private static double finite(JsonNode node, String field, double minimum, double maximum) {
        double value = node.path(field).asDouble(Double.NaN);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be finite and bounded");
        }
        return value;
    }

    private static int integer(JsonNode node, String field, int minimum, int maximum) {
        if (!node.path(field).canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        int value = node.path(field).asInt();
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " is out of bounds");
        return value;
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

    public record Normalized(RuntimeEvent event, RuntimeEvent.AdmissionPolicy policy,
                             boolean invalidatePreDeath) { }
}
