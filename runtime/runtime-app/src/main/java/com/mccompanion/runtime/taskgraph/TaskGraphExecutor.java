package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import com.mccompanion.runtime.tool.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Deterministic execution core. It executes a validated graph and never selects goals or strategy.
 * Durable checkpoints/resume and the remaining node types are intentionally separate milestones.
 */
public final class TaskGraphExecutor {
    public static final Set<String> EXECUTABLE_NODE_TYPES = Set.of("sequence", "call_tool", "retry", "fallback", "wait",
            "checkpoint", "emit_progress", "return", "fail");
    private final ToolGateway tools;
    private final TaskGraphValidator validator;

    public TaskGraphExecutor(ToolGateway tools) {
        this(tools, new TaskGraphValidator());
    }

    TaskGraphExecutor(ToolGateway tools, TaskGraphValidator validator) {
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
    }

    public TaskGraphExecutionResult execute(String executionId, ToolContext context, JsonNode graph) {
        return execute(executionId, context, graph, Json.object(), null,
                new TaskGraphExecutionControl(), ignored -> { });
    }

    TaskGraphExecutionResult execute(String executionId, ToolContext context, JsonNode graph, JsonNode inputs,
                                     TaskGraphExecutionRecord previous, TaskGraphExecutionControl control,
                                     Consumer<TaskGraphExecutionSnapshot> snapshots) {
        String id = required(executionId);
        Map<String, String> available = tools.definitions(context).stream()
                .filter(value -> !value.name().startsWith("task_graph."))
                .collect(java.util.stream.Collectors.toMap(value -> value.name(), value -> value.permission()));
        TaskGraphValidationResult validation = validator.validateExecutable(graph, available, EXECUTABLE_NODE_TYPES);
        if (!validation.valid()) {
            return new TaskGraphExecutionResult(id, "FAILED", "TASK_GRAPH_INVALID", 0, List.of(), Map.of(),
                    List.of(validation.toJson()), Json.object());
        }
        ObjectNode validatedInputs = TaskGraphValues.validateInputs(graph, inputs);
        State state = new State(id, context, graph, validation.limits(), validatedInputs, previous, control, snapshots);
        state.publish("RUNNING", null, "RUNNING");
        Outcome outcome = run(graph.path("root"), "$.root", state);
        String terminalState = switch (outcome.kind) {
            case SUCCESS, RETURN -> "SUCCEEDED";
            case PAUSED -> "PAUSED";
            case CANCELLED -> "CANCELLED";
            case FAILURE -> "FAILED";
        };
        state.publish(terminalState, null, outcome.code);
        return new TaskGraphExecutionResult(id, terminalState, outcome.code, state.toolCalls,
                List.copyOf(state.completedNodes), state.outputs, state.evidence, outcome.value);
    }

    private Outcome run(JsonNode node, String path, State state) {
        Outcome stopped = state.controlOutcome();
        if (stopped != null) return stopped;
        if (System.nanoTime() > state.deadline) return Outcome.failure("WALL_TIME_LIMIT_EXCEEDED", Json.object());
        String type = node.path("type").asText();
        String nodeId = node.path("id").asText();
        if (state.completedNodes.contains(nodeId)) return Outcome.success();
        state.currentNodeId = nodeId;
        state.publish("RUNNING", nodeId, "RUNNING");
        if (!EXECUTABLE_NODE_TYPES.contains(type)) {
            return Outcome.failure("NODE_TYPE_NOT_EXECUTABLE",
                    Json.object().put("nodeId", nodeId).put("nodeType", type));
        }
        Outcome outcome = switch (type) {
            case "sequence" -> sequence(node.path("nodes"), path + ".nodes", state);
            case "fallback" -> fallback(node.path("nodes"), path + ".nodes", state);
            case "retry" -> retry(node, path, state);
            case "call_tool" -> callTool(node, path, state);
            case "wait" -> waitNode(node, state);
            case "checkpoint" -> event(nodeId, "CHECKPOINT", node.path("label"), state);
            case "emit_progress" -> event(nodeId, "PROGRESS", node.path("message"), state);
            case "return" -> new Outcome(Kind.RETURN, "OK",
                    TaskGraphValues.resolve(node.path("value"), state.valueContext()));
            case "fail" -> Outcome.failure(node.path("code").asText("TASK_GRAPH_FAILED"),
                    Json.object().put("message", node.path("message").asText()));
            default -> throw new IllegalStateException("unhandled executable type");
        };
        if (outcome.kind == Kind.SUCCESS || outcome.kind == Kind.RETURN) {
            state.completedNodes.add(nodeId);
            state.publish("RUNNING", nodeId, "NODE_COMPLETED");
        }
        return outcome;
    }

    private Outcome sequence(JsonNode nodes, String path, State state) {
        for (int index = 0; index < nodes.size(); index++) {
            Outcome outcome = run(nodes.path(index), path + "[" + index + "]", state);
            if (outcome.kind != Kind.SUCCESS) return outcome;
        }
        return Outcome.success();
    }

    private Outcome fallback(JsonNode nodes, String path, State state) {
        Outcome last = Outcome.failure("FALLBACK_EXHAUSTED", Json.object());
        for (int index = 0; index < nodes.size(); index++) {
            Outcome outcome = run(nodes.path(index), path + "[" + index + "]", state);
            if (outcome.kind != Kind.FAILURE) return outcome;
            last = outcome;
        }
        return last;
    }

    private Outcome retry(JsonNode node, String path, State state) {
        int maximum = node.path("maxAttempts").asInt();
        Outcome last = Outcome.failure("RETRY_EXHAUSTED", Json.object());
        for (int attempt = 1; attempt <= maximum; attempt++) {
            state.attempts.put(node.path("node").path("id").asText(), attempt);
            last = run(node.path("node"), path + ".node", state);
            if (last.kind != Kind.FAILURE) return last;
            if (attempt < maximum && node.path("backoffMillis").asLong() > 0) {
                Outcome waited = boundedSleep(node.path("backoffMillis").asLong(), state);
                if (waited.kind == Kind.FAILURE) return waited;
            }
        }
        return Outcome.failure("RETRY_EXHAUSTED", last.value);
    }

    private Outcome callTool(JsonNode node, String path, State state) {
        if (state.toolCalls >= state.limits.maxToolCalls()) {
            return Outcome.failure("TOOL_CALL_LIMIT_EXCEEDED", Json.object());
        }
        String nodeId = node.path("id").asText();
        ToolDefinition definition = tools.definitions(state.context).stream()
                .filter(value -> value.name().equals(node.path("tool").asText())).findFirst().orElse(null);
        if (definition == null || definition.name().startsWith("task_graph.")) {
            return Outcome.failure("TOOL_UNAVAILABLE", Json.object().put("nodeId", nodeId));
        }
        if (!state.permissions.contains(definition.permission())) {
            return Outcome.failure("TOOL_PERMISSION_DENIED",
                    Json.object().put("nodeId", nodeId).put("requiredPermission", definition.permission()));
        }
        int attempt = state.attempts.getOrDefault(nodeId, 1);
        String callId = state.executionId + ':' + nodeId + ':' + attempt;
        ToolResult cached = state.toolResults.get(callId);
        if (cached != null) return toolOutcome(nodeId, cached, state);
        ToolCall call = new ToolCall(callId, node.path("tool").asText(),
                node.has("arguments") ? TaskGraphValues.resolve(node.path("arguments"), state.valueContext())
                        : Json.object());
        state.toolCalls++;
        state.control.activeCallId(callId);
        ToolResult accepted = tools.execute(state.context, call);
        ToolResult terminal = accepted.terminal() ? accepted : tools.awaitTerminal(state.context, call, accepted,
                Duration.ofSeconds(Math.min(300, state.limits.maxWallTimeSeconds())), progress ->
                        state.addEvidence(progress.observation()));
        state.control.activeCallId(null);
        state.toolResults.put(callId, terminal);
        state.publish("RUNNING", nodeId, "TOOL_RESULT_RECORDED");
        Outcome stopped = state.controlOutcome();
        if (stopped != null) return stopped;
        return toolOutcome(nodeId, terminal, state);
    }

    private static Outcome toolOutcome(String nodeId, ToolResult result, State state) {
        state.addEvidence(Json.object().put("nodeId", nodeId).put("callId", result.callId())
                .put("tool", result.toolName()).put("success", result.success()).put("code", result.code())
                .set("observation", result.observation()));
        state.outputs.put(nodeId, result.observation());
        return result.success() && result.terminal() ? Outcome.success()
                : Outcome.failure(result.code(), result.observation());
    }

    private Outcome waitNode(JsonNode node, State state) {
        return boundedSleep(node.path("durationMillis").asLong(), state);
    }

    private Outcome boundedSleep(long millis, State state) {
        if (millis <= 0) return Outcome.success();
        long remainingNanos = state.deadline - System.nanoTime();
        if (remainingNanos <= 0 || Duration.ofNanos(remainingNanos).toMillis() < millis) {
            return Outcome.failure("WALL_TIME_LIMIT_EXCEEDED", Json.object());
        }
        try {
            long remaining = millis;
            while (remaining > 0) {
                Outcome stopped = state.controlOutcome();
                if (stopped != null) return stopped;
                long slice = Math.min(remaining, 25);
                Thread.sleep(slice);
                remaining -= slice;
            }
            return Outcome.success();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Outcome.failure("EXECUTION_INTERRUPTED", Json.object());
        }
    }

    private static Outcome event(String nodeId, String type, JsonNode content, State state) {
        ObjectNode event = Json.object().put("nodeId", nodeId).put("type", type)
                .put("content", content.asText());
        state.addEvidence(event);
        if (type.equals("CHECKPOINT")) state.checkpoints.add(event.deepCopy());
        state.publish("RUNNING", nodeId, type);
        return Outcome.success();
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("executionId is invalid");
        }
        return value.strip();
    }

    private enum Kind { SUCCESS, FAILURE, RETURN, PAUSED, CANCELLED }
    private record Outcome(Kind kind, String code, JsonNode value) {
        static Outcome success() { return new Outcome(Kind.SUCCESS, "OK", Json.object()); }
        static Outcome failure(String code, JsonNode value) { return new Outcome(Kind.FAILURE, code, value); }
        static Outcome paused() { return new Outcome(Kind.PAUSED, "TASK_GRAPH_PAUSED", Json.object()); }
        static Outcome cancelled() { return new Outcome(Kind.CANCELLED, "TASK_GRAPH_CANCELLED", Json.object()); }
    }

    private static final class State {
        private final String executionId;
        private final ToolContext context;
        private final JsonNode graph;
        private final TaskGraphLimits limits;
        private final long deadline;
        private final LinkedHashSet<String> completedNodes = new LinkedHashSet<>();
        private final Map<String, JsonNode> outputs = new LinkedHashMap<>();
        private final List<JsonNode> evidence = new ArrayList<>();
        private final Map<String, ToolResult> toolResults = new LinkedHashMap<>();
        private final Map<String, Integer> attempts = new LinkedHashMap<>();
        private final ObjectNode inputs;
        private final ObjectNode variables;
        private final Set<String> permissions;
        private final ArrayNode checkpoints = Json.MAPPER.createArrayNode();
        private final TaskGraphExecutionControl control;
        private final Consumer<TaskGraphExecutionSnapshot> snapshots;
        private String currentNodeId;
        private int toolCalls;

        private State(String executionId, ToolContext context, JsonNode graph, TaskGraphLimits limits,
                      ObjectNode inputs, TaskGraphExecutionRecord previous, TaskGraphExecutionControl control,
                      Consumer<TaskGraphExecutionSnapshot> snapshots) {
            this.executionId = executionId;
            this.context = context;
            this.graph = graph;
            this.limits = limits;
            this.inputs = inputs;
            this.variables = inputs.deepCopy();
            this.permissions = new java.util.HashSet<>();
            graph.path("permissions").forEach(value -> permissions.add(value.asText()));
            this.control = control;
            this.snapshots = snapshots;
            this.deadline = System.nanoTime() + Duration.ofSeconds(limits.maxWallTimeSeconds()).toNanos();
            if (previous != null) restore(previous);
        }

        private void addEvidence(JsonNode value) {
            evidence.add(value.deepCopy());
            while (evidence.size() > limits.maxEvidenceEntries()) evidence.removeFirst();
        }

        private void restore(TaskGraphExecutionRecord previous) {
            previous.completedNodes().forEach(value -> completedNodes.add(value.asText()));
            previous.variables().fields().forEachRemaining(entry -> variables.set(entry.getKey(), entry.getValue()));
            previous.checkpoints().forEach(value -> checkpoints.add(value.deepCopy()));
            previous.evidence().forEach(this::addEvidence);
            previous.toolResults().fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                ToolResult result = new ToolResult(entry.getKey(), value.path("toolName").asText(),
                        value.path("success").asBoolean(), value.path("code").asText(),
                        value.path("observation"), true);
                toolResults.put(entry.getKey(), result);
                String nodeId = value.path("nodeId").asText();
                if (!nodeId.isBlank()) outputs.put(nodeId, result.observation());
            });
            toolCalls = toolResults.size();
        }

        private ObjectNode valueContext() {
            ObjectNode context = Json.object();
            context.set("inputs", inputs);
            context.set("variables", variables);
            ObjectNode outputValues = context.putObject("outputs");
            outputs.forEach(outputValues::set);
            return context;
        }

        private Outcome controlOutcome() {
            if (control.cancelRequested()) return Outcome.cancelled();
            if (control.pauseRequested()) return Outcome.paused();
            return null;
        }

        private void publish(String state, String nodeId, String code) {
            ArrayNode completed = Json.MAPPER.createArrayNode();
            completedNodes.forEach(completed::add);
            ObjectNode results = Json.object();
            toolResults.forEach((callId, result) -> results.set(callId,
                    Json.object().put("nodeId", nodeForCall(callId)).put("toolName", result.toolName())
                            .put("success", result.success()).put("code", result.code())
                            .set("observation", result.observation())));
            ArrayNode boundedEvidence = Json.MAPPER.createArrayNode();
            evidence.forEach(boundedEvidence::add);
            snapshots.accept(new TaskGraphExecutionSnapshot(state, nodeId, completed, results,
                    variables.deepCopy(), checkpoints.deepCopy(), boundedEvidence, code));
        }

        private String nodeForCall(String callId) {
            String prefix = executionId + ':';
            if (!callId.startsWith(prefix)) return "";
            String remaining = callId.substring(prefix.length());
            int separator = remaining.lastIndexOf(':');
            return separator > 0 ? remaining.substring(0, separator) : remaining;
        }
    }
}
