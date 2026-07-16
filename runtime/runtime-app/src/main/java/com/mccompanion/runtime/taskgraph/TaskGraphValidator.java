package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static validator for externally planned graphs.
 * Task Graph Runtime = deterministic orchestration; External Brain = reasoning and planning.
 */
public final class TaskGraphValidator {
    public static final String VERSION = "mcac-task-graph/1";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern TOOL = Pattern.compile("[a-z][a-z0-9_.-]{2,63}");
    private static final Set<String> NODE_TYPES = Set.of("sequence", "call_tool", "if", "switch", "repeat",
            "while", "retry", "fallback", "parallel", "wait", "ask_user", "read_memory",
            "suggest_memory", "checkpoint", "emit_progress", "return", "fail");
    private static final Set<String> INPUT_TYPES = Set.of("string", "integer", "number", "boolean",
            "registry_item", "registry_block", "registry_entity", "position", "json");

    public TaskGraphValidationResult validate(JsonNode graph, Set<String> availableTools) {
        List<TaskGraphValidationIssue> issues = new ArrayList<>();
        if (graph == null || !graph.isObject()) {
            issues.add(issue("$", "INVALID_TYPE", "task graph must be an object"));
            return new TaskGraphValidationResult(false, "", 0, 0, TaskGraphLimits.DEFAULTS, issues);
        }
        int serialized = graph.toString().getBytes(StandardCharsets.UTF_8).length;
        if (serialized > TaskGraphLimits.HARD_LIMITS.maxSerializedStateBytes()) {
            issues.add(issue("$", "GRAPH_TOO_LARGE", "serialized graph exceeds 2 MiB"));
        }
        rejectUnknown(graph, "$", Set.of("version", "id", "inputs", "permissions", "limits", "root",
                "provenance"), issues);
        if (!VERSION.equals(graph.path("version").asText())) {
            issues.add(issue("$.version", "UNSUPPORTED_VERSION", "version must be " + VERSION));
        }
        String graphId = graph.path("id").asText("");
        if (!ID.matcher(graphId).matches()) issues.add(issue("$.id", "INVALID_ID", "graph id is invalid"));
        validateInputs(graph.path("inputs"), issues);
        Set<String> permissions = validatePermissions(graph.path("permissions"), issues);
        TaskGraphLimits limits = TaskGraphLimits.parse(graph.path("limits"), issues);
        State state = new State(limits, availableTools == null ? Set.of() : Set.copyOf(availableTools),
                permissions, issues);
        validateNode(graph.path("root"), "$.root", 1, state);
        if (state.nodeCount > limits.maxNodes()) {
            issues.add(issue("$.root", "NODE_LIMIT_EXCEEDED", "graph exceeds maxNodes"));
        }
        if (state.toolCalls > limits.maxToolCalls()) {
            issues.add(issue("$.root", "TOOL_CALL_LIMIT_EXCEEDED", "static tool calls exceed maxToolCalls"));
        }
        return new TaskGraphValidationResult(issues.isEmpty(), graphId, state.nodeCount, state.maxDepth,
                limits, issues);
    }

    private static void validateNode(JsonNode node, String path, int depth, State state) {
        if (!node.isObject()) {
            state.issues.add(issue(path, "INVALID_NODE", "node must be an object"));
            return;
        }
        state.nodeCount++;
        state.maxDepth = Math.max(state.maxDepth, depth);
        if (depth > state.limits.maxDepth()) {
            state.issues.add(issue(path, "DEPTH_LIMIT_EXCEEDED", "node exceeds maxDepth"));
            return;
        }
        String id = node.path("id").asText("");
        if (!ID.matcher(id).matches()) state.issues.add(issue(path + ".id", "INVALID_NODE_ID", "stable node id is required"));
        else if (!state.nodeIds.add(id)) state.issues.add(issue(path + ".id", "DUPLICATE_NODE_ID", "node id must be unique"));
        String type = node.path("type").asText("");
        if (!NODE_TYPES.contains(type)) {
            state.issues.add(issue(path + ".type", "UNKNOWN_NODE_TYPE", "unsupported node type"));
            return;
        }
        switch (type) {
            case "sequence", "fallback" -> {
                rejectUnknown(node, path, Set.of("id", "type", "nodes"), state.issues);
                validateChildren(node.path("nodes"), path + ".nodes", depth, state, 1, state.limits.maxNodes());
            }
            case "parallel" -> {
                rejectUnknown(node, path, Set.of("id", "type", "nodes", "maxConcurrency"), state.issues);
                int maximum = boundedInt(node, "maxConcurrency", path, 1, state.limits.maxParallelNodes(), state.issues);
                validateChildren(node.path("nodes"), path + ".nodes", depth, state, 1, maximum);
            }
            case "call_tool" -> {
                rejectUnknown(node, path, Set.of("id", "type", "tool", "arguments", "permission"), state.issues);
                state.toolCalls++;
                String tool = node.path("tool").asText("");
                if (!TOOL.matcher(tool).matches()) state.issues.add(issue(path + ".tool", "INVALID_TOOL", "tool name is invalid"));
                else if (!state.availableTools.isEmpty() && !state.availableTools.contains(tool)) {
                    state.issues.add(issue(path + ".tool", "TOOL_UNAVAILABLE", "tool is not exposed in this context"));
                }
                if (node.has("arguments") && !node.path("arguments").isObject()) {
                    state.issues.add(issue(path + ".arguments", "INVALID_TYPE", "arguments must be an object"));
                }
                String permission = node.path("permission").asText("");
                if (!permission.isBlank() && !state.permissions.contains(permission)) {
                    state.issues.add(issue(path + ".permission", "PERMISSION_NOT_DECLARED",
                            "node permission is absent from graph permissions"));
                }
            }
            case "if" -> {
                rejectUnknown(node, path, Set.of("id", "type", "condition", "then", "else"), state.issues);
                expression(node, "condition", path, state.issues);
                validateNode(node.path("then"), path + ".then", depth + 1, state);
                if (node.has("else")) validateNode(node.path("else"), path + ".else", depth + 1, state);
            }
            case "switch" -> validateSwitch(node, path, depth, state);
            case "repeat" -> {
                rejectUnknown(node, path, Set.of("id", "type", "maxIterations", "until", "body"), state.issues);
                boundedInt(node, "maxIterations", path, 1, state.limits.maxLoopIterations(), state.issues);
                if (node.has("until")) expression(node, "until", path, state.issues);
                validateNode(node.path("body"), path + ".body", depth + 1, state);
            }
            case "while" -> {
                rejectUnknown(node, path, Set.of("id", "type", "condition", "maxIterations", "body"), state.issues);
                expression(node, "condition", path, state.issues);
                boundedInt(node, "maxIterations", path, 1, state.limits.maxLoopIterations(), state.issues);
                validateNode(node.path("body"), path + ".body", depth + 1, state);
            }
            case "retry" -> {
                rejectUnknown(node, path, Set.of("id", "type", "maxAttempts", "backoffMillis", "node"), state.issues);
                boundedInt(node, "maxAttempts", path, 1, state.limits.maxRetriesPerNode(), state.issues);
                boundedInt(node, "backoffMillis", path, 0, 60_000, state.issues);
                validateNode(node.path("node"), path + ".node", depth + 1, state);
            }
            case "wait" -> {
                rejectUnknown(node, path, Set.of("id", "type", "durationMillis"), state.issues);
                boundedInt(node, "durationMillis", path, 1,
                        Math.min(300_000, state.limits.maxWallTimeSeconds() * 1_000), state.issues);
            }
            case "ask_user" -> {
                rejectUnknown(node, path, Set.of("id", "type", "prompt", "options"), state.issues);
                boundedText(node, "prompt", path, 1, 2_000, state.issues);
                if (node.has("options")) validateStringArray(node.path("options"), path + ".options", 1, 8, state.issues);
            }
            case "read_memory" -> {
                rejectUnknown(node, path, Set.of("id", "type", "kind", "query"), state.issues);
                enumText(node, "kind", path, Set.of("WORKING", "EPISODIC", "WORLD", "PREFERENCE"), state.issues);
                boundedText(node, "query", path, 1, 1_024, state.issues);
            }
            case "suggest_memory" -> {
                rejectUnknown(node, path, Set.of("id", "type", "kind", "content"), state.issues);
                enumText(node, "kind", path, Set.of("EPISODIC", "WORLD", "PREFERENCE"), state.issues);
                boundedText(node, "content", path, 1, 4_096, state.issues);
            }
            case "checkpoint" -> {
                rejectUnknown(node, path, Set.of("id", "type", "label"), state.issues);
                boundedText(node, "label", path, 1, 128, state.issues);
            }
            case "emit_progress" -> {
                rejectUnknown(node, path, Set.of("id", "type", "message"), state.issues);
                boundedText(node, "message", path, 1, 1_024, state.issues);
            }
            case "return" -> rejectUnknown(node, path, Set.of("id", "type", "value"), state.issues);
            case "fail" -> {
                rejectUnknown(node, path, Set.of("id", "type", "code", "message"), state.issues);
                boundedText(node, "code", path, 1, 128, state.issues);
                boundedText(node, "message", path, 1, 1_024, state.issues);
            }
            default -> throw new IllegalStateException("unhandled node type");
        }
    }

    private static void validateSwitch(JsonNode node, String path, int depth, State state) {
        rejectUnknown(node, path, Set.of("id", "type", "expression", "cases", "default"), state.issues);
        expression(node, "expression", path, state.issues);
        JsonNode cases = node.path("cases");
        if (!cases.isArray() || cases.isEmpty() || cases.size() > 32) {
            state.issues.add(issue(path + ".cases", "INVALID_CASES", "cases must contain 1..32 entries"));
        } else {
            for (int index = 0; index < cases.size(); index++) {
                JsonNode branch = cases.path(index);
                String branchPath = path + ".cases[" + index + "]";
                if (!branch.isObject()) {
                    state.issues.add(issue(branchPath, "INVALID_TYPE", "case must be an object"));
                    continue;
                }
                rejectUnknown(branch, branchPath, Set.of("equals", "node"), state.issues);
                if (!branch.has("equals") || branch.path("equals").isContainerNode()) {
                    state.issues.add(issue(branchPath + ".equals", "INVALID_CASE_VALUE", "equals must be a scalar"));
                }
                validateNode(branch.path("node"), branchPath + ".node", depth + 1, state);
            }
        }
        if (node.has("default")) validateNode(node.path("default"), path + ".default", depth + 1, state);
    }

    private static void validateChildren(JsonNode nodes, String path, int depth, State state,
                                         int minimum, int maximum) {
        if (!nodes.isArray() || nodes.size() < minimum || nodes.size() > maximum) {
            state.issues.add(issue(path, "INVALID_CHILD_COUNT",
                    "nodes must contain " + minimum + ".." + maximum + " entries"));
            return;
        }
        for (int index = 0; index < nodes.size(); index++) {
            validateNode(nodes.path(index), path + "[" + index + "]", depth + 1, state);
        }
    }

    private static void validateInputs(JsonNode inputs, List<TaskGraphValidationIssue> issues) {
        if (inputs.isMissingNode()) return;
        if (!inputs.isObject() || inputs.size() > 64) {
            issues.add(issue("$.inputs", "INVALID_INPUTS", "inputs must be an object with at most 64 entries"));
            return;
        }
        inputs.fields().forEachRemaining(entry -> {
            String path = "$.inputs." + entry.getKey();
            if (!ID.matcher(entry.getKey()).matches() || !entry.getValue().isObject()) {
                issues.add(issue(path, "INVALID_INPUT", "input name or definition is invalid"));
                return;
            }
            rejectUnknown(entry.getValue(), path, Set.of("type", "required", "default"), issues);
            if (!INPUT_TYPES.contains(entry.getValue().path("type").asText())) {
                issues.add(issue(path + ".type", "INVALID_INPUT_TYPE", "unsupported input type"));
            }
            if (entry.getValue().has("required") && !entry.getValue().path("required").isBoolean()) {
                issues.add(issue(path + ".required", "INVALID_TYPE", "required must be boolean"));
            }
        });
    }

    private static Set<String> validatePermissions(JsonNode permissions, List<TaskGraphValidationIssue> issues) {
        Set<String> result = new HashSet<>();
        if (!permissions.isArray() || permissions.size() > 64) {
            issues.add(issue("$.permissions", "INVALID_PERMISSIONS", "permissions must be an array with at most 64 entries"));
            return result;
        }
        for (int index = 0; index < permissions.size(); index++) {
            String permission = permissions.path(index).asText("");
            if (!permission.matches("[A-Z][A-Z0-9_]{1,63}")) {
                issues.add(issue("$.permissions[" + index + "]", "INVALID_PERMISSION", "permission is invalid"));
            } else if (!result.add(permission)) {
                issues.add(issue("$.permissions[" + index + "]", "DUPLICATE_PERMISSION", "permission is duplicated"));
            }
        }
        return result;
    }

    private static void expression(JsonNode node, String field, String path,
                                   List<TaskGraphValidationIssue> issues) {
        if (!node.path(field).isTextual()) {
            issues.add(issue(path + "." + field, "INVALID_EXPRESSION", field + " must be a string"));
            return;
        }
        String error = SafeExpressionValidator.validate(node.path(field).asText());
        if (error != null) issues.add(issue(path + "." + field, "UNSAFE_EXPRESSION", error));
    }

    private static int boundedInt(JsonNode node, String field, String path, int minimum, int maximum,
                                  List<TaskGraphValidationIssue> issues) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            issues.add(issue(path + "." + field, "INVALID_TYPE", field + " must be an integer"));
            return minimum;
        }
        int result = value.asInt();
        if (result < minimum || result > maximum) {
            issues.add(issue(path + "." + field, "VALUE_OUT_OF_RANGE",
                    field + " must be " + minimum + ".." + maximum));
        }
        return result;
    }

    private static void boundedText(JsonNode node, String field, String path, int minimum, int maximum,
                                    List<TaskGraphValidationIssue> issues) {
        String value = node.path(field).isTextual() ? node.path(field).asText() : "";
        if (value.length() < minimum || value.length() > maximum) {
            issues.add(issue(path + "." + field, "INVALID_TEXT_LENGTH",
                    field + " must contain " + minimum + ".." + maximum + " characters"));
        }
    }

    private static void enumText(JsonNode node, String field, String path, Set<String> values,
                                 List<TaskGraphValidationIssue> issues) {
        if (!values.contains(node.path(field).asText())) {
            issues.add(issue(path + "." + field, "INVALID_VALUE", field + " is unsupported"));
        }
    }

    private static void validateStringArray(JsonNode value, String path, int minimum, int maximum,
                                            List<TaskGraphValidationIssue> issues) {
        if (!value.isArray() || value.size() < minimum || value.size() > maximum) {
            issues.add(issue(path, "INVALID_ARRAY", "array must contain " + minimum + ".." + maximum + " strings"));
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            if (!value.path(index).isTextual() || value.path(index).asText().isBlank()
                    || value.path(index).asText().length() > 256) {
                issues.add(issue(path + "[" + index + "]", "INVALID_TEXT", "option must contain 1..256 characters"));
            }
        }
    }

    private static void rejectUnknown(JsonNode value, String path, Set<String> allowed,
                                      List<TaskGraphValidationIssue> issues) {
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) issues.add(issue(path + "." + field, "UNKNOWN_FIELD", "unknown field"));
        });
    }

    private static TaskGraphValidationIssue issue(String path, String code, String message) {
        return new TaskGraphValidationIssue(path, code, message);
    }

    private static final class State {
        private final TaskGraphLimits limits;
        private final Set<String> availableTools;
        private final Set<String> permissions;
        private final List<TaskGraphValidationIssue> issues;
        private final Set<String> nodeIds = new HashSet<>();
        private int nodeCount;
        private int toolCalls;
        private int maxDepth;

        private State(TaskGraphLimits limits, Set<String> availableTools, Set<String> permissions,
                      List<TaskGraphValidationIssue> issues) {
            this.limits = limits;
            this.availableTools = availableTools;
            this.permissions = permissions;
            this.issues = issues;
        }
    }
}
