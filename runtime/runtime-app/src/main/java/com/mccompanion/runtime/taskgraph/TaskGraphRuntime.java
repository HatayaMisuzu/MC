package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.mccompanion.runtime.security.Digests;

/**
 * Persistent asynchronous execution service. It schedules deterministic graph execution only;
 * it never creates goals, graphs, or high-level strategies.
 */
public final class TaskGraphRuntime implements AutoCloseable {
    private static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "FAILED", "CANCELLED", "PAUSED", "RECONCILIATION_REQUIRED");
    private final ToolGateway tools;
    private final TaskGraphExecutionRepository repository;
    private final TaskGraphValidator validator = new TaskGraphValidator();
    private final ExecutorService workers;
    private final Map<String, Running> active = new ConcurrentHashMap<>();

    public TaskGraphRuntime(ToolGateway tools, TaskGraphExecutionRepository repository) {
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mcac-task-graph");
            thread.setDaemon(false);
            return thread;
        });
    }

    public ToolResult start(ToolContext context, ToolCall call, JsonNode graph, JsonNode inputs,
                            JsonNode provenance) {
        try {
            var definitions = ordinaryDefinitions(context);
            TaskGraphValidationResult validation = validator.validateExecutable(graph, definitions,
                    TaskGraphExecutor.EXECUTABLE_NODE_TYPES);
            if (!validation.valid()) {
                return new ToolResult(call.callId(), call.name(), false, "TASK_GRAPH_INVALID",
                        validation.toJson(), true);
            }
            JsonNode boundedInputs = TaskGraphValues.validateInputs(graph, inputs);
            var existing = repository.get(call.callId());
            if (existing.isPresent()) {
                TaskGraphExecutionRecord record = existing.get();
                verifyOwned(context, record);
                String hash = Digests.sha256(Json.canonical(graph));
                if (!record.graphHash().equals(hash)
                        || !Json.canonical(record.inputs()).equals(Json.canonical(boundedInputs))) {
                    return ToolResult.rejected(call, "TASK_GRAPH_CALL_ID_REUSED",
                            "execution ID was reused with different graph input");
                }
                if (TERMINAL.contains(record.state())) return terminal(call, record);
                if (record.state().equals("READY") && !active.containsKey(record.executionId())) submit(context, record);
                return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_ACCEPTED",
                        inspectJson(record).put("state", "ACCEPTED"), false);
            }
            TaskGraphExecutionRecord record = repository.create(call.callId(), context, graph,
                    validation.limits(), boundedInputs, provenance);
            submit(context, record);
            return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_ACCEPTED",
                    inspectJson(record).put("state", "ACCEPTED"), false);
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (SQLException failure) {
            return ToolResult.rejected(call, "PERSISTENCE_ERROR", "Task Graph state is unavailable");
        }
    }

    public ToolResult await(ToolContext context, ToolCall call, Duration timeout,
                            java.util.function.Consumer<ToolResult> progress) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long seenRevision = -1;
        try {
            while (System.nanoTime() < deadline) {
                TaskGraphExecutionRecord record = owned(context, call.callId());
                if (record.revision() != seenRevision) {
                    seenRevision = record.revision();
                    if (!TERMINAL.contains(record.state())) {
                        progress.accept(new ToolResult(call.callId(), call.name(), true, "TOOL_PROGRESS",
                                inspectJson(record), false));
                    }
                }
                if (TERMINAL.contains(record.state())) return terminal(call, record);
                Thread.sleep(25);
            }
            cancel(context, call.callId(), "timeout");
            return new ToolResult(call.callId(), call.name(), false, "TOOL_TIMEOUT",
                    Json.object().put("executionId", call.callId()).put("state", "INTERRUPTED"), true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new ToolResult(call.callId(), call.name(), false, "TOOL_INTERRUPTED",
                    Json.object().put("executionId", call.callId()).put("state", "INTERRUPTED"), true);
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_INSPECTION_FAILED", failure.getMessage());
        }
    }

    public ToolResult inspect(ToolContext context, ToolCall call, String executionId) {
        try {
            TaskGraphExecutionRecord record = owned(context, executionId);
            return new ToolResult(call.callId(), call.name(), true, "OK", inspectJson(record), true);
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_INSPECTION_FAILED", failure.getMessage());
        }
    }

    public ToolResult pause(ToolContext context, ToolCall call, String executionId) {
        try {
            TaskGraphExecutionRecord record = owned(context, executionId);
            if (TERMINAL.contains(record.state())) return terminal(call, record);
            Running running = active.get(executionId);
            if (running == null) return ToolResult.rejected(call, "TASK_GRAPH_NOT_RUNNING", "execution is not active");
            running.control.requestPause();
            cancelActiveTool(running, "task graph pause");
            return new ToolResult(call.callId(), call.name(), true, "PAUSE_REQUESTED",
                    inspectJson(record).put("state", "PAUSE_REQUESTED"), true);
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    public ToolResult resume(ToolContext context, ToolCall call, String executionId) {
        try {
            TaskGraphExecutionRecord record = owned(context, executionId);
            if (!record.state().equals("PAUSED")) {
                return ToolResult.rejected(call, "TASK_GRAPH_NOT_PAUSED",
                        "only a safely paused execution can resume");
            }
            TaskGraphExecutionRecord ready = repository.save(record.executionId(), record.revision(),
                    "READY", record.currentNodeId(), record.completedNodes(), record.toolResults(),
                    record.variables(), record.checkpoints(), record.evidence(), record.waitingQuestion(),
                    record.result(), "RESUME_REQUESTED");
            submit(context, ready);
            return new ToolResult(call.callId(), call.name(), true, "RESUME_ACCEPTED",
                    inspectJson(ready).put("state", "ACCEPTED"), true);
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    public ToolResult cancel(ToolContext context, String executionId, String reason) {
        ToolCall synthetic = new ToolCall("cancel-" + executionId, "task_graph.cancel",
                Json.object().put("executionId", executionId));
        try {
            TaskGraphExecutionRecord record = owned(context, executionId);
            Running running = active.get(executionId);
            if (running != null) {
                running.control.requestCancel();
                cancelActiveTool(running, reason);
                return new ToolResult(synthetic.callId(), synthetic.name(), true, "CANCEL_REQUESTED",
                        inspectJson(record).put("state", "CANCEL_REQUESTED"), true);
            }
            if (record.state().equals("PAUSED")) {
                TaskGraphExecutionRecord cancelled = repository.save(record.executionId(), record.revision(),
                        "CANCELLED", null, record.completedNodes(), record.toolResults(), record.variables(),
                        record.checkpoints(), record.evidence(), record.waitingQuestion(), record.result(),
                        "TASK_GRAPH_CANCELLED");
                return terminal(synthetic, cancelled);
            }
            return terminal(synthetic, record);
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(synthetic, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    private void submit(ToolContext context, TaskGraphExecutionRecord record) {
        TaskGraphExecutionControl control = new TaskGraphExecutionControl();
        Running running = new Running(context, control);
        if (active.putIfAbsent(record.executionId(), running) != null) {
            throw new IllegalArgumentException("execution is already active");
        }
        workers.submit(() -> {
            AtomicLong revision = new AtomicLong(record.revision());
            try {
                new TaskGraphExecutor(tools).execute(record.executionId(), context, record.graph(), record.inputs(),
                        record, control, snapshot -> {
                            try {
                                TaskGraphExecutionRecord saved = repository.save(record.executionId(),
                                        revision.get(), snapshot.state(), snapshot.currentNodeId(),
                                        snapshot.completedNodes(), snapshot.toolResults(), snapshot.variables(),
                                        snapshot.checkpoints(), snapshot.evidence(), Json.MAPPER.nullNode(),
                                        snapshot.result(), snapshot.resultCode());
                                revision.set(saved.revision());
                            } catch (SQLException failure) {
                                throw new IllegalStateException("TASK_GRAPH_PERSISTENCE_ERROR", failure);
                            }
                        });
            } finally {
                active.remove(record.executionId(), running);
            }
        });
    }

    private Map<String, String> ordinaryDefinitions(ToolContext context) {
        return tools.definitions(context).stream().filter(value -> !value.name().startsWith("task_graph."))
                .collect(java.util.stream.Collectors.toMap(ToolDefinition::name, ToolDefinition::permission));
    }

    private TaskGraphExecutionRecord owned(ToolContext context, String executionId) throws SQLException {
        TaskGraphExecutionRecord record = repository.get(executionId)
                .orElseThrow(() -> new IllegalArgumentException("execution not found"));
        verifyOwned(context, record);
        return record;
    }

    private static void verifyOwned(ToolContext context, TaskGraphExecutionRecord record) {
        if (!record.controllerId().equals(context.controllerId())
                || !record.brainSessionId().equals(context.brainSessionId())
                || !record.companionId().equals(context.companionId())) {
            throw new IllegalArgumentException("execution does not belong to this session");
        }
    }

    private void cancelActiveTool(Running running, String reason) {
        String callId = running.control.activeCallId();
        if (callId != null) tools.cancel(running.context, callId, reason);
    }

    private static ToolResult terminal(ToolCall call, TaskGraphExecutionRecord record) {
        boolean success = record.state().equals("SUCCEEDED");
        String code = success ? "OK" : record.resultCode();
        return new ToolResult(call.callId(), call.name(), success, code, inspectJson(record), true);
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode inspectJson(TaskGraphExecutionRecord record) {
        var result = Json.object().put("executionId", record.executionId()).put("graphId", record.graphId())
                .put("graphVersion", record.graphVersion()).put("graphHash", record.graphHash())
                .put("state", record.state()).put("currentNodeId",
                        record.currentNodeId() == null ? "" : record.currentNodeId())
                .put("revision", record.revision()).put("resultCode", record.resultCode());
        result.set("completedNodes", record.completedNodes());
        result.set("outputs", outputs(record.toolResults()));
        result.set("variables", record.variables());
        result.set("checkpoints", record.checkpoints());
        result.set("evidence", record.evidence());
        result.set("value", record.result());
        return result;
    }

    private static JsonNode outputs(JsonNode results) {
        var outputs = Json.object();
        results.fields().forEachRemaining(entry -> {
            String nodeId = entry.getValue().path("nodeId").asText();
            if (!nodeId.isBlank()) outputs.set(nodeId, entry.getValue().path("observation"));
        });
        return outputs;
    }

    @Override public void close() {
        active.values().forEach(value -> {
            value.control.requestCancel();
            cancelActiveTool(value, "runtime shutdown");
        });
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Task Graph workers did not terminate");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private record Running(ToolContext context, TaskGraphExecutionControl control) { }
}
