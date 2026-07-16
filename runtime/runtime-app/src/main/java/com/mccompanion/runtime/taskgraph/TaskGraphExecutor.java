package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolInputSchemaValidator;
import com.mccompanion.runtime.tool.ToolResult;
import com.mccompanion.runtime.tool.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Deterministic execution core. It executes a validated graph and never selects goals or strategy.
 * It contains no goal selection or planning behavior.
 */
public final class TaskGraphExecutor {
    public static final Set<String> EXECUTABLE_NODE_TYPES = Set.of("sequence", "call_tool", "if", "switch",
            "repeat", "while", "retry", "fallback", "parallel", "wait", "checkpoint", "emit_progress",
            "ask_user", "read_memory", "return", "fail");
    private final ToolGateway tools;
    private final TaskGraphValidator validator;
    private final Set<String> executableNodeTypes;
    private final ExecutorService parallelExecutor;
    private static final ExecutorService DEFAULT_PARALLEL_EXECUTOR =
            Executors.newFixedThreadPool(TaskGraphLimits.HARD_LIMITS.maxParallelNodes(), runnable -> {
                Thread thread = new Thread(runnable, "mcac-task-graph-parallel-shared");
                thread.setDaemon(true);
                return thread;
            });

    public TaskGraphExecutor(ToolGateway tools) {
        this(tools, new TaskGraphValidator(), EXECUTABLE_NODE_TYPES, DEFAULT_PARALLEL_EXECUTOR);
    }

    TaskGraphExecutor(ToolGateway tools, TaskGraphValidator validator) {
        this(tools, validator, EXECUTABLE_NODE_TYPES, DEFAULT_PARALLEL_EXECUTOR);
    }

    TaskGraphExecutor(ToolGateway tools, TaskGraphValidator validator, Set<String> executableNodeTypes) {
        this(tools, validator, executableNodeTypes, DEFAULT_PARALLEL_EXECUTOR);
    }

    TaskGraphExecutor(ToolGateway tools, TaskGraphValidator validator, Set<String> executableNodeTypes,
                      ExecutorService parallelExecutor) {
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.executableNodeTypes = Set.copyOf(executableNodeTypes);
        this.parallelExecutor = java.util.Objects.requireNonNull(parallelExecutor, "parallelExecutor");
    }

    public TaskGraphExecutionResult execute(String executionId, ToolContext context, JsonNode graph) {
        return execute(executionId, context, graph, Json.object(), null,
                new TaskGraphExecutionControl(), ignored -> { });
    }

    TaskGraphExecutionResult execute(String executionId, ToolContext context, JsonNode graph, JsonNode inputs,
                                     TaskGraphExecutionRecord previous, TaskGraphExecutionControl control,
                                     Consumer<TaskGraphExecutionSnapshot> snapshots) {
        String id = required(executionId);
        Map<String, ToolDefinition> available = tools.definitions(context).stream()
                .filter(value -> !value.name().startsWith("task_graph."))
                .collect(java.util.stream.Collectors.toMap(ToolDefinition::name, value -> value));
        TaskGraphValidationResult validation = validator.validateExecutable(graph, available, executableNodeTypes);
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
            case WAITING -> "WAITING";
            case RECONCILIATION -> "RECONCILIATION_REQUIRED";
            case FAILURE -> "FAILED";
        };
        state.result = outcome.value.deepCopy();
        state.publish(terminalState, null, outcome.code);
        return state.executionResult(terminalState, outcome);
    }

    private Outcome run(JsonNode node, String path, State state) {
        return run(node, path, state, "");
    }

    private Outcome run(JsonNode node, String path, State state, String scope) {
        Outcome stopped = state.controlOutcome();
        if (stopped != null) return stopped;
        if (System.nanoTime() > state.deadline) return Outcome.failure("WALL_TIME_LIMIT_EXCEEDED", Json.object());
        String type = node.path("type").asText();
        String nodeId = node.path("id").asText();
        String nodeKey = scoped(nodeId, scope);
        if (state.isCompleted(nodeKey)) return Outcome.success();
        state.enter(nodeId);
        if (!executableNodeTypes.contains(type)) {
            return Outcome.failure("NODE_TYPE_NOT_EXECUTABLE",
                    Json.object().put("nodeId", nodeId).put("nodeType", type));
        }
        Outcome outcome;
        try {
            outcome = switch (type) {
                case "sequence" -> sequence(node.path("nodes"), path + ".nodes", state, scope);
                case "fallback" -> fallback(node.path("nodes"), path + ".nodes", state, scope);
                case "retry" -> retry(node, path, state, scope);
                case "call_tool" -> callTool(node, path, state, nodeKey);
                case "if" -> ifNode(node, path, state, scope);
                case "switch" -> switchNode(node, path, state, scope);
                case "repeat" -> repeatNode(node, path, state, scope);
                case "while" -> whileNode(node, path, state, scope);
                case "parallel" -> parallelNode(node, path, state, scope);
                case "ask_user" -> askUser(node, state);
                case "read_memory" -> readMemory(node, path, state, nodeKey);
                case "wait" -> waitNode(node, state);
                case "checkpoint" -> event(nodeId, "CHECKPOINT", node.path("label"), state);
                case "emit_progress" -> event(nodeId, "PROGRESS", node.path("message"), state);
                case "return" -> new Outcome(Kind.RETURN, "OK",
                        TaskGraphValues.resolve(node.path("value"), state.valueContext()));
                case "fail" -> Outcome.failure(node.path("code").asText("TASK_GRAPH_FAILED"),
                        Json.object().put("message", node.path("message").asText()));
                default -> throw new IllegalStateException("unhandled executable type");
            };
        } catch (IllegalArgumentException failure) {
            outcome = Outcome.failure("TASK_GRAPH_VALUE_ERROR",
                    Json.object().put("nodeId", nodeId).put("message", failure.getMessage()));
        }
        if (outcome.kind == Kind.SUCCESS || outcome.kind == Kind.RETURN) {
            state.complete(nodeKey, nodeId);
        }
        return outcome;
    }

    private Outcome sequence(JsonNode nodes, String path, State state, String scope) {
        for (int index = 0; index < nodes.size(); index++) {
            Outcome outcome = run(nodes.path(index), path + "[" + index + "]", state, scope);
            if (outcome.kind != Kind.SUCCESS) return outcome;
        }
        return Outcome.success();
    }

    private Outcome fallback(JsonNode nodes, String path, State state, String scope) {
        Outcome last = Outcome.failure("FALLBACK_EXHAUSTED", Json.object());
        for (int index = 0; index < nodes.size(); index++) {
            Outcome outcome = run(nodes.path(index), path + "[" + index + "]", state, scope);
            if (outcome.kind != Kind.FAILURE) return outcome;
            last = outcome;
        }
        return last;
    }

    private Outcome retry(JsonNode node, String path, State state, String scope) {
        int maximum = node.path("maxAttempts").asInt();
        Outcome last = Outcome.failure("RETRY_EXHAUSTED", Json.object());
        for (int attempt = 1; attempt <= maximum; attempt++) {
            String childId = node.path("node").path("id").asText();
            state.setAttempt(scoped(childId, scope), attempt);
            last = run(node.path("node"), path + ".node", state, scope);
            if (last.kind != Kind.FAILURE) return last;
            if (attempt < maximum && node.path("backoffMillis").asLong() > 0) {
                Outcome waited = boundedSleep(node.path("backoffMillis").asLong(), state);
                if (waited.kind == Kind.FAILURE) return waited;
            }
        }
        return Outcome.failure("RETRY_EXHAUSTED", last.value);
    }

    private Outcome ifNode(JsonNode node, String path, State state, String scope) {
        boolean condition = SafeExpressionEvaluator.evaluateBoolean(node.path("condition").asText(),
                state.valueContext());
        if (condition) return run(node.path("then"), path + ".then", state, scope);
        if (node.has("else")) return run(node.path("else"), path + ".else", state, scope);
        return Outcome.success();
    }

    private Outcome switchNode(JsonNode node, String path, State state, String scope) {
        JsonNode value = SafeExpressionEvaluator.evaluate(node.path("expression").asText(), state.valueContext());
        JsonNode cases = node.path("cases");
        for (int index = 0; index < cases.size(); index++) {
            JsonNode branch = cases.path(index);
            if (scalarEquals(value, branch.path("equals"))) {
                return run(branch.path("node"), path + ".cases[" + index + "].node", state, scope);
            }
        }
        return node.has("default") ? run(node.path("default"), path + ".default", state, scope) : Outcome.success();
    }

    private Outcome repeatNode(JsonNode node, String path, State state, String parentScope) {
        String nodeId = node.path("id").asText();
        String loopKey = scoped(nodeId, parentScope);
        int maximum = node.path("maxIterations").asInt();
        int next = state.loopIteration(loopKey);
        for (int iteration = next; iteration < maximum; iteration++) {
            state.setLoopIteration(loopKey, iteration);
            state.publish("RUNNING", nodeId, "LOOP_ITERATION");
            Outcome outcome = run(node.path("body"), path + ".body", state,
                    childScope(parentScope, nodeId, iteration));
            if (outcome.kind != Kind.SUCCESS) return outcome;
            state.setLoopIteration(loopKey, iteration + 1);
            state.publish("RUNNING", nodeId, "LOOP_ITERATION_COMPLETED");
            if (node.has("until") && SafeExpressionEvaluator.evaluateBoolean(
                    node.path("until").asText(), state.valueContext())) {
                return Outcome.success();
            }
        }
        if (!node.has("until")) return Outcome.success();
        return Outcome.failure("LOOP_EXHAUSTED",
                Json.object().put("nodeId", nodeId).put("maxIterations", maximum));
    }

    private Outcome whileNode(JsonNode node, String path, State state, String parentScope) {
        String nodeId = node.path("id").asText();
        String loopKey = scoped(nodeId, parentScope);
        int maximum = node.path("maxIterations").asInt();
        int next = state.loopIteration(loopKey);
        for (int iteration = next; iteration < maximum; iteration++) {
            if (!SafeExpressionEvaluator.evaluateBoolean(node.path("condition").asText(), state.valueContext())) {
                return Outcome.success();
            }
            state.setLoopIteration(loopKey, iteration);
            state.publish("RUNNING", nodeId, "LOOP_ITERATION");
            Outcome outcome = run(node.path("body"), path + ".body", state,
                    childScope(parentScope, nodeId, iteration));
            if (outcome.kind != Kind.SUCCESS) return outcome;
            state.setLoopIteration(loopKey, iteration + 1);
            state.publish("RUNNING", nodeId, "LOOP_ITERATION_COMPLETED");
        }
        if (!SafeExpressionEvaluator.evaluateBoolean(node.path("condition").asText(), state.valueContext())) {
            return Outcome.success();
        }
        return Outcome.failure("LOOP_EXHAUSTED",
                Json.object().put("nodeId", nodeId).put("maxIterations", maximum));
    }

    private Outcome parallelNode(JsonNode node, String path, State state, String scope) {
        JsonNode nodes = node.path("nodes");
        int concurrency = Math.min(node.path("maxConcurrency").asInt(), nodes.size());
        var completion = new ExecutorCompletionService<IndexedOutcome>(parallelExecutor);
        List<Future<IndexedOutcome>> futures = new ArrayList<>();
        Outcome[] outcomes = new Outcome[nodes.size()];
        int next = 0;
        int completed = 0;
        try {
            while (next < concurrency) {
                int child = next++;
                futures.add(completion.submit(() -> new IndexedOutcome(child,
                        run(nodes.path(child), path + ".nodes[" + child + "]", state, scope))));
            }
            while (completed < nodes.size()) {
                IndexedOutcome result = completion.take().get();
                outcomes[result.index()] = result.outcome();
                completed++;
                if (next < nodes.size()) {
                    int child = next++;
                    futures.add(completion.submit(() -> new IndexedOutcome(child,
                            run(nodes.path(child), path + ".nodes[" + child + "]", state, scope))));
                }
            }
            for (Outcome outcome : outcomes) {
                if (outcome.kind != Kind.SUCCESS) return outcome;
            }
            return Outcome.success();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Outcome.failure("EXECUTION_INTERRUPTED", Json.object());
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            return Outcome.failure("PARALLEL_BRANCH_FAILED",
                    Json.object().put("message", cause == null ? failure.toString() : cause.toString()));
        } finally {
            futures.forEach(value -> {
                if (!value.isDone()) value.cancel(true);
            });
        }
    }

    private static boolean scalarEquals(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private Outcome callTool(JsonNode node, String path, State state, String nodeKey) {
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
        int attempt = state.attempt(nodeKey);
        String callId = state.executionId + ':' + nodeKey + ':' + attempt;
        ToolResult cached;
        synchronized (state) {
            cached = state.toolResults.get(callId);
        }
        if (cached != null) return toolOutcome(nodeId, cached, state);
        JsonNode resolvedArguments = node.has("arguments")
                ? TaskGraphValues.resolve(node.path("arguments"), state.valueContext()) : Json.object();
        List<ToolInputSchemaValidator.Violation> schemaViolations =
                ToolInputSchemaValidator.validate(definition.inputSchema(), resolvedArguments, false);
        if (!schemaViolations.isEmpty()) {
            ToolInputSchemaValidator.Violation violation = schemaViolations.getFirst();
            return Outcome.failure("TOOL_ARGUMENT_SCHEMA_INVALID",
                    Json.object().put("nodeId", nodeId).put("tool", definition.name())
                            .put("path", violation.path()).put("schemaCode", violation.code())
                            .put("message", violation.message()));
        }
        ToolCall call = new ToolCall(callId, node.path("tool").asText(), resolvedArguments);
        synchronized (state) {
            cached = state.toolResults.get(callId);
            if (cached != null) return toolOutcome(nodeId, cached, state);
            if (state.toolCalls >= state.limits.maxToolCalls()) {
                return Outcome.failure("TOOL_CALL_LIMIT_EXCEEDED", Json.object());
            }
            state.toolCalls++;
        }
        ToolResult terminal;
        state.control.callStarted(callId);
        try {
            ToolResult accepted = tools.execute(state.context, call);
            terminal = accepted.terminal() ? accepted : tools.awaitTerminal(state.context, call, accepted,
                    Duration.ofSeconds(Math.min(300, state.limits.maxWallTimeSeconds())), progress ->
                            state.addEvidence(progress.observation()));
        } catch (RuntimeException failure) {
            return Outcome.reconciliation("TOOL_RECONCILIATION_REQUIRED",
                    Json.object().put("nodeId", nodeId).put("callId", callId)
                            .put("tool", call.name()).put("message", boundedMessage(failure)));
        } finally {
            state.control.callFinished(callId);
        }
        state.recordToolResult(callId, nodeId, terminal);
        Outcome stopped = state.controlOutcome();
        if (stopped != null) return stopped;
        return toolOutcome(nodeId, terminal, state);
    }

    private Outcome readMemory(JsonNode node, String path, State state, String nodeKey) {
        ObjectNode call = Json.object().put("id", node.path("id").asText())
                .put("type", "call_tool").put("tool", "memory.search")
                .put("permission", "MEMORY");
        call.set("arguments", Json.object().put("kind", node.path("kind").asText())
                .put("query", node.path("query").asText()).put("limit", 25));
        return callTool(call, path, state, nodeKey);
    }

    private Outcome askUser(JsonNode node, State state) {
        String nodeId = node.path("id").asText();
        JsonNode waiting = state.waitingQuestion(nodeId);
        if (waiting.path("state").asText().equals("ANSWERED") && waiting.has("answer")) {
            state.recordNodeOutput(nodeId, waiting.path("answer"));
            state.clearWaitingQuestion();
            return Outcome.success();
        }
        if (waiting.isObject()) return Outcome.waiting(waiting);
        ObjectNode request = Json.object().put("state", "REQUESTED").put("nodeId", nodeId)
                .put("prompt", node.path("prompt").asText())
                .put("freeTextAllowed", node.has("freeTextAllowed")
                        ? node.path("freeTextAllowed").asBoolean() : !node.has("options"));
        request.set("options", node.has("options") ? node.path("options").deepCopy()
                : Json.MAPPER.createArrayNode());
        state.setWaitingQuestion(request);
        return Outcome.waiting(request);
    }

    private static Outcome toolOutcome(String nodeId, ToolResult result, State state) {
        state.recordToolOutcome(nodeId, result);
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
        state.recordEvent(nodeId, type, event);
        return Outcome.success();
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("executionId is invalid");
        }
        return value.strip();
    }

    private static String boundedMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.length() <= 1_024 ? message : message.substring(0, 1_024);
    }

    private static String scoped(String nodeId, String scope) {
        return scope.isBlank() ? nodeId : nodeId + '@' + scope;
    }

    private static String childScope(String parentScope, String nodeId, int iteration) {
        String current = nodeId + '#' + iteration;
        return parentScope.isBlank() ? current : parentScope + '/' + current;
    }

    private enum Kind { SUCCESS, FAILURE, RETURN, PAUSED, CANCELLED, WAITING, RECONCILIATION }
    private record IndexedOutcome(int index, Outcome outcome) {
    }

    private record Outcome(Kind kind, String code, JsonNode value) {
        static Outcome success() { return new Outcome(Kind.SUCCESS, "OK", Json.object()); }
        static Outcome failure(String code, JsonNode value) { return new Outcome(Kind.FAILURE, code, value); }
        static Outcome paused() { return new Outcome(Kind.PAUSED, "TASK_GRAPH_PAUSED", Json.object()); }
        static Outcome cancelled() { return new Outcome(Kind.CANCELLED, "TASK_GRAPH_CANCELLED", Json.object()); }
        static Outcome waiting(JsonNode value) {
            return new Outcome(Kind.WAITING, "TASK_GRAPH_WAITING_USER", value);
        }
        static Outcome reconciliation(String code, JsonNode value) {
            return new Outcome(Kind.RECONCILIATION, code, value);
        }
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
        private final Map<String, String> callNodes = new LinkedHashMap<>();
        private final Map<String, Integer> attempts = new LinkedHashMap<>();
        private final ObjectNode inputs;
        private final ObjectNode variables;
        private final Set<String> permissions;
        private final ArrayNode checkpoints = Json.MAPPER.createArrayNode();
        private final TaskGraphExecutionControl control;
        private final Consumer<TaskGraphExecutionSnapshot> snapshots;
        private String currentNodeId;
        private JsonNode result = Json.MAPPER.nullNode();
        private JsonNode waitingQuestion = Json.MAPPER.nullNode();
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

        private synchronized void addEvidence(JsonNode value) {
            evidence.add(value.deepCopy());
            while (evidence.size() > limits.maxEvidenceEntries()) evidence.removeFirst();
        }

        private void restore(TaskGraphExecutionRecord previous) {
            previous.completedNodes().forEach(value -> completedNodes.add(value.asText()));
            previous.variables().fields().forEachRemaining(entry -> variables.set(entry.getKey(), entry.getValue()));
            previous.outputs().fields().forEachRemaining(entry -> outputs.put(entry.getKey(), entry.getValue()));
            previous.checkpoints().forEach(value -> checkpoints.add(value.deepCopy()));
            previous.evidence().forEach(this::addEvidence);
            result = previous.result().deepCopy();
            waitingQuestion = previous.waitingQuestion().deepCopy();
            previous.toolResults().fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                ToolResult result = new ToolResult(entry.getKey(), value.path("toolName").asText(),
                        value.path("success").asBoolean(), value.path("code").asText(),
                        value.path("observation"), true);
                toolResults.put(entry.getKey(), result);
                String nodeId = value.path("nodeId").asText();
                if (!nodeId.isBlank()) {
                    callNodes.put(entry.getKey(), nodeId);
                    outputs.put(nodeId, result.observation());
                    variables.set(nodeId, result.observation().deepCopy());
                    variables.set("last", result.observation().deepCopy());
                }
            });
            toolCalls = toolResults.size();
        }

        private synchronized ObjectNode valueContext() {
            ObjectNode context = Json.object();
            context.set("inputs", inputs);
            context.set("variables", variables);
            context.set("state", variables);
            ObjectNode outputValues = context.putObject("outputs");
            outputs.forEach(outputValues::set);
            return context;
        }

        private Outcome controlOutcome() {
            if (control.cancelRequested()) return Outcome.cancelled();
            if (control.pauseRequested()) return Outcome.paused();
            return null;
        }

        private synchronized void publish(String state, String nodeId, String code) {
            ArrayNode completed = Json.MAPPER.createArrayNode();
            completedNodes.forEach(completed::add);
            ObjectNode results = Json.object();
            toolResults.forEach((callId, result) -> results.set(callId,
                    Json.object().put("nodeId", callNodes.getOrDefault(callId, nodeForCall(callId)))
                            .put("toolName", result.toolName())
                            .put("success", result.success()).put("code", result.code())
                            .set("observation", result.observation())));
            ObjectNode outputValues = Json.object();
            outputs.forEach(outputValues::set);
            ArrayNode boundedEvidence = Json.MAPPER.createArrayNode();
            evidence.forEach(boundedEvidence::add);
            snapshots.accept(new TaskGraphExecutionSnapshot(state, nodeId, completed, results,
                    variables.deepCopy(), checkpoints.deepCopy(), outputValues.deepCopy(),
                    boundedEvidence, waitingQuestion.deepCopy(), result.deepCopy(), code));
        }

        private String nodeForCall(String callId) {
            String prefix = executionId + ':';
            if (!callId.startsWith(prefix)) return "";
            String remaining = callId.substring(prefix.length());
            int separator = remaining.lastIndexOf(':');
            String nodeKey = separator > 0 ? remaining.substring(0, separator) : remaining;
            int scope = nodeKey.indexOf('@');
            return scope > 0 ? nodeKey.substring(0, scope) : nodeKey;
        }

        private synchronized int loopIteration(String nodeId) {
            return runtimeLoops().path(nodeId).asInt(0);
        }

        private synchronized void setLoopIteration(String nodeId, int iteration) {
            runtimeLoops().put(nodeId, iteration);
        }

        private ObjectNode runtimeLoops() {
            JsonNode runtime = variables.path("_mcac");
            ObjectNode runtimeObject;
            if (runtime.isObject()) runtimeObject = (ObjectNode) runtime;
            else {
                runtimeObject = Json.object();
                variables.set("_mcac", runtimeObject);
            }
            JsonNode loops = runtimeObject.path("loops");
            if (loops.isObject()) return (ObjectNode) loops;
            ObjectNode loopObject = Json.object();
            runtimeObject.set("loops", loopObject);
            return loopObject;
        }

        private synchronized boolean isCompleted(String nodeKey) {
            return completedNodes.contains(nodeKey);
        }

        private synchronized void enter(String nodeId) {
            currentNodeId = nodeId;
            publish("RUNNING", nodeId, "RUNNING");
        }

        private synchronized void complete(String nodeKey, String nodeId) {
            completedNodes.add(nodeKey);
            publish("RUNNING", nodeId, "NODE_COMPLETED");
        }

        private synchronized void setAttempt(String nodeKey, int attempt) {
            attempts.put(nodeKey, attempt);
        }

        private synchronized int attempt(String nodeKey) {
            return attempts.getOrDefault(nodeKey, 1);
        }

        private synchronized void recordToolResult(String callId, String nodeId, ToolResult result) {
            toolResults.put(callId, result);
            callNodes.put(callId, nodeId);
            publish("RUNNING", nodeId, "TOOL_RESULT_RECORDED");
        }

        private synchronized void recordToolOutcome(String nodeId, ToolResult result) {
            addEvidence(Json.object().put("nodeId", nodeId).put("callId", result.callId())
                    .put("tool", result.toolName()).put("success", result.success()).put("code", result.code())
                    .set("observation", result.observation()));
            outputs.put(nodeId, result.observation());
            variables.set(nodeId, result.observation().deepCopy());
            variables.set("last", result.observation().deepCopy());
        }

        private synchronized void recordEvent(String nodeId, String type, JsonNode event) {
            addEvidence(event);
            if (type.equals("CHECKPOINT")) checkpoints.add(event.deepCopy());
            publish("RUNNING", nodeId, type);
        }

        private synchronized TaskGraphExecutionResult executionResult(String terminalState, Outcome outcome) {
            return new TaskGraphExecutionResult(executionId, terminalState, outcome.code, toolCalls,
                    List.copyOf(completedNodes), Map.copyOf(outputs), List.copyOf(evidence), outcome.value);
        }

        private synchronized JsonNode waitingQuestion(String nodeId) {
            if (!waitingQuestion.isObject()) return Json.MAPPER.nullNode();
            String requestedNode = waitingQuestion.path("context").path("nodeId").asText(
                    waitingQuestion.path("nodeId").asText(""));
            return requestedNode.equals(nodeId) ? waitingQuestion.deepCopy() : Json.MAPPER.nullNode();
        }

        private synchronized void setWaitingQuestion(JsonNode question) {
            waitingQuestion = question.deepCopy();
        }

        private synchronized void clearWaitingQuestion() {
            waitingQuestion = Json.MAPPER.nullNode();
        }

        private synchronized void recordNodeOutput(String nodeId, JsonNode value) {
            outputs.put(nodeId, value.deepCopy());
            variables.set(nodeId, value.deepCopy());
            variables.set("last", value.deepCopy());
        }
    }
}
