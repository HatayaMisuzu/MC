package com.mccompanion.runtime.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;
import com.mccompanion.runtime.task.TaskRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validates Body inventory/world edges and binds them only to the matching active task. */
public final class InventoryWorldEventNormalizer {
    private static final Set<String> INVENTORY_TYPES = Set.of(
            "INVENTORY_NEAR_FULL", "INVENTORY_FULL", "KEY_ITEM_ACQUIRED",
            "KEY_ITEM_INSUFFICIENT", "RESOURCE_TARGET_REACHED");
    private static final Set<String> WORLD_TYPES = Set.of(
            "DIMENSION_CHANGED", "DAY_NIGHT_CHANGED", "WEATHER_CHANGED",
            "TASK_TARGET_CHANGED", "TARGET_CONTAINER_CHANGED", "TARGET_BLOCK_CHANGED");
    private static final Set<String> COALESCED = Set.of(
            "DAY_NIGHT_CHANGED", "WEATHER_CHANGED", "TASK_TARGET_CHANGED",
            "TARGET_CONTAINER_CHANGED", "TARGET_BLOCK_CHANGED");
    private final Clock clock;

    public InventoryWorldEventNormalizer() {
        this(Clock.systemUTC());
    }

    InventoryWorldEventNormalizer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Normalized normalize(JsonNode body, Optional<TaskRecord> activeTask) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("inventory/world event payload must be an object");
        }
        String companionId = required(body, "companionId", 128);
        String bodyEventId = required(body, "eventId", 160);
        String eventType = required(body, "eventType", 64).toUpperCase(Locale.ROOT);
        RuntimeEvent.Category category = category(eventType);
        String suppliedCategory = required(body, "category", 16).toUpperCase(Locale.ROOT);
        if (!category.name().equals(suppliedCategory)) {
            throw new IllegalArgumentException("event category does not match event type");
        }
        String behaviorId = optional(body.path("behaviorId").asText(null), 128);
        String taskId = activeTask
                .filter(task -> behaviorId != null && behaviorId.equals(task.behaviorId()))
                .map(TaskRecord::taskId).orElse(null);
        Instant now = clock.instant();
        Instant occurredAt = instant(body.path("occurredAt").asText(null), now);
        if (occurredAt.isAfter(now.plusSeconds(30))) {
            throw new IllegalArgumentException("event occurredAt is in the future");
        }

        ObjectNode inventory = inventory(body.path("inventory"));
        ObjectNode world = world(body.path("world"));
        ObjectNode observedTarget = target(body.path("target"));
        ObjectNode target = Json.object();
        if (category == RuntimeEvent.Category.INVENTORY) {
            target.put("freeSlots", inventory.path("freeSlots").asInt())
                    .put("slots", inventory.path("slots").asInt());
            if (inventory.path("goal").isObject()) {
                target.set("goal", inventory.path("goal").deepCopy());
            }
        } else {
            target.set("world", world.deepCopy());
            if (!observedTarget.isEmpty()) target.set("target", observedTarget.deepCopy());
        }

        ObjectNode payload = Json.object().put("bodyEventId", bodyEventId)
                .put("behaviorId", behaviorId == null ? "" : behaviorId)
                .put("tick", Math.max(0L, body.path("tick").asLong(0L)))
                .put("eventType", eventType)
                .put("previousValue", bounded(body.path("previousValue").asText(""), 2_048))
                .put("currentValue", bounded(body.path("currentValue").asText(""), 2_048));
        payload.set("inventory", inventory);
        payload.set("world", world);
        if (!observedTarget.isEmpty()) payload.set("target", observedTarget);

        String digest = Digests.sha256(bodyEventId);
        String stateKey = COALESCED.contains(eventType)
                ? "inventory-world-state:" + companionId + ':' + eventType : null;
        String cooldownKey = switch (eventType) {
            case "INVENTORY_NEAR_FULL", "INVENTORY_FULL", "KEY_ITEM_INSUFFICIENT" ->
                    "inventory-world-edge:" + companionId + ':' + eventType;
            case "DAY_NIGHT_CHANGED", "WEATHER_CHANGED" ->
                    "inventory-world-cycle:" + companionId + ':' + eventType;
            default -> null;
        };
        RuntimeEvent.Priority priority = priority(eventType);
        Duration ttl = priority == RuntimeEvent.Priority.CRITICAL
                ? Duration.ofMinutes(2) : Duration.ofMinutes(5);
        RuntimeEvent event = new RuntimeEvent("inventory-world-event-" + digest.substring(0, 32),
                category, eventType, priority, "MINECRAFT_INVENTORY_WORLD_OBSERVER",
                companionId, taskId, null, target, "inventory-world:" + digest,
                stateKey, cooldownKey, occurredAt, now, occurredAt.plus(ttl), payload);
        RuntimeEvent.AdmissionPolicy policy;
        if (COALESCED.contains(eventType)) {
            policy = new RuntimeEvent.AdmissionPolicy(Duration.ofMillis(250),
                    cooldownKey == null ? Duration.ZERO : Duration.ofSeconds(10));
        } else if (cooldownKey != null) {
            policy = new RuntimeEvent.AdmissionPolicy(Duration.ZERO, Duration.ofSeconds(5));
        } else {
            policy = RuntimeEvent.AdmissionPolicy.immediate();
        }
        return new Normalized(event, policy);
    }

    private static RuntimeEvent.Category category(String type) {
        if (INVENTORY_TYPES.contains(type)) return RuntimeEvent.Category.INVENTORY;
        if (WORLD_TYPES.contains(type)) return RuntimeEvent.Category.WORLD;
        throw new IllegalArgumentException("unsupported inventory/world event type");
    }

    private static RuntimeEvent.Priority priority(String type) {
        return switch (type) {
            case "INVENTORY_FULL" -> RuntimeEvent.Priority.CRITICAL;
            case "DAY_NIGHT_CHANGED", "WEATHER_CHANGED" -> RuntimeEvent.Priority.MEDIUM;
            case "INVENTORY_NEAR_FULL", "KEY_ITEM_ACQUIRED", "KEY_ITEM_INSUFFICIENT",
                    "RESOURCE_TARGET_REACHED", "DIMENSION_CHANGED", "TASK_TARGET_CHANGED",
                    "TARGET_CONTAINER_CHANGED", "TARGET_BLOCK_CHANGED" -> RuntimeEvent.Priority.HIGH;
            default -> throw new IllegalArgumentException("unsupported inventory/world event type");
        };
    }

    private static ObjectNode inventory(JsonNode value) {
        if (!value.isObject()) throw new IllegalArgumentException("inventory must be an object");
        int slots = integer(value, "slots", 1, 256);
        int freeSlots = integer(value, "freeSlots", 0, slots);
        JsonNode bodyCounts = value.path("counts");
        if (!bodyCounts.isObject() || bodyCounts.size() > 256) {
            throw new IllegalArgumentException("inventory counts must be a bounded object");
        }
        ObjectNode result = Json.object().put("slots", slots).put("freeSlots", freeSlots);
        ObjectNode counts = result.putObject("counts");
        bodyCounts.fields().forEachRemaining(entry -> {
            String itemId = bounded(entry.getKey(), 256);
            if (itemId.isBlank() || !entry.getValue().canConvertToInt()) {
                throw new IllegalArgumentException("inventory item count is invalid");
            }
            int count = entry.getValue().asInt();
            if (count < 1 || count > 1_000_000) {
                throw new IllegalArgumentException("inventory item count is out of bounds");
            }
            counts.put(itemId, count);
        });
        if (value.path("goal").isObject()) {
            JsonNode goal = value.path("goal");
            result.set("goal", Json.object()
                    .put("itemId", required(goal, "itemId", 256))
                    .put("requiredCount", integer(goal, "requiredCount", 1, 1_000_000))
                    .put("currentCount", integer(goal, "currentCount", 0, 1_000_000)));
        }
        return result;
    }

    private static ObjectNode world(JsonNode value) {
        if (!value.isObject()) throw new IllegalArgumentException("world must be an object");
        String time = required(value, "timeOfDay", 16).toUpperCase(Locale.ROOT);
        String weather = required(value, "weather", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("DAY", "NIGHT").contains(time)
                || !Set.of("CLEAR", "RAIN", "THUNDER").contains(weather)) {
            throw new IllegalArgumentException("world cycle state is invalid");
        }
        return Json.object().put("dimension", required(value, "dimension", 256))
                .put("timeOfDay", time).put("weather", weather);
    }

    private static ObjectNode target(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return Json.object();
        if (!value.isObject()) throw new IllegalArgumentException("target must be an object");
        String kind = required(value, "kind", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("BLOCK", "CONTAINER").contains(kind)) {
            throw new IllegalArgumentException("target kind is invalid");
        }
        return Json.object().put("identity", required(value, "identity", 512))
                .put("kind", kind).put("present", value.path("present").asBoolean(false))
                .put("blockId", bounded(value.path("blockId").asText(""), 256))
                .put("blockFingerprint", bounded(value.path("blockFingerprint").asText(""), 2_048))
                .put("containerType", bounded(value.path("containerType").asText(""), 256))
                .put("containerFingerprint", bounded(
                        value.path("containerFingerprint").asText(""), 2_048));
    }

    private static int integer(JsonNode node, String field, int minimum, int maximum) {
        if (!node.path(field).canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int value = node.path(field).asInt();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is out of bounds");
        }
        return value;
    }

    private static Instant instant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException("occurredAt must be ISO-8601", invalid);
        }
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
        String result = value.strip();
        if (result.length() > maximum) throw new IllegalArgumentException("identity is too long");
        return result;
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        String result = value.strip();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }

    public record Normalized(RuntimeEvent event, RuntimeEvent.AdmissionPolicy policy) { }
}
