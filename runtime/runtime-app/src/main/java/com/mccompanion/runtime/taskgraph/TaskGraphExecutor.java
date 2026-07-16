package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic execution core. It executes a validated graph and never selects goals or strategy.
 * Durable checkpoints/resume and the remaining node types are intentionally separate milestones.
 */
public final class TaskGraphExecutor {
    private static final Set<String> EXECUTABLE = Set.of("sequence", "call_tool", "retry", "fallback", "wait",
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
        String id = required(executionId);
        Set<String> available = tools.definitions(context).stream().map(value -> value.name())
                .filter(name -> !name.startsWith("task_graph."))
                .collect(java.util.stream.Collectors.toSet());
        TaskGraphValidationResult validation = validator.validate(graph, available);
        if (!validation.valid()) {
            return new TaskGraphExecutionResult(id, "FAILED", "TASK_GRAPH_INVALID", 0, List.of(), Map.of(),
                    List.of(validation.toJson()), Json.object());
        }
        State state = new State(id, context, validation.limits());
        Outcome outcome = run(graph.path("root"), "$.root", state);
        String terminalState = outcome.kind == Kind.SUCCESS || outcome.kind == Kind.RETURN ? "SUCCEEDED" : "FAILED";
        return new TaskGraphExecutionResult(id, terminalState, outcome.code, state.toolCalls,
                state.completedNodes, state.outputs, state.evidence, outcome.value);
    }

    private Outcome run(JsonNode node, String path, State state) {
        if (System.nanoTime() > state.deadline) return Outcome.failure("WALL_TIME_LIMIT_EXCEEDED", Json.object());
        String type = node.path("type").asText();
        String nodeId = node.path("id").asText();
        if (!EXECUTABLE.contains(type)) {
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
            case "return" -> new Outcome(Kind.RETURN, "OK", node.path("value").deepCopy());
            case "fail" -> Outcome.failure(node.path("code").asText("TASK_GRAPH_FAILED"),
                    Json.object().put("message", node.path("message").asText()));
            default -> throw new IllegalStateException("unhandled executable type");
        };
        if (outcome.kind != Kind.FAILURE) state.completedNodes.add(nodeId);
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
        int attempt = state.attempts.getOrDefault(nodeId, 1);
        String callId = state.executionId + ':' + nodeId + ':' + attempt;
        ToolResult cached = state.toolResults.get(callId);
        if (cached != null) return toolOutcome(nodeId, cached, state);
        ToolCall call = new ToolCall(callId, node.path("tool").asText(),
                node.has("arguments") ? node.path("arguments") : Json.object());
        state.toolCalls++;
        ToolResult accepted = tools.execute(state.context, call);
        ToolResult terminal = accepted.terminal() ? accepted : tools.awaitTerminal(state.context, call, accepted,
                Duration.ofSeconds(Math.min(300, state.limits.maxWallTimeSeconds())), progress ->
                        state.evidence.add(progress.observation().deepCopy()));
        state.toolResults.put(callId, terminal);
        return toolOutcome(nodeId, terminal, state);
    }

    private static Outcome toolOutcome(String nodeId, ToolResult result, State state) {
        state.evidence.add(Json.object().put("nodeId", nodeId).put("callId", result.callId())
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
            Thread.sleep(millis);
            return Outcome.success();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Outcome.failure("EXECUTION_INTERRUPTED", Json.object());
        }
    }

    private static Outcome event(String nodeId, String type, JsonNode content, State state) {
        state.evidence.add(Json.object().put("nodeId", nodeId).put("type", type)
                .put("content", content.asText()));
        if (state.evidence.size() > state.limits.maxEvidenceEntries()) {
            state.evidence.removeFirst();
        }
        return Outcome.success();
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("executionId is invalid");
        }
        return value.strip();
    }

    private enum Kind { SUCCESS, FAILURE, RETURN }
    private record Outcome(Kind kind, String code, JsonNode value) {
        static Outcome success() { return new Outcome(Kind.SUCCESS, "OK", Json.object()); }
        static Outcome failure(String code, JsonNode value) { return new Outcome(Kind.FAILURE, code, value); }
    }

    private static final class State {
        private final String executionId;
        private final ToolContext context;
        private final TaskGraphLimits limits;
        private final long deadline;
        private final List<String> completedNodes = new ArrayList<>();
        private final Map<String, JsonNode> outputs = new LinkedHashMap<>();
        private final List<JsonNode> evidence = new ArrayList<>();
        private final Map<String, ToolResult> toolResults = new LinkedHashMap<>();
        private final Map<String, Integer> attempts = new LinkedHashMap<>();
        private int toolCalls;

        private State(String executionId, ToolContext context, TaskGraphLimits limits) {
            this.executionId = executionId;
            this.context = context;
            this.limits = limits;
            this.deadline = System.nanoTime() + Duration.ofSeconds(limits.maxWallTimeSeconds()).toNanos();
        }
    }
}
