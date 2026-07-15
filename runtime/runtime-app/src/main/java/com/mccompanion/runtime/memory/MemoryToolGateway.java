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

/** Read-only typed memory access plus unverified preference suggestions from an external Brain. */
public final class MemoryToolGateway implements ToolGateway {
    private final MemoryRepository memories;
    public MemoryToolGateway(MemoryRepository memories) { this.memories = java.util.Objects.requireNonNull(memories); }

    @Override public List<ToolDefinition> definitions(ToolContext context) {
        return List.of(definition("memory.list", "List one typed memory category with provenance", listSchema()),
                definition("memory.search", "Search bounded memory keys and values", searchSchema()),
                definition("memory.suggest_preference", "Store an unverified preference suggestion for user review", preferenceSchema()));
    }

    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            return switch (call.name()) {
                case "memory.list" -> list(context, call);
                case "memory.search" -> search(context, call);
                case "memory.suggest_preference" -> suggest(context, call);
                default -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Memory tool is unavailable");
            };
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (java.sql.SQLException failure) {
            return ToolResult.rejected(call, "PERSISTENCE_ERROR", "Memory store is unavailable");
        }
    }

    private ToolResult list(ToolContext context, ToolCall call) throws java.sql.SQLException {
        rejectUnexpected(call.arguments(), Set.of("kind", "limit"));
        MemoryKind kind = kind(call.arguments().path("kind").asText(""));
        int limit = boundedLimit(call.arguments().path("limit").asInt(25));
        return ok(call, Json.MAPPER.valueToTree(memories.list(context.companionId(), kind, limit)));
    }

    private ToolResult search(ToolContext context, ToolCall call) throws java.sql.SQLException {
        rejectUnexpected(call.arguments(), Set.of("query", "limit"));
        String query = text(call.arguments(), "query", 1, 128);
        return ok(call, Json.MAPPER.valueToTree(memories.search(context.companionId(), query,
                boundedLimit(call.arguments().path("limit").asInt(25)))));
    }

    private ToolResult suggest(ToolContext context, ToolCall call) throws java.sql.SQLException {
        rejectUnexpected(call.arguments(), Set.of("key", "value", "confidence", "ttlSeconds"));
        String key = text(call.arguments(), "key", 1, 128);
        JsonNode value = call.arguments().path("value");
        if (value.isMissingNode() || Json.write(value).length() > 4096) throw new IllegalArgumentException("value is required and bounded");
        rejectSensitive(Json.write(value));
        double confidence = call.arguments().path("confidence").asDouble(0.5);
        if (confidence < 0 || confidence > 0.9 || Double.isNaN(confidence)) throw new IllegalArgumentException("confidence must be 0..0.9");
        long ttlSeconds = call.arguments().path("ttlSeconds").asLong(2_592_000L);
        if (ttlSeconds < 60 || ttlSeconds > 31_536_000L) throw new IllegalArgumentException("ttlSeconds must be 60..31536000");
        MemoryFact fact = memories.remember(context.companionId(), MemoryKind.PREFERENCE, key, value,
                false, confidence, Duration.ofSeconds(ttlSeconds), "EXTERNAL_BRAIN_SUGGESTION");
        return ok(call, Json.MAPPER.valueToTree(fact));
    }

    private static ToolResult ok(ToolCall call, JsonNode value) {
        return new ToolResult(call.callId(), call.name(), true, "OK", value, true);
    }

    private static ToolDefinition definition(String name, String description, ObjectNode properties) {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties);
        if (name.equals("memory.list")) schema.putArray("required").add("kind");
        if (name.equals("memory.search")) schema.putArray("required").add("query");
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
    private static ObjectNode searchSchema() {
        ObjectNode p = Json.object(); p.putObject("query").put("type", "string").put("maxLength", 128);
        p.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 100); return p;
    }
    private static ObjectNode preferenceSchema() {
        ObjectNode p = Json.object(); p.putObject("key").put("type", "string").put("maxLength", 128);
        p.putObject("value"); p.putObject("confidence").put("type", "number").put("minimum", 0).put("maximum", 0.9);
        p.putObject("ttlSeconds").put("type", "integer").put("minimum", 60).put("maximum", 31_536_000); return p;
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
