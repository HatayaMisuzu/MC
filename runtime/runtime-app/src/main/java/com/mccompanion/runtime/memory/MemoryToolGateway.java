package com.mccompanion.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Read-only typed memory access plus quarantined suggestions from an external Brain. */
public final class MemoryToolGateway implements ToolGateway {
    private final MemoryRepository memories;
    public MemoryToolGateway(MemoryRepository memories) { this.memories = java.util.Objects.requireNonNull(memories); }

    @Override public List<ToolDefinition> definitions(ToolContext context) {
        return List.of(definition("world.locate_known_container",
                        "List only body-verified known containers, with dimension compatibility", containerSchema()),
                definition("memory.list", "List one typed memory category with provenance", listSchema()),
                definition("memory.search", "Search bounded memory keys and values", searchSchema()),
                definition("memory.suggest", "Quarantine an unverified typed memory suggestion for review",
                        suggestionSchema()),
                definition("memory.suggest_preference",
                        "Compatibility wrapper that quarantines an unverified preference suggestion",
                        preferenceSchema()));
    }

    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            return switch (call.name()) {
                case "world.locate_known_container" -> locateContainers(context, call);
                case "memory.list" -> list(context, call);
                case "memory.search" -> search(context, call);
                case "memory.suggest" -> suggest(context, call, false);
                case "memory.suggest_preference" -> suggest(context, call, true);
                default -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Memory tool is unavailable");
            };
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (java.sql.SQLException failure) {
            return ToolResult.rejected(call, "PERSISTENCE_ERROR", "Memory store is unavailable");
        }
    }

    private ToolResult locateContainers(ToolContext context, ToolCall call) throws java.sql.SQLException {
        rejectUnexpected(call.arguments(), Set.of("dimension", "limit"));
        String dimension = call.arguments().path("dimension").asText("").strip();
        if (!dimension.isEmpty() && !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("dimension must be a namespaced id");
        }
        int limit = call.arguments().path("limit").asInt(20);
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be 1..20");
        var candidates = Json.MAPPER.createArrayNode();
        for (MemoryFact fact : memories.relevant(context.companionId(), MemoryKind.WORLD, 100)) {
            if (!fact.verified() || !fact.key().startsWith("container:")) continue;
            JsonNode value = fact.value();
            String candidateDimension = value.path("dimension").asText("");
            if (candidateDimension.isBlank() || !value.path("x").canConvertToInt()
                    || !value.path("y").canConvertToInt() || !value.path("z").canConvertToInt()) continue;
            ObjectNode candidate = candidates.addObject().put("memoryId", fact.memoryId())
                    .put("dimension", candidateDimension).put("x", value.path("x").asInt())
                    .put("y", value.path("y").asInt()).put("z", value.path("z").asInt())
                    .put("sameDimension", dimension.isEmpty() || dimension.equals(candidateDimension))
                    .put("verified", true).put("source", fact.source())
                    .put("verifiedAt", fact.updatedAt().toString());
            if (value.hasNonNull("type")) candidate.set("type", value.path("type"));
            if (candidates.size() >= limit) break;
        }
        ObjectNode observation = Json.object().put("requestedDimension", dimension)
                .put("count", candidates.size());
        observation.set("containers", candidates);
        return ok(call, observation);
    }

    private ToolResult list(ToolContext context, ToolCall call) throws java.sql.SQLException {
        rejectUnexpected(call.arguments(), Set.of("kind", "limit"));
        MemoryKind kind = kind(call.arguments().path("kind").asText(""));
        int limit = boundedLimit(call.arguments().path("limit").asInt(25));
        return ok(call, Json.MAPPER.valueToTree(memories.list(context.companionId(), kind, limit)));
    }

    private ToolResult search(ToolContext context, ToolCall call) throws java.sql.SQLException {
        rejectUnexpected(call.arguments(), Set.of("kind", "query", "limit"));
        String query = text(call.arguments(), "query", 1, 128);
        MemoryKind filter = call.arguments().has("kind")
                ? kind(call.arguments().path("kind").asText("")) : null;
        return ok(call, Json.MAPPER.valueToTree(memories.search(context.companionId(), filter, query,
                boundedLimit(call.arguments().path("limit").asInt(25)))));
    }

    private ToolResult suggest(ToolContext context, ToolCall call, boolean preferenceOnly)
            throws java.sql.SQLException {
        rejectUnexpected(call.arguments(), preferenceOnly
                ? Set.of("key", "value", "confidence", "ttlSeconds")
                : Set.of("kind", "key", "value", "confidence", "ttlSeconds"));
        MemoryKind kind = preferenceOnly ? MemoryKind.PREFERENCE
                : kind(call.arguments().path("kind").asText(""));
        if (kind == MemoryKind.WORKING) throw new IllegalArgumentException("WORKING suggestions are not supported");
        String key = text(call.arguments(), "key", 1, 128);
        JsonNode value = call.arguments().path("value");
        if (value.isMissingNode() || Json.write(value).length() > 4096) throw new IllegalArgumentException("value is required and bounded");
        rejectSensitive(key + ' ' + Json.write(value));
        double confidence = call.arguments().path("confidence").asDouble(0.5);
        if (confidence < 0 || confidence > 0.9 || Double.isNaN(confidence)) throw new IllegalArgumentException("confidence must be 0..0.9");
        long ttlSeconds = call.arguments().path("ttlSeconds").asLong(2_592_000L);
        if (ttlSeconds < 60 || ttlSeconds > 31_536_000L) throw new IllegalArgumentException("ttlSeconds must be 60..31536000");
        MemorySuggestion suggestion = memories.suggest(context.companionId(), kind, key, value,
                confidence, Duration.ofSeconds(ttlSeconds), "EXTERNAL_BRAIN_SUGGESTION",
                context.brainSessionId());
        return new ToolResult(call.callId(), call.name(), true, "MEMORY_SUGGESTION_QUARANTINED",
                Json.MAPPER.valueToTree(suggestion), true);
    }

    private static ToolResult ok(ToolCall call, JsonNode value) {
        return new ToolResult(call.callId(), call.name(), true, "OK", value, true);
    }

    private static ToolDefinition definition(String name, String description, ObjectNode properties) {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties);
        if (name.equals("memory.list")) schema.putArray("required").add("kind");
        if (name.equals("memory.search")) schema.putArray("required").add("query");
        if (name.equals("memory.suggest")) schema.putArray("required").add("kind").add("key").add("value");
        if (name.equals("memory.suggest_preference")) schema.putArray("required").add("key").add("value");
        return new ToolDefinition(name, "1.0", description, schema, "LOW", "MEMORY",
                Duration.ofSeconds(5), name.equals("memory.list") || name.equals("memory.search"));
    }

    private static ObjectNode listSchema() {
        ObjectNode p = Json.object();
        p.putObject("kind").put("type", "string").putArray("enum")
                .add("WORKING").add("EPISODIC").add("WORLD").add("PREFERENCE");
        p.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 100);
        return p;
    }
    private static ObjectNode containerSchema() {
        ObjectNode p = Json.object();
        p.putObject("dimension").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        p.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 20);
        return p;
    }
    private static ObjectNode searchSchema() {
        ObjectNode p = Json.object();
        p.putObject("kind").put("type", "string").putArray("enum")
                .add("WORKING").add("EPISODIC").add("WORLD").add("PREFERENCE");
        p.putObject("query").put("type", "string").put("maxLength", 128);
        p.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 100); return p;
    }
    private static ObjectNode preferenceSchema() {
        ObjectNode p = Json.object(); p.putObject("key").put("type", "string").put("maxLength", 128);
        p.putObject("value"); p.putObject("confidence").put("type", "number").put("minimum", 0).put("maximum", 0.9);
        p.putObject("ttlSeconds").put("type", "integer").put("minimum", 60).put("maximum", 31_536_000); return p;
    }
    private static ObjectNode suggestionSchema() {
        ObjectNode p = preferenceSchema();
        p.putObject("kind").put("type", "string").putArray("enum")
                .add("EPISODIC").add("WORLD").add("PREFERENCE");
        return p;
    }
    private static MemoryKind kind(String value) {
        try { return MemoryKind.valueOf(value); } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("kind is invalid"); }
    }
    private static int boundedLimit(int value) { if (value < 1 || value > 100) throw new IllegalArgumentException("limit must be 1..100"); return value; }
    private static String text(JsonNode node, String field, int min, int max) {
        String value = node.path(field).asText("").strip();
        if (value.length() < min || value.length() > max) throw new IllegalArgumentException(field + " is invalid"); return value;
    }
    private static void rejectUnexpected(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new IllegalArgumentException("arguments must be an object");
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException("unexpected argument: " + field); });
    }
    private static void rejectSensitive(String value) {
        if (value.matches("(?is).*(sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._-]{12,}|[a-z]:\\\\|/(users|home)/).*")) {
            throw new IllegalArgumentException("sensitive values cannot be stored");
        }
    }
}
