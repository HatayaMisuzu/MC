package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned compatibility evidence for one durable Task Graph execution. */
final class TaskGraphCompatibility {
    static final int SCHEMA_VERSION = 1;
    private static final List<String> STABLE_BINDING_FIELDS = List.of("targetId", "worldId", "protocol");

    private TaskGraphCompatibility() { }

    static ObjectNode capture(JsonNode graph, Map<String, ToolDefinition> definitions, JsonNode binding) {
        ObjectNode result = Json.object().put("schemaVersion", SCHEMA_VERSION);
        result.set("binding", objectCopy(binding));
        ObjectNode tools = result.putObject("tools");
        for (String name : referencedTools(graph.path("root"), Set.of())) {
            ToolDefinition definition = definitions.get(name);
            if (definition != null) tools.set(name, contract(definition));
        }
        return result;
    }

    static boolean isCurrent(JsonNode value) {
        return value != null && value.isObject()
                && value.path("schemaVersion").asInt(-1) == SCHEMA_VERSION
                && value.path("tools").isObject() && value.path("binding").isObject();
    }

    static Assessment assessResume(JsonNode persisted, JsonNode graph, JsonNode completedNodes,
                                   Map<String, ToolDefinition> current, JsonNode currentBinding) {
        if (!isCurrent(persisted)) {
            return Assessment.rejected("TASK_GRAPH_COMPATIBILITY_CONTEXT_MISSING",
                    "execution predates versioned compatibility context");
        }
        Assessment binding = assessBinding(persisted.path("binding"), currentBinding, false);
        if (!binding.compatible()) return binding;
        Set<String> completed = new HashSet<>();
        if (completedNodes != null && completedNodes.isArray()) completedNodes.forEach(value -> completed.add(value.asText()));
        for (String name : referencedTools(graph.path("root"), completed)) {
            JsonNode expected = persisted.path("tools").path(name);
            if (!expected.isObject()) {
                return Assessment.rejected("TASK_GRAPH_TOOL_CONTRACT_MISSING",
                        "pending Tool has no persisted compatibility contract: " + name);
            }
            ToolDefinition actual = current.get(name);
            if (actual == null) {
                return Assessment.rejected("TASK_GRAPH_CAPABILITY_UNAVAILABLE",
                        "pending Tool is no longer available: " + name);
            }
            if (!matches(expected, actual)) {
                return Assessment.rejected("TASK_GRAPH_TOOL_CONTRACT_CHANGED",
                        "pending Tool contract changed: " + name);
            }
        }
        boolean refresh = bindingRefreshRequired(persisted.path("binding"), currentBinding);
        return Assessment.compatible(refresh);
    }

    static Assessment assessCall(JsonNode persisted, String toolName, ToolDefinition current,
                                 JsonNode currentBinding) {
        if (!isCurrent(persisted)) return Assessment.compatible(false);
        JsonNode expected = persisted.path("tools").path(toolName);
        if (!expected.isObject()) {
            return Assessment.rejected("TASK_GRAPH_TOOL_CONTRACT_MISSING",
                    "Tool has no persisted compatibility contract: " + toolName);
        }
        if (current == null) {
            return Assessment.rejected("TASK_GRAPH_CAPABILITY_UNAVAILABLE",
                    "Tool is no longer available: " + toolName);
        }
        if (!matches(expected, current)) {
            return Assessment.rejected("TASK_GRAPH_TOOL_CONTRACT_CHANGED",
                    "Tool contract changed: " + toolName);
        }
        return assessBinding(persisted.path("binding"), currentBinding, true);
    }

    static ObjectNode refreshBinding(JsonNode persisted, JsonNode binding) {
        ObjectNode copy = persisted.deepCopy();
        copy.set("binding", objectCopy(binding));
        return copy;
    }

    static Map<String, ToolDefinition> definitionsForValidation(
            Map<String, ToolDefinition> current, JsonNode compatibility) {
        if (!isCurrent(compatibility)) return current;
        Map<String, ToolDefinition> merged = new LinkedHashMap<>(current);
        compatibility.path("tools").fields().forEachRemaining(entry -> {
            if (merged.containsKey(entry.getKey()) || !entry.getValue().isObject()) return;
            JsonNode value = entry.getValue();
            long timeout = Math.max(1L, Math.min(300_000L, value.path("timeoutMillis").asLong(30_000L)));
            merged.put(entry.getKey(), new ToolDefinition(entry.getKey(), value.path("version").asText(),
                    "Persisted Task Graph compatibility contract", value.path("inputSchema"),
                    value.path("risk").asText(), value.path("permission").asText(),
                    Duration.ofMillis(timeout), value.path("idempotent").asBoolean()));
        });
        return Map.copyOf(merged);
    }

    private static ObjectNode contract(ToolDefinition definition) {
        ObjectNode value = Json.object().put("version", definition.version())
                .put("permission", definition.permission()).put("risk", definition.risk())
                .put("idempotent", definition.idempotent())
                .put("timeoutMillis", definition.timeout().toMillis());
        value.set("inputSchema", definition.inputSchema());
        return value;
    }

    private static boolean matches(JsonNode expected, ToolDefinition actual) {
        return expected.path("version").asText().equals(actual.version())
                && expected.path("permission").asText().equals(actual.permission())
                && expected.path("risk").asText().equals(actual.risk())
                && expected.path("idempotent").asBoolean() == actual.idempotent()
                && expected.path("inputSchema").equals(actual.inputSchema());
    }

    private static Assessment assessBinding(JsonNode expected, JsonNode actual, boolean strictSession) {
        JsonNode current = actual != null && actual.isObject() ? actual : Json.object();
        if (expected.path("connected").asBoolean(false) && !current.path("connected").asBoolean(false)) {
            return Assessment.rejected("TASK_GRAPH_BODY_OFFLINE", "bound Body session is not connected");
        }
        for (String field : STABLE_BINDING_FIELDS) {
            String required = expected.path(field).asText("");
            if (!required.isBlank() && !required.equals(current.path(field).asText(""))) {
                return Assessment.rejected("TASK_GRAPH_" + field.replace("Id", "").toUpperCase()
                                + "_CHANGED", "execution binding changed: " + field);
            }
        }
        if (strictSession) {
            String session = expected.path("sessionId").asText("");
            if (!session.isBlank() && !session.equals(current.path("sessionId").asText(""))) {
                return Assessment.rejected("TASK_GRAPH_SESSION_CHANGED",
                        "Body session changed; reconcile observations before continuing");
            }
        }
        return Assessment.compatible(false);
    }

    private static boolean bindingRefreshRequired(JsonNode expected, JsonNode actual) {
        JsonNode current = actual != null && actual.isObject() ? actual : Json.object();
        return !expected.equals(current);
    }

    private static Set<String> referencedTools(JsonNode node, Set<String> completed) {
        Set<String> tools = new java.util.LinkedHashSet<>();
        collectTools(node, completed, tools);
        return Set.copyOf(tools);
    }

    private static void collectTools(JsonNode node, Set<String> completed, Set<String> tools) {
        if (node == null || !node.isObject() || completed.contains(node.path("id").asText())) return;
        String tool = switch (node.path("type").asText()) {
            case "call_tool" -> node.path("tool").asText();
            case "read_memory" -> "memory.search";
            case "suggest_memory" -> "memory.suggest";
            default -> "";
        };
        if (!tool.isBlank() && !tool.startsWith("task_graph.") && !tool.startsWith("task.")) tools.add(tool);
        for (JsonNode child : children(node)) collectTools(child, completed, tools);
    }

    private static List<JsonNode> children(JsonNode node) {
        ArrayList<JsonNode> result = new ArrayList<>();
        switch (node.path("type").asText()) {
            case "sequence", "fallback", "parallel" -> node.path("nodes").forEach(result::add);
            case "retry" -> result.add(node.path("node"));
            case "if" -> {
                result.add(node.path("then"));
                if (node.has("else")) result.add(node.path("else"));
            }
            case "switch" -> {
                node.path("cases").forEach(value -> result.add(value.path("node")));
                if (node.has("default")) result.add(node.path("default"));
            }
            case "repeat", "while" -> result.add(node.path("body"));
            default -> { }
        }
        return result;
    }

    private static ObjectNode objectCopy(JsonNode value) {
        return value != null && value.isObject() ? value.deepCopy() : Json.object();
    }

    record Assessment(boolean compatible, boolean refreshRequired, String code, String message) {
        static Assessment compatible(boolean refreshRequired) {
            return new Assessment(true, refreshRequired, "OK", "compatible");
        }
        static Assessment rejected(String code, String message) {
            return new Assessment(false, false, code, message);
        }
    }
}
