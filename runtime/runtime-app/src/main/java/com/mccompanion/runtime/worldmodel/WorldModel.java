package com.mccompanion.runtime.worldmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.event.RuntimeEvent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.memory.MemoryFact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Bounded projection of authenticated observations. Never plans or writes Minecraft state. */
public final class WorldModel {
    public static final String STORAGE_FIELD = "_worldModel";
    public static final int MAX_CURRENT = 16, MAX_NEARBY = 48, MAX_MEMORY = 64;
    public static final int SUMMARY_CHARS = 12_000, SUMMARY_ENTRIES = 32;
    private static final long CURRENT_TTL = 15_000, NEARBY_TTL = 10_000, MEMORY_TTL = 600_000;
    private static final List<String> CURRENT = List.of("position", "dimension", "vitals", "equipment",
            "inventory", "menu", "vehicle", "behavior", "navigation");
    private final ObjectNode data;

    public WorldModel(JsonNode saved) {
        data = saved != null && saved.isObject() && saved.path("version").asInt() == 1
                ? saved.deepCopy() : Json.object().put("version", 1);
        for (String group : List.of("current", "nearby", "memory")) {
            if (!data.path(group).isObject()) data.putObject(group);
        }
    }

    public ObjectNode saved() { return data.deepCopy(); }

    public void observe(String worldId, String sessionId, JsonNode status, Instant receivedAt) {
        String dimension = text(status.path("dimension"));
        Instant observed = instant(status.path("observedAt"));
        Instant previous = instant(data.path("observedAt"));
        boolean sameWorld = worldId.equals(data.path("worldId").asText());
        boolean sameSession = sessionId != null && sessionId.equals(data.path("sessionId").asText());
        if (sameWorld && sameSession && previous != null && observed != null && !observed.isAfter(previous)) return;
        if (!sameWorld) {
            group("current").removeAll(); group("nearby").removeAll(); group("memory").removeAll();
        } else if (!dimension.equals(data.path("dimension").asText())) {
            invalidateAll("DIMENSION_CHANGED", receivedAt);
        } else if (!sameSession) invalidateAll("SESSION_CHANGED", receivedAt);
        data.put("worldId", worldId).put("sessionId", sessionId).put("dimension", dimension);
        // Missing/future timestamps and synthetic dormant-body coordinates are not observations.
        if (observed == null || observed.isAfter(receivedAt.plusSeconds(2)) || dimension.isBlank()
                || !status.path("runtimeConnected").asBoolean()
                || !"spawned".equalsIgnoreCase(status.path("bodyState").asText())) {
            invalidateAll("BODY_UNAVAILABLE", receivedAt);
            return;
        }
        data.put("observedAt", observed.toString());
        ObjectNode body = Json.object();
        for (String kind : CURRENT) if (status.has(kind)) body.set(kind, status.path(kind));
        ObjectNode behavior = body.putObject("behavior");
        for (String field : List.of("behaviorId", "behaviorState", "behaviorRevision", "controlEpoch", "bodyState")) {
            if (status.has(field)) behavior.set(field, status.path(field));
        }
        behavior.put("behaviorState", status.path("behaviorState").asText("idle"));
        for (String kind : CURRENT) {
            if (body.has(kind)) put("current", kind, kind, dimension, body.path(kind), observed,
                    CURRENT_TTL, "CONNECTED_BODY_OBSERVATION", true);
            else invalidate(group("current").path(kind), "NOT_OBSERVED", observed);
        }
        // Each nearby list is a bounded sample. Omission means not currently observed, not death.
        group("nearby").elements().forEachRemaining(entry -> invalidate(entry, "NOT_OBSERVED", observed.minusNanos(1)));
        JsonNode nearby = status.path("localWorld");
        observeList(nearby.path("entities"), "entity", dimension, observed);
        observeList(status.path("observedContainers"), "container", dimension, observed);
        observeList(nearby.path("targets"), "target", dimension, observed);
        observeList(nearby.path("resources"), "resource", dimension, observed);
        group("nearby").fields().forEachRemaining(field -> {
            JsonNode entry = field.getValue();
            if (entry.path("invalidation").asText().equals("NOT_OBSERVED")) {
                invalidate(group("memory").path(field.getKey()), "NOT_OBSERVED", observed);
            }
        });
        JsonNode path = body.path("navigation");
        if (path.path("active").asBoolean()) put("memory", "path", "recentPath", dimension,
                path, observed, 120_000, "NAVIGATION_RUNTIME", false);
        trim(observed);
    }

    private void observeList(JsonNode values, String category, String dimension, Instant observed) {
        if (!values.isArray()) return;
        int count = 0;
        for (JsonNode value : values) {
            if (++count > 32) break;
            String dim = value.path("dimension").asText(dimension);
            if (!dim.equals(dimension)) continue;
            String identity = identity(value, dim);
            if (identity.isBlank()) continue;
            if (value.has("verified") && !value.path("verified").asBoolean()) {
                invalidateIdentity(identity, "TARGET_UNAVAILABLE", observed);
                continue;
            }
            String kind = category;
            if (category.equals("entity")) {
                kind = value.path("player").asBoolean() ? "player"
                        : value.has("item") ? "droppedItem" : value.path("hostile").asBoolean() ? "threat" : "entity";
                if (value.has("alive") && !value.path("alive").asBoolean()) {
                    invalidateIdentity(identity, "ENTITY_DEAD", observed); continue;
                }
            }
            String key = category + ':' + identity;
            if (value.has("container") && !value.path("container").asBoolean()) {
                for (String name : List.of("nearby", "memory")) group(name).forEach(entry -> {
                    if (entry.path("identity").asText().equals(identity)
                            && Set.of("container", "knownContainer").contains(entry.path("kind").asText()))
                        invalidate(entry, "BLOCK_REPLACED", observed);
                });
            }
            put("nearby", key, kind, dim, value, observed, NEARBY_TTL, "CONNECTED_BODY_OBSERVATION", true);
            String memoryKind = switch (kind) {
                case "player" -> "recentPlayer";
                case "container" -> "knownContainer";
                case "target" -> "recentTarget";
                case "resource" -> "taskResource";
                default -> "";
            };
            if (!memoryKind.isBlank()) put("memory", key, memoryKind, dim, value, observed,
                    MEMORY_TTL, "CONNECTED_BODY_OBSERVATION", true);
        }
    }

    public void event(RuntimeEvent event) {
        Instant at = event.occurredAt();
        String type = event.eventType();
        if (type.equals("TASK_TARGET_CHANGED")) {
            for (String group : List.of("nearby", "memory")) group(group).forEach(entry -> {
                if (Set.of("target", "recentTarget", "taskResource").contains(entry.path("kind").asText()))
                    invalidate(entry, type, at);
            });
        }
        if (type.equals("DIMENSION_CHANGED") || type.equals("DEATH")) {
            invalidateAll(type, at); return;
        }
        if (Set.of("CURRENT_TARGET_DIED", "CURRENT_TARGET_DISAPPEARED", "CURRENT_TARGET_LOST",
                "FOLLOW_TARGET_LOST", "PLAYER_LEFT_RANGE", "TARGET_BLOCK_CHANGED", "TARGET_CONTAINER_CHANGED",
                "TASK_TARGET_CHANGED").contains(type)) {
            JsonNode target = event.payload().path("target");
            String identity = target.path("identity").asText(target.path("entityId").asText(""));
            if (!identity.isBlank()) invalidateIdentity(identity, type, at);
        }
    }

    public void observeQuery(String tool, JsonNode observation, Instant now) {
        Instant at = instant(observation.path("observedAt"));
        String dimension = observation.path("dimension").asText("");
        if (!observation.path("verified").asBoolean() || at == null || at.isAfter(now.plusSeconds(2))
                || now.toEpochMilli() - at.toEpochMilli() >= NEARBY_TTL
                || !dimension.equals(data.path("dimension").asText())) return;
        switch (tool) {
            case "entity.inspect" -> observeList(observation.path("entities"), "entity", dimension, at);
            case "block.inspect" -> observeList(Json.MAPPER.createArrayNode().add(observation), "target", dimension, at);
            case "menu.inspect" -> put("current", "menu", "menu", dimension, observation, at,
                    CURRENT_TTL, "CONNECTED_BODY_OBSERVATION", true);
            default -> { }
        }
        trim(now);
    }

    public void runtime(String kind, JsonNode value, JsonNode target, Instant at) {
        String dimension = data.path("dimension").asText("");
        put("current", kind, kind, dimension, value, at, CURRENT_TTL, "TASK_RUNTIME", true);
        if (target != null && target.isObject() && !identity(target, dimension).isBlank()) {
            put("memory", kind + ':' + identity(target, dimension), "taskLocation",
                    target.path("dimension").asText(dimension), target, at, MEMORY_TTL, "TASK_RUNTIME_INTENT", false);
        }
        trim(at);
    }

    public void invalidateAll(String reason, Instant at) {
        for (String name : List.of("current", "nearby", "memory")) {
            group(name).elements().forEachRemaining(entry -> invalidate(entry, reason, at));
        }
    }

    private void invalidateIdentity(String identity, String reason, Instant at) {
        for (String name : List.of("nearby", "memory")) {
            group(name).elements().forEachRemaining(entry -> {
                if (identity.equals(entry.path("identity").asText())) invalidate(entry, reason, at);
            });
        }
    }

    private static void invalidate(JsonNode entry, String reason, Instant at) {
        Instant observed = instant(entry.path("observedAt"));
        if (!(entry instanceof ObjectNode object) || observed == null || observed.isAfter(at)) return;
        if (reason.equals("NOT_OBSERVED") && !entry.path("invalidation").asText().isBlank()) return;
        object.put("invalidation", reason).put("invalidatedAt", at.toString()).put("stale", true).put("verified", false);
    }

    private void put(String group, String key, String kind, String dimension, JsonNode value,
                     Instant at, long ttl, String source, boolean verified) {
        if (key.length() > 300 || dimension.length() > 256) return;
        Instant invalidated = instant(group(group).path(key).path("invalidatedAt"));
        Instant prior = instant(group(group).path(key).path("observedAt"));
        if ((invalidated != null && !at.isAfter(invalidated)) || (prior != null && at.isBefore(prior))) return;
        JsonNode bounded = boundedValue(value, kind.equals("inventory") ? 4096 : 2048);
        String identity = identity(value, dimension);
        ObjectNode entry = Json.object().put("identity", identity.isBlank() ? key : identity)
                .put("kind", kind).put("source", source).put("observedAt", at.toString())
                .put("dimension", dimension).put("verified", verified && !truncated(bounded))
                .put("ttlMillis", ttl).put("stale", false).put("invalidation", "");
        entry.set("value", bounded);
        group(group).set(key, entry);
    }

    /** Summary never claims historical location/intent to be a currently verified world fact. */
    public ObjectNode summary(Instant now, List<MemoryFact> landmarks) {
        ObjectNode out = Json.object().put("source", "WORLD_MODEL").put("worldId", data.path("worldId").asText())
                .put("dimension", data.path("dimension").asText()).put("generatedAt", now.toString())
                .put("maxEntries", SUMMARY_ENTRIES).put("maxChars", SUMMARY_CHARS);
        ArrayNode current = out.putArray("current"), nearby = out.putArray("nearby"), memory = out.putArray("memory");
        int emitted = 0, eligible = 0;
        // User declarations are kept in their existing store and removed immediately when forgotten.
        for (MemoryFact fact : landmarks) {
            if (!explicitLandmark(fact) || memory.size() >= 8) continue;
            if (fact.expiresAt() != null && !fact.expiresAt().isAfter(now)) continue;
            if (!data.path("worldId").asText().equals(fact.value().path("worldId").asText())) continue;
            ObjectNode entry = Json.object().put("identity", fact.key()).put("kind", "landmark")
                    .put("source", fact.source()).put("observedAt", fact.updatedAt().toString())
                    .put("dimension", fact.value().path("dimension").asText(""))
                    .put("verified", false).put("userDeclared", true).put("stale", false).put("current", false)
                    .put("invalidation", "").put("ttlMillis", fact.expiresAt() == null ? -1
                            : fact.expiresAt().toEpochMilli() - fact.updatedAt().toEpochMilli());
            entry.set("value", boundedValue(fact.value(), 512));
            eligible++;
            if (Json.write(memory).length() + Json.write(entry).length() > 1800) continue;
            memory.add(entry); emitted++;
        }
        for (String name : List.of("current", "nearby", "memory")) {
            List<JsonNode> entries = new ArrayList<>();
            group(name).forEach(entries::add);
            entries.sort(Comparator.<JsonNode, Boolean>comparing(e -> stale(e, now))
                    .thenComparing(e -> e.path("observedAt").asText(), Comparator.reverseOrder()));
            ArrayNode destination = name.equals("current") ? current : name.equals("nearby") ? nearby : memory;
            int allowance = name.equals("current") ? 5800 : name.equals("nearby") ? 3300 : 2400;
            for (JsonNode entry : entries) {
                eligible++;
                ObjectNode candidate = project(entry, now);
                candidate.put("current", !name.equals("memory") && !candidate.path("stale").asBoolean());
                if (emitted >= SUMMARY_ENTRIES || Json.write(destination).length() + Json.write(candidate).length() > allowance) continue;
                destination.add(candidate);
                if (Json.write(out).length() > SUMMARY_CHARS - 100) destination.remove(destination.size() - 1);
                else emitted++;
            }
        }
        out.put("emittedEntries", emitted).put("omittedEntries", eligible - emitted);
        return out;
    }

    public JsonNode current(String kind, Instant now) { return project(group("current").path(kind), now); }

    public static boolean explicitLandmark(MemoryFact fact) {
        return fact.verified() && (fact.source().equals("USER") || fact.source().equals("USER_EDIT") || fact.source().equals("USER_EXPLICIT")
                || fact.source().startsWith("USER_APPROVED_"))
                && !fact.value().path("dimension").asText("").isBlank()
                && !identity(fact.value(), fact.value().path("dimension").asText()).isBlank();
    }

    private static ObjectNode project(JsonNode entry, Instant now) {
        if (!entry.isObject()) return Json.object();
        ObjectNode result = entry.deepCopy();
        boolean stale = stale(entry, now);
        result.put("stale", stale).put("verified", !stale && entry.path("verified").asBoolean());
        if (stale && result.path("invalidation").asText().isBlank()) result.put("invalidation", "TTL_EXPIRED");
        result.put("ageMillis", Math.max(0, now.toEpochMilli() - instant(entry.path("observedAt")).toEpochMilli()));
        // Nested input provenance cannot override this entry's freshness.
        if (result.path("value") instanceof ObjectNode value) {
            value.remove(List.of("verified", "stale", "source", "observedAt"));
        }
        return result;
    }

    private static boolean stale(JsonNode entry, Instant now) {
        Instant at = instant(entry.path("observedAt"));
        return at == null || at.isAfter(now) || !entry.path("invalidation").asText("").isBlank()
                || now.toEpochMilli() - at.toEpochMilli() >= entry.path("ttlMillis").asLong(0);
    }

    private void trim(Instant now) {
        for (String name : List.of("current", "nearby", "memory")) {
            ObjectNode entries = group(name);
            int limit = name.equals("current") ? MAX_CURRENT : name.equals("nearby") ? MAX_NEARBY : MAX_MEMORY;
            List<String> keys = new ArrayList<>(); entries.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.<String, Boolean>comparing(k -> !stale(entries.path(k), now))
                    .thenComparing(k -> entries.path(k).path("observedAt").asText()));
            for (String key : keys) { if (entries.size() <= limit) break; entries.remove(key); }
        }
    }

    private ObjectNode group(String name) { return (ObjectNode) data.path(name); }

    public static String identity(JsonNode value, String dimension) {
        String id = value.path("entityId").asText("");
        if (!id.isBlank()) {
            if (id.length() != 36) return "";
            try { return java.util.UUID.fromString(id).toString(); } catch (IllegalArgumentException ignored) { return ""; }
        }
        JsonNode pos = value.has("position") ? value.path("position") : value;
        for (String axis : List.of("x", "y", "z")) if (!pos.path(axis).isNumber()
                || !Double.isFinite(pos.path(axis).asDouble())) return "";
        return dimension + ':' + pos.path("x").asInt() + ',' + pos.path("y").asInt() + ',' + pos.path("z").asInt();
    }

    private static String text(JsonNode value) { String s = value.asText(""); return s.length() <= 256 ? s : ""; }
    private static Instant instant(JsonNode value) {
        try { return Instant.parse(value.asText("")); } catch (RuntimeException ignored) { return null; }
    }

    private static JsonNode boundedValue(JsonNode input, int budget) {
        int[] remaining = {128, 0};
        JsonNode value = copy(input, 0, remaining);
        if (remaining[1] != 0) {
            if (value instanceof ObjectNode object) object.put("_truncated", true);
            else value = Json.object().put("_truncated", true).set("value", value);
        }
        if (Json.write(value).length() <= budget) return value;
        if (value instanceof ObjectNode object) {
            var keys = new ArrayList<String>(); object.fieldNames().forEachRemaining(keys::add);
            object.put("_truncated", true);
            for (int i = keys.size() - 1; i >= 0 && Json.write(object).length() > budget; i--)
                if (!keys.get(i).equals("_truncated")) object.remove(keys.get(i));
            return object;
        }
        return Json.object().put("_truncated", true);
    }

    private static JsonNode copy(JsonNode value, int depth, int[] remaining) {
        if (depth > 5 || --remaining[0] < 0) return Json.object().put("_truncated", true);
        if (value.isTextual()) {
            if (value.asText().length() > 256) remaining[1] = 1;
            return Json.MAPPER.getNodeFactory().textNode(value.asText().substring(0, Math.min(256, value.asText().length())));
        }
        if (value.isObject()) {
            ObjectNode result = Json.object(); var fields = value.fields();
            while (fields.hasNext() && remaining[0] > 0) {
                var field = fields.next();
                if (field.getKey().length() <= 128) result.set(field.getKey(), copy(field.getValue(), depth + 1, remaining));
                else remaining[1] = 1;
            }
            if (fields.hasNext()) result.put("_truncated", true);
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = Json.MAPPER.createArrayNode();
            for (int i = 0; i < Math.min(16, value.size()) && remaining[0] > 0; i++) result.add(copy(value.path(i), depth + 1, remaining));
            if (result.size() < value.size()) result.add(Json.object().put("_truncated", true));
            return result;
        }
        return value.deepCopy();
    }

    private static boolean truncated(JsonNode value) {
        if (value.path("_truncated").asBoolean()) return true;
        if (value.isContainerNode()) for (JsonNode child : value) if (truncated(child)) return true;
        return false;
    }
}
