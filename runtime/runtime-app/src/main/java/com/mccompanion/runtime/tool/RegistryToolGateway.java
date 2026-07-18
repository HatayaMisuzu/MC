package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.protocol.CommandType;
import com.mccompanion.runtime.command.ProtocolCommandSender;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.RuntimeSession;
import com.mccompanion.runtime.session.SessionRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded live server Registry, recipe, and spatial observations supplied by the authenticated Mod session. */
public final class RegistryToolGateway implements ToolGateway, AutoCloseable {
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RESULT_CHARS = 262_144;
    private final SessionRegistry sessions;
    private final ProtocolCommandSender sender;
    private final ConcurrentHashMap<String, Pending> pendingByQuery = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> queryByCall = new ConcurrentHashMap<>();

    public RegistryToolGateway(SessionRegistry sessions, ProtocolCommandSender sender) {
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
    }

    @Override
    public List<ToolDefinition> definitions(ToolContext context) {
        RuntimeSession session = sessions.forCompanion(context.companionId()).orElse(null);
        if (session == null) return List.of();
        boolean registry = session.handshake().capabilities().path("registry_query").asBoolean(false);
        boolean recipes = session.handshake().capabilities().path("recipe_query").asBoolean(false);
        boolean observations = session.handshake().capabilities()
                .path("primitive_observation_query").asBoolean(false);
        java.util.ArrayList<ToolDefinition> values = new java.util.ArrayList<>();
        if (registry) {
            values.add(new ToolDefinition("registry.search", "1.0",
                    "Search the connected server Registry with bounded namespace-aware filters",
                    registrySearchSchema(), "LOW", "READ_WORLD", QUERY_TIMEOUT, true));
            values.add(new ToolDefinition("registry.describe", "1.0",
                    "Describe one exact connected-server Registry identifier",
                    registryDescribeSchema(), "LOW", "READ_WORLD", QUERY_TIMEOUT, true));
        }
        if (recipes) {
            values.add(new ToolDefinition("recipe.query", "1.0",
                    "Query bounded live crafting or smelting recipes from the connected server",
                    recipeQuerySchema(), "LOW", "READ_WORLD", QUERY_TIMEOUT, true));
        }
        if (observations) {
            values.add(new ToolDefinition("block.inspect", "1.0",
                    "Inspect one visible loaded block near the connected body",
                    blockInspectSchema(), "LOW", "READ_WORLD", QUERY_TIMEOUT, true));
            values.add(new ToolDefinition("item.inspect", "1.0",
                    "Inspect one namespaced item in the connected body inventory",
                    itemInspectSchema(), "LOW", "INVENTORY", QUERY_TIMEOUT, true));
            values.add(new ToolDefinition("entity.inspect", "1.0",
                    "Inspect bounded visible entities near the connected body",
                    entityInspectSchema(), "LOW", "READ_WORLD", QUERY_TIMEOUT, true));
            values.add(new ToolDefinition("menu.inspect", "1.0",
                    "Inspect the exact open menu and issue a short-lived session capability",
                    objectSchema(), "LOW", "INVENTORY", QUERY_TIMEOUT, true));
        }
        return List.copyOf(values);
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        if (definitions(context).stream().noneMatch(value -> value.name().equals(call.name()))) {
            return ToolResult.rejected(call, "TOOL_UNAVAILABLE",
                    "Connected Mod session does not advertise this live query");
        }
        final ObjectNode arguments;
        final CommandType command;
        try {
            arguments = switch (call.name()) {
                case "registry.search" -> validatedRegistrySearch(call.arguments());
                case "registry.describe" -> validatedRegistryDescribe(call.arguments());
                case "recipe.query" -> validatedRecipeQuery(call.arguments());
                case "block.inspect" -> validatedBlockInspect(call.arguments());
                case "item.inspect" -> validatedItemInspect(call.arguments());
                case "entity.inspect" -> validatedEntityInspect(call.arguments());
                case "menu.inspect" -> validatedEmpty(call.arguments());
                default -> throw new IllegalArgumentException("Unsupported Registry tool");
            };
            command = call.name().startsWith("registry.") ? CommandType.QUERY_REGISTRY
                    : call.name().equals("recipe.query") ? CommandType.QUERY_RECIPE
                    : CommandType.QUERY_OBSERVATION;
        } catch (IllegalArgumentException invalid) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", invalid.getMessage());
        }
        RuntimeSession session = sessions.forCompanion(context.companionId()).orElse(null);
        if (session == null) return ToolResult.rejected(call, "COMPANION_OFFLINE", "Companion Mod session is offline");
        String callKey = callKey(context, call.callId());
        String queryId = UUID.randomUUID().toString();
        if (queryByCall.putIfAbsent(callKey, queryId) != null) {
            return ToolResult.rejected(call, "TOOL_CALL_IN_PROGRESS", "The query call is already active");
        }
        Pending pending = new Pending(context, call, session.sessionId(), new CompletableFuture<>());
        pendingByQuery.put(queryId, pending);
        arguments.put("queryId", queryId).put("tool", call.name());
        try {
            sender.send(session, "query-" + queryId, command, context.companionId(),
                    null, null, 0, 0, arguments);
            return new ToolResult(call.callId(), call.name(), true, "QUERY_DISPATCHED",
                    Json.object().put("state", "QUERYING").put("queryId", queryId)
                            .put("source", "CONNECTED_SERVER_REGISTRY"), false);
        } catch (RuntimeException dispatchFailure) {
            pendingByQuery.remove(queryId, pending);
            queryByCall.remove(callKey, queryId);
            return ToolResult.rejected(call, "RUNTIME_OFFLINE", "Live server query could not be dispatched");
        }
    }

    @Override
    public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted, Duration timeout,
                                    java.util.function.Consumer<ToolResult> progress) {
        if (accepted.terminal() || !accepted.success()) return accepted;
        String callKey = callKey(context, call.callId());
        String queryId = queryByCall.get(callKey);
        Pending pending = queryId == null ? null : pendingByQuery.get(queryId);
        if (pending == null) {
            return ToolResult.rejected(call, "QUERY_BINDING_MISSING", "Live query binding is unavailable");
        }
        Duration bounded = timeout.compareTo(QUERY_TIMEOUT) > 0 ? QUERY_TIMEOUT : timeout;
        try {
            return pending.result().get(Math.max(1L, bounded.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            return ToolResult.rejected(call, "QUERY_TIMEOUT", "Connected server did not answer the bounded query");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.rejected(call, "QUERY_INTERRUPTED", "Live query wait was interrupted");
        } catch (ExecutionException failure) {
            return ToolResult.rejected(call, "QUERY_FAILED", "Live query result could not be read");
        } finally {
            pendingByQuery.remove(queryId, pending);
            queryByCall.remove(callKey, queryId);
        }
    }

    /** Completes one authenticated Mod result; returns false for an unknown or already-finished query. */
    public boolean complete(RuntimeSession session, JsonNode payload) {
        if (session == null || payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("query result must be an object from an authenticated session");
        }
        String queryId = payload.path("queryId").asText("");
        Pending pending = pendingByQuery.get(queryId);
        if (pending == null) return false;
        if (!pending.runtimeSessionId().equals(session.sessionId())) {
            throw new IllegalArgumentException("query result belongs to another Runtime session");
        }
        if (!pending.context().companionId().equals(payload.path("companionId").asText(""))) {
            throw new IllegalArgumentException("query result companion binding mismatch");
        }
        boolean success = payload.path("success").asBoolean(false);
        String code = payload.path("code").asText(success ? "OK" : "QUERY_FAILED");
        JsonNode observation = payload.path("observation");
        if (!observation.isObject() || Json.write(observation).length() > MAX_RESULT_CHARS) {
            pending.result().complete(ToolResult.rejected(pending.call(), "INVALID_QUERY_RESULT",
                    "Connected server returned an invalid or oversized observation"));
            return true;
        }
        ToolResult result = new ToolResult(pending.call().callId(), pending.call().name(), success, code,
                observation, true);
        pending.result().complete(result);
        return true;
    }

    @Override
    public void cancel(ToolContext context, String callId, String reason) {
        String callKey = callKey(context, callId);
        String queryId = queryByCall.get(callKey);
        Pending pending = queryId == null ? null : pendingByQuery.get(queryId);
        if (pending != null) {
            pending.result().complete(ToolResult.rejected(pending.call(), "QUERY_CANCELLED",
                    reason == null || reason.isBlank() ? "Live query was cancelled" : reason));
        }
    }

    @Override
    public void close() {
        pendingByQuery.forEach((queryId, pending) -> pending.result().complete(
                ToolResult.rejected(pending.call(), "QUERY_GATEWAY_CLOSED", "Registry query gateway stopped")));
        pendingByQuery.clear();
        queryByCall.clear();
    }

    private static ObjectNode validatedRegistrySearch(JsonNode input) {
        rejectUnexpected(input, Set.of("kind", "query", "namespace", "limit"));
        ObjectNode values = (ObjectNode) input.deepCopy();
        values.put("kind", registryKind(input.path("kind").asText("")));
        optionalQuery(input, values);
        if (input.has("namespace")) {
            String namespace = input.path("namespace").asText("");
            if (!namespace.matches("[a-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("namespace must be a valid Registry namespace");
            }
            values.put("namespace", namespace);
        }
        values.put("limit", boundedLimit(input, 64));
        return values;
    }

    private static ObjectNode validatedRegistryDescribe(JsonNode input) {
        rejectUnexpected(input, Set.of("kind", "id"));
        String id = input.path("id").asText("");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("id must be a namespaced Registry identifier");
        }
        return Json.object().put("kind", registryKind(input.path("kind").asText(""))).put("id", id);
    }

    private static ObjectNode validatedRecipeQuery(JsonNode input) {
        rejectUnexpected(input, Set.of("type", "query", "output", "limit"));
        String type = input.path("type").asText("ANY").toUpperCase(Locale.ROOT);
        if (!Set.of("ANY", "CRAFTING", "SMELTING").contains(type)) {
            throw new IllegalArgumentException("type must be ANY, CRAFTING, or SMELTING");
        }
        ObjectNode values = Json.object().put("type", type).put("limit", boundedLimit(input, 32));
        optionalQuery(input, values);
        if (input.has("output")) {
            String output = input.path("output").asText("");
            if (!output.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("output must be a namespaced item identifier");
            }
            values.put("output", output);
        }
        if (!values.has("query")) throw new IllegalArgumentException("recipe query requires query");
        return values;
    }

    private static ObjectNode validatedBlockInspect(JsonNode input) {
        rejectUnexpected(input, Set.of("position"));
        return Json.object().set("position", validatedPosition(input.path("position"), "position"));
    }

    private static ObjectNode validatedItemInspect(JsonNode input) {
        rejectUnexpected(input, Set.of("item"));
        return Json.object().put("item", namespacedId(input.path("item").asText(""), "item"));
    }

    private static ObjectNode validatedEntityInspect(JsonNode input) {
        rejectUnexpected(input, Set.of("radius", "type", "entityId", "limit"));
        ObjectNode values = Json.object();
        if (!input.path("radius").isNumber()) throw new IllegalArgumentException("radius must be a number");
        double radius = input.path("radius").asDouble();
        if (!Double.isFinite(radius) || radius < 1.0D || radius > 16.0D) {
            throw new IllegalArgumentException("radius must be 1..16");
        }
        values.put("radius", radius).put("limit", boundedLimit(input, 32));
        if (input.has("type")) values.put("type", namespacedId(input.path("type").asText(""), "type"));
        if (input.has("entityId")) {
            String entityId = input.path("entityId").asText("");
            try {
                values.put("entityId", UUID.fromString(entityId).toString());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("entityId must be a UUID");
            }
        }
        return values;
    }

    private static ObjectNode validatedEmpty(JsonNode input) {
        rejectUnexpected(input, Set.of());
        return Json.object();
    }

    private static ObjectNode validatedPosition(JsonNode input, String label) {
        if (!input.isObject()) throw new IllegalArgumentException(label + " must be an object");
        rejectUnexpected(input, Set.of("dimension", "x", "y", "z"));
        ObjectNode values = Json.object();
        for (String field : List.of("x", "y", "z")) {
            if (!input.path(field).isIntegralNumber() || !input.path(field).canConvertToInt()) {
                throw new IllegalArgumentException(label + "." + field + " must be an integer");
            }
            values.put(field, input.path(field).asInt());
        }
        if (input.has("dimension")) {
            values.put("dimension", namespacedId(input.path("dimension").asText(""), label + ".dimension"));
        }
        return values;
    }

    private static String namespacedId(String value, String label) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(label + " must be a namespaced identifier");
        }
        return value;
    }

    private static int boundedLimit(JsonNode input, int maximum) {
        if (!input.has("limit")) return maximum;
        if (!input.path("limit").isIntegralNumber() || !input.path("limit").canConvertToInt()) {
            throw new IllegalArgumentException("limit must be an integer");
        }
        int limit = input.path("limit").asInt();
        if (limit < 1 || limit > maximum) throw new IllegalArgumentException("limit must be 1.." + maximum);
        return limit;
    }

    private static void optionalQuery(JsonNode input, ObjectNode values) {
        if (!input.has("query")) return;
        String query = input.path("query").asText("").strip().toLowerCase(Locale.ROOT);
        if (query.isEmpty() || query.length() > 64) throw new IllegalArgumentException("query must be 1..64 characters");
        values.put("query", query);
    }

    private static String registryKind(String kind) {
        String normalized = kind.toUpperCase(Locale.ROOT);
        if (!Set.of("ITEM", "BLOCK", "ENTITY", "DIMENSION", "MENU").contains(normalized)) {
            throw new IllegalArgumentException("kind must be ITEM, BLOCK, ENTITY, DIMENSION, or MENU");
        }
        return normalized;
    }

    private static void rejectUnexpected(JsonNode input, Set<String> allowed) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("arguments must be an object");
        input.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("unexpected argument: " + field);
        });
    }

    private static ObjectNode registrySearchSchema() {
        ObjectNode root = objectSchema();
        ObjectNode properties = (ObjectNode) root.path("properties");
        enumProperty(properties, "kind", "ITEM", "BLOCK", "ENTITY", "DIMENSION", "MENU");
        properties.putObject("query").put("type", "string").put("minLength", 1).put("maxLength", 64);
        properties.putObject("namespace").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]{1,64}$");
        properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 64);
        root.putArray("required").add("kind");
        return root;
    }

    private static ObjectNode registryDescribeSchema() {
        ObjectNode root = objectSchema();
        ObjectNode properties = (ObjectNode) root.path("properties");
        enumProperty(properties, "kind", "ITEM", "BLOCK", "ENTITY", "DIMENSION", "MENU");
        properties.putObject("id").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        root.putArray("required").add("kind").add("id");
        return root;
    }

    private static ObjectNode recipeQuerySchema() {
        ObjectNode root = objectSchema();
        ObjectNode properties = (ObjectNode) root.path("properties");
        enumProperty(properties, "type", "ANY", "CRAFTING", "SMELTING");
        properties.putObject("query").put("type", "string").put("minLength", 1).put("maxLength", 64);
        properties.putObject("output").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 32);
        root.putArray("required").add("query");
        return root;
    }

    private static ObjectNode blockInspectSchema() {
        ObjectNode root = objectSchema();
        root.withObject("/properties").set("position", positionSchema());
        root.putArray("required").add("position");
        return root;
    }

    private static ObjectNode itemInspectSchema() {
        ObjectNode root = objectSchema();
        root.withObject("/properties").putObject("item").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        root.putArray("required").add("item");
        return root;
    }

    private static ObjectNode entityInspectSchema() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.withObject("/properties");
        properties.putObject("radius").put("type", "number").put("minimum", 1).put("maximum", 16);
        properties.putObject("type").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        properties.putObject("entityId").put("type", "string")
                .put("pattern", "^[0-9a-fA-F-]{36}$");
        properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 32);
        root.putArray("required").add("radius");
        return root;
    }

    private static ObjectNode positionSchema() {
        ObjectNode root = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        properties.putObject("dimension").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        for (String field : List.of("x", "y", "z")) properties.putObject(field).put("type", "integer");
        root.putArray("required").add("x").add("y").add("z");
        return root;
    }

    private static ObjectNode objectSchema() {
        ObjectNode root = Json.object().put("type", "object").put("additionalProperties", false);
        root.set("properties", Json.object());
        return root;
    }

    private static void enumProperty(ObjectNode properties, String name, String... values) {
        var valuesNode = properties.putObject(name).put("type", "string").putArray("enum");
        for (String value : values) valuesNode.add(value);
    }

    private static String callKey(ToolContext context, String callId) {
        return context.brainSessionId() + '\u0000' + context.companionId() + '\u0000' + callId;
    }

    private record Pending(ToolContext context, ToolCall call, String runtimeSessionId,
                           CompletableFuture<ToolResult> result) { }
}
