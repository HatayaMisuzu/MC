package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import com.mccompanion.runtime.conversation.ConversationOption;
import com.mccompanion.runtime.conversation.ConversationRepository;
import com.mccompanion.runtime.conversation.IncomingMessageResolution;
import com.mccompanion.runtime.conversation.WaitingQuestion;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import com.mccompanion.runtime.security.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent asynchronous execution service. It schedules deterministic graph execution only;
 * it never creates goals, graphs, or high-level strategies.
 */
public final class TaskGraphRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGraphRuntime.class);
    private static final Duration DEFAULT_CANCELLATION_CONFIRMATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "FAILED", "CANCELLED", "PAUSED", "RECONCILIATION_REQUIRED");
    private final ToolGateway tools;
    private final TaskGraphExecutionRepository repository;
    private final ConversationRepository conversations;
    private final Set<String> executableNodeTypes;
    private final Duration cancellationConfirmationTimeout;
    private final TaskGraphValidator validator = new TaskGraphValidator();
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor parallelWorkers;
    private final ScheduledExecutorService timers;
    private final Map<String, Running> active = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timedWaits = new ConcurrentHashMap<>();

    public TaskGraphRuntime(ToolGateway tools, TaskGraphExecutionRepository repository) {
        this(tools, repository, null);
    }

    public TaskGraphRuntime(ToolGateway tools, TaskGraphExecutionRepository repository,
                            ConversationRepository conversations) {
        this(tools, repository, conversations, DEFAULT_CANCELLATION_CONFIRMATION_TIMEOUT);
    }

    TaskGraphRuntime(ToolGateway tools, TaskGraphExecutionRepository repository,
                     ConversationRepository conversations, Duration cancellationConfirmationTimeout) {
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.conversations = conversations;
        this.cancellationConfirmationTimeout =
                java.util.Objects.requireNonNull(cancellationConfirmationTimeout, "cancellationConfirmationTimeout");
        if (cancellationConfirmationTimeout.isNegative() || cancellationConfirmationTimeout.isZero()
                || cancellationConfirmationTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("cancellationConfirmationTimeout must be 1ns..10s");
        }
        HashSet<String> executable = new HashSet<>(TaskGraphExecutor.EXECUTABLE_NODE_TYPES);
        if (conversations == null) executable.remove("ask_user");
        this.executableNodeTypes = Set.copyOf(executable);
        this.workers = fixedPool(2, "mcac-task-graph");
        this.parallelWorkers = fixedPool(
                TaskGraphLimits.HARD_LIMITS.maxParallelNodes(), "mcac-task-graph-parallel");
        this.timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcac-task-graph-timer");
            thread.setDaemon(false);
            return thread;
        });
        try {
            repository.waitingTimeExecutionIds().forEach(this::scheduleTimedWait);
        } catch (SQLException failure) {
            close();
            throw new IllegalStateException("TASK_GRAPH_WAIT_RECOVERY_FAILED", failure);
        }
    }

    private static ThreadPoolExecutor fixedPool(int size, String threadName) {
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(false);
            return thread;
        });
    }

    public ToolResult start(ToolContext context, ToolCall call, JsonNode graph, JsonNode inputs,
                            JsonNode provenance) {
        try {
            var definitions = ordinaryDefinitions(context);
            TaskGraphValidationResult validation = validator.validateExecutable(graph, definitions,
                    executableNodeTypes);
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
                // A terminal database row is persisted immediately before lifecycle feedback and worker
                // cleanup. Do not expose completion to awaiters until that boundary is fully observable.
                if (TERMINAL.contains(record.state())) {
                    if (!active.containsKey(record.executionId())) {
                        // appendOnce makes this a safe reconciliation point if worker cleanup became
                        // visible just before the lifecycle event's separate transaction committed.
                        notifyTerminalLifecycle(record);
                        return terminal(call, record);
                    }
                } else if (waitingReady(record)) return terminal(call, record);
                if (record.state().equals("READY") && !active.containsKey(record.executionId())) submit(context, record);
                return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_ACCEPTED",
                        inspectJson(record).put("state", "ACCEPTED"), false);
            }
            TaskGraphExecutionRecord record = repository.create(call.callId(), context, graph,
                    validation.limits(), boundedInputs, provenance);
            notifyLifecycle(record, "STARTED");
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
                    if (!TERMINAL.contains(record.state()) && !waitingReady(record)) {
                        progress.accept(new ToolResult(call.callId(), call.name(), true, "TOOL_PROGRESS",
                                inspectJson(record), false));
                    }
                }
                if (TERMINAL.contains(record.state()) || waitingReady(record)) return terminal(call, record);
                Thread.sleep(25);
            }
            cancel(context, new ToolCall("timeout-cancel-" + call.callId(), "task_graph.cancel",
                    Json.object().put("executionId", call.callId())), call.callId(), "timeout");
            return timeoutResult(context, call);
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

    public java.util.List<TaskGraphExecutionRepository.ExecutionSummary> listForManagement(
            String companionId, int limit) throws SQLException {
        return repository.listByCompanion(companionId, limit);
    }

    /** Local authenticated user control; ownership is derived from the durable execution. */
    public ToolResult controlForManagement(String companionId, String executionId, String action) {
        try {
            TaskGraphExecutionRecord record = repository.get(executionId)
                    .orElseThrow(() -> new IllegalArgumentException("task graph execution was not found"));
            if (!record.companionId().equals(companionId)) {
                throw new IllegalArgumentException("task graph execution is outside companion scope");
            }
            ToolContext owner = new ToolContext(
                    record.controllerId(), record.brainSessionId(), record.companionId());
            ToolCall call = new ToolCall("local-management-" + java.util.UUID.randomUUID(),
                    "task_graph." + action, Json.object().put("executionId", executionId));
            return switch (action) {
                case "pause" -> pause(owner, call, executionId);
                case "resume" -> resume(owner, call, executionId);
                case "cancel" -> cancel(owner, call, executionId, "LOCAL_MANAGEMENT_USER");
                default -> throw new IllegalArgumentException("task graph management action is invalid");
            };
        } catch (SQLException | IllegalArgumentException failure) {
            ToolCall call = new ToolCall("local-management-rejected", "task_graph." + action,
                    Json.object().put("executionId", executionId));
            return ToolResult.rejected(call, "TASK_GRAPH_MANAGEMENT_REJECTED", failure.getMessage());
        }
    }

    public ToolResult checkpoint(ToolContext context, ToolCall call, String executionId, String label) {
        try {
            if (label == null || label.isBlank() || label.length() > 512) {
                throw new IllegalArgumentException("label must be 1..512 characters");
            }
            for (int attempt = 0; attempt < 20; attempt++) {
                TaskGraphExecutionRecord record = owned(context, executionId);
                String externalNodeId = "external:" + call.callId();
                for (JsonNode checkpoint : record.checkpoints()) {
                    if (checkpoint.path("nodeId").asText().equals(externalNodeId)) {
                        return new ToolResult(call.callId(), call.name(), true, "OK",
                                inspectJson(record), true);
                    }
                }
                if (TERMINAL.contains(record.state())) {
                    return ToolResult.rejected(call, "TASK_GRAPH_TERMINAL",
                            "cannot append a checkpoint to a terminal execution");
                }
                if (active.containsKey(executionId)) {
                    return ToolResult.rejected(call, "TASK_GRAPH_CHECKPOINT_BUSY",
                            "execution must reach a persistent node boundary before checkpointing");
                }
                TaskGraphLimits limits = TaskGraphLimits.parse(record.limits(), new ArrayList<>());
                JsonNode event = Json.object().put("nodeId", externalNodeId)
                        .put("type", "CHECKPOINT").put("content", label)
                        .put("source", "EXTERNAL_BRAIN").put("at", Instant.now().toString());
                var checkpoints = (com.fasterxml.jackson.databind.node.ArrayNode) record.checkpoints().deepCopy();
                checkpoints.add(event.deepCopy());
                trim(checkpoints, limits.maxEvidenceEntries(), limits.maxEvidenceBytes());
                var evidence = (com.fasterxml.jackson.databind.node.ArrayNode) record.evidence().deepCopy();
                evidence.add(boundedEvidence(event, limits.maxEvidenceBytes()));
                trim(evidence, limits.maxEvidenceEntries(), limits.maxEvidenceBytes());
                try {
                    TaskGraphExecutionRecord saved = repository.save(record.executionId(), record.revision(),
                            record.state(), record.currentNodeId(), record.completedNodes(), record.toolResults(),
                            record.variables(), record.outputs(), checkpoints, evidence, record.waitingQuestion(),
                            record.result(), "EXTERNAL_CHECKPOINT");
                    return new ToolResult(call.callId(), call.name(), true, "OK",
                            inspectJson(saved), true);
                } catch (IllegalStateException stale) {
                    if (!"STALE_TASK_GRAPH_REVISION".equals(stale.getMessage())) throw stale;
                }
            }
            return ToolResult.rejected(call, "TASK_GRAPH_CHECKPOINT_CONFLICT",
                    "checkpoint could not be persisted after concurrent state changes");
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    public ToolResult pause(ToolContext context, ToolCall call, String executionId) {
        try {
            TaskGraphExecutionRecord record = owned(context, executionId);
            if (TERMINAL.contains(record.state())) return terminal(call, record);
            if (record.state().equals("WAITING") && isTimedWait(record)) {
                cancelTimedWait(executionId);
                for (int attempt = 0; attempt < 20; attempt++) {
                    record = owned(context, executionId);
                    if (record.state().equals("PAUSED")) {
                        return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_PAUSED",
                                inspectJson(record), true);
                    }
                    if (TERMINAL.contains(record.state())) return terminal(call, record);
                    if (!isTimedWait(record)) break;
                    try {
                        TaskGraphExecutionRecord paused = repository.save(record.executionId(), record.revision(),
                                "PAUSED", record.currentNodeId(), record.completedNodes(), record.toolResults(),
                                record.variables(), record.outputs(), record.checkpoints(), record.evidence(),
                                record.waitingQuestion(), record.result(), "TASK_GRAPH_PAUSED");
                        notifyLifecycle(paused, "PAUSED");
                        return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_PAUSED",
                                inspectJson(paused), true);
                    } catch (IllegalStateException stale) {
                        if (!"STALE_TASK_GRAPH_REVISION".equals(stale.getMessage())) throw stale;
                    }
                }
            }
            Running running = active.get(executionId);
            if (running == null) return ToolResult.rejected(call, "TASK_GRAPH_NOT_RUNNING", "execution is not active");
            running.control.requestPause();
            cancelActiveTool(running, "task graph pause");
            long deadline = System.nanoTime() + cancellationConfirmationTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                record = owned(context, executionId);
                if (record.state().equals("PAUSED")) {
                    return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_PAUSED",
                            inspectJson(record), true);
                }
                if (TERMINAL.contains(record.state())) {
                    return terminal(call, record);
                }
                if (record.state().equals("WAITING") && isTimedWait(record)) {
                    cancelTimedWait(executionId);
                    TaskGraphExecutionRecord paused = repository.save(record.executionId(), record.revision(),
                            "PAUSED", record.currentNodeId(), record.completedNodes(), record.toolResults(),
                            record.variables(), record.outputs(), record.checkpoints(), record.evidence(),
                            record.waitingQuestion(), record.result(), "TASK_GRAPH_PAUSED");
                    notifyLifecycle(paused, "PAUSED");
                    return new ToolResult(call.callId(), call.name(), true, "TASK_GRAPH_PAUSED",
                            inspectJson(paused), true);
                }
                Thread.sleep(5);
            }
            return new ToolResult(call.callId(), call.name(), true, "PAUSE_REQUESTED",
                    inspectJson(record).put("state", "PAUSE_REQUESTED"), true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_INTERRUPTED", "pause was interrupted");
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    public ToolResult resume(ToolContext context, ToolCall call, String executionId) {
        try {
            TaskGraphExecutionRecord record = owned(context, executionId);
            boolean recovery = record.state().equals("RECONCILIATION_REQUIRED");
            if (!record.state().equals("PAUSED") && !recovery) {
                return ToolResult.rejected(call, "TASK_GRAPH_NOT_PAUSED",
                        "only a safely paused or reconcilable execution can resume");
            }
            if (recovery) {
                record = reconcileConfirmedToolResult(record);
                RecoveryAssessment assessment = assessRecovery(record);
                if (!assessment.safe()) {
                    return ToolResult.rejected(call, assessment.code(), assessment.message());
                }
            }
            long inactiveDeadline = System.nanoTime() + cancellationConfirmationTimeout.toNanos();
            while (active.containsKey(executionId) && System.nanoTime() < inactiveDeadline) {
                Thread.sleep(5);
            }
            if (active.containsKey(executionId)) {
                return ToolResult.rejected(call, "TASK_GRAPH_STILL_PAUSING",
                        "execution has not released its prior worker yet");
            }
            record = owned(context, executionId);
            if (!record.state().equals("PAUSED") && !record.state().equals("RECONCILIATION_REQUIRED")) {
                return ToolResult.rejected(call, "TASK_GRAPH_NOT_PAUSED",
                        "execution changed state before resume");
            }
            TaskGraphExecutionRecord ready = repository.save(record.executionId(), record.revision(),
                    "READY", record.currentNodeId(), record.completedNodes(), record.toolResults(),
                    record.variables(), record.outputs(), record.checkpoints(), record.evidence(), record.waitingQuestion(),
                    record.result(), recovery ? "RECOVERY_RESUME_REQUESTED" : "RESUME_REQUESTED");
            notifyLifecycle(ready, "RESUMED");
            submit(context, ready);
            return new ToolResult(call.callId(), call.name(), true,
                    recovery ? "RECOVERY_RESUME_ACCEPTED" : "RESUME_ACCEPTED",
                    inspectJson(ready).put("state", "ACCEPTED"), true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_INTERRUPTED", "resume was interrupted");
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    private TaskGraphExecutionRecord reconcileConfirmedToolResult(TaskGraphExecutionRecord record)
            throws SQLException {
        if (!Digests.sha256(Json.canonical(record.graph())).equals(record.graphHash())
                || !record.completedNodes().isArray() || !record.toolResults().isObject()
                || !record.variables().isObject() || !record.outputs().isObject()
                || !record.checkpoints().isArray() || !record.evidence().isArray()) {
            return record;
        }
        if (record.currentNodeId() == null) return record;
        JsonNode current = findNode(record.graph().path("root"), record.currentNodeId());
        if (current == null || !current.path("type").asText().equals("call_tool")) return record;
        String nodeId = current.path("id").asText();
        String nodeKey = record.variables().path("_mcac").path("currentNodeKey").asText(nodeId);
        String callId = record.executionId() + ':' + nodeKey + ":1";
        if (record.toolResults().has(callId)) return record;

        var valueContext = Json.object();
        valueContext.set("inputs", record.inputs());
        valueContext.set("variables", record.variables());
        valueContext.set("state", record.variables());
        valueContext.set("outputs", record.outputs());
        JsonNode arguments = current.has("arguments")
                ? TaskGraphValues.resolve(current.path("arguments"), valueContext) : Json.object();
        ToolContext context = new ToolContext(
                record.controllerId(), record.brainSessionId(), record.companionId());
        ToolCall toolCall = new ToolCall(callId, current.path("tool").asText(), arguments);
        ToolResult reconciled = tools.reconcile(context, toolCall).orElse(null);
        if (reconciled == null || !reconciled.terminal()
                || !reconciled.callId().equals(callId) || !reconciled.toolName().equals(toolCall.name())) {
            return record;
        }

        ObjectNode results = (ObjectNode) record.toolResults().deepCopy();
        results.set(callId, Json.object().put("nodeId", nodeId)
                .put("toolName", reconciled.toolName())
                .put("success", reconciled.success()).put("code", reconciled.code())
                .set("observation", reconciled.observation()));
        return repository.save(record.executionId(), record.revision(), record.state(),
                record.currentNodeId(), record.completedNodes(), results, record.variables(),
                record.outputs(), record.checkpoints(), record.evidence(), record.waitingQuestion(),
                record.result(), "TOOL_RESULT_RECONCILED");
    }

    public ToolResult cancel(ToolContext context, String executionId, String reason) {
        return cancel(context, new ToolCall("cancel-" + executionId, "task_graph.cancel",
                Json.object().put("executionId", executionId)), executionId, reason);
    }

    public ToolResult cancel(ToolContext context, ToolCall call, String executionId, String reason) {
        ToolCall request = call == null ? new ToolCall("cancel-" + executionId, "task_graph.cancel",
                Json.object().put("executionId", executionId)) : call;
        try {
            TaskGraphExecutionRecord record = owned(context, executionId);
            Running running = active.get(executionId);
            if (record.state().equals("WAITING")) {
                if (running != null) {
                    running.control.requestCancel();
                    cancelActiveTool(running, reason);
                }
                cancelTimedWait(executionId);
                for (int attempt = 0; attempt < 20; attempt++) {
                    record = owned(context, executionId);
                    if (record.state().equals("CANCELLED")) {
                        return new ToolResult(request.callId(), request.name(), true, "TASK_GRAPH_CANCELLED",
                                inspectJson(record), true);
                    }
                    if (TERMINAL.contains(record.state())) return terminal(request, record);
                    if (!record.state().equals("WAITING")) break;
                    JsonNode cancelledQuestion = cancelWaitingQuestion(record, reason);
                    try {
                        TaskGraphExecutionRecord cancelled = repository.save(record.executionId(), record.revision(),
                                "CANCELLED", null, record.completedNodes(), record.toolResults(), record.variables(),
                                record.outputs(), record.checkpoints(), record.evidence(), cancelledQuestion,
                                record.result(), "TASK_GRAPH_CANCELLED");
                        notifyLifecycle(cancelled, "CANCELLED");
                        return new ToolResult(request.callId(), request.name(), true, "TASK_GRAPH_CANCELLED",
                                inspectJson(cancelled), true);
                    } catch (IllegalStateException stale) {
                        if (!"STALE_TASK_GRAPH_REVISION".equals(stale.getMessage())) throw stale;
                    }
                }
            }
            if (running != null) {
                running.control.requestCancel();
                cancelActiveTool(running, reason);
                return new ToolResult(request.callId(), request.name(), true, "CANCEL_REQUESTED",
                        inspectJson(record).put("state", "CANCEL_REQUESTED"), true);
            }
            if (record.state().equals("PAUSED")) {
                JsonNode cancelledQuestion = cancelWaitingQuestion(record, reason);
                TaskGraphExecutionRecord cancelled = repository.save(record.executionId(), record.revision(),
                        "CANCELLED", null, record.completedNodes(), record.toolResults(), record.variables(),
                        record.outputs(), record.checkpoints(), record.evidence(), cancelledQuestion, record.result(),
                        "TASK_GRAPH_CANCELLED");
                notifyLifecycle(cancelled, "CANCELLED");
                return new ToolResult(request.callId(), request.name(), true, "TASK_GRAPH_CANCELLED",
                        inspectJson(cancelled), true);
            }
            return terminal(request, record);
        } catch (SQLException | IllegalArgumentException failure) {
            return ToolResult.rejected(request, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    private ToolResult timeoutResult(ToolContext context, ToolCall call)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + cancellationConfirmationTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            TaskGraphExecutionRecord record = owned(context, call.callId());
            ToolResult observed = timeoutTerminal(call, record);
            if (observed != null) return observed;
            Thread.sleep(25);
        }
        TaskGraphExecutionRecord reconciled = markCancellationUnconfirmed(context, call.callId());
        ToolResult observed = timeoutTerminal(call, reconciled);
        if (observed != null) return observed;
        return new ToolResult(call.callId(), call.name(), false,
                "TOOL_TIMEOUT_RECONCILIATION_REQUIRED",
                inspectJson(reconciled).put("timedOut", true).put("cancellationConfirmed", false), true);
    }

    private static ToolResult timeoutTerminal(ToolCall call, TaskGraphExecutionRecord record) {
        if (record.state().equals("CANCELLED")) {
            return new ToolResult(call.callId(), call.name(), false, "TOOL_TIMEOUT_CANCELLED",
                    inspectJson(record).put("timedOut", true).put("cancellationConfirmed", true), true);
        }
        if (record.state().equals("RECONCILIATION_REQUIRED")) {
            return new ToolResult(call.callId(), call.name(), false,
                    "TOOL_TIMEOUT_RECONCILIATION_REQUIRED",
                    inspectJson(record).put("timedOut", true).put("cancellationConfirmed", false), true);
        }
        if (TERMINAL.contains(record.state())) return terminal(call, record);
        return null;
    }

    private TaskGraphExecutionRecord markCancellationUnconfirmed(ToolContext context, String executionId)
            throws SQLException, InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            TaskGraphExecutionRecord record = owned(context, executionId);
            if (TERMINAL.contains(record.state())) return record;
            try {
                TaskGraphExecutionRecord failed = repository.save(record.executionId(), record.revision(),
                        "RECONCILIATION_REQUIRED", record.currentNodeId(), record.completedNodes(),
                        record.toolResults(), record.variables(), record.outputs(), record.checkpoints(),
                        record.evidence(), record.waitingQuestion(),
                        Json.object().put("message", "timeout cancellation was not confirmed"),
                        "TOOL_TIMEOUT_RECONCILIATION_REQUIRED");
                notifyLifecycle(failed, "FAILED");
                return failed;
            } catch (IllegalStateException stale) {
                if (!"STALE_TASK_GRAPH_REVISION".equals(stale.getMessage())) throw stale;
                Thread.sleep(5);
            }
        }
        throw new SQLException("Unable to persist Task Graph cancellation reconciliation state");
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
                TaskGraphExecutionResult result = new TaskGraphExecutor(
                        tools, validator, executableNodeTypes, parallelWorkers)
                        .execute(record.executionId(), context, record.graph(), record.inputs(),
                        record, control, snapshot -> {
                            try {
                                TaskGraphExecutionRecord saved = repository.save(record.executionId(),
                                        revision.get(), snapshot.state(), snapshot.currentNodeId(),
                                        snapshot.completedNodes(), snapshot.toolResults(), snapshot.variables(),
                                        snapshot.outputs(), snapshot.checkpoints(), snapshot.evidence(),
                                        snapshot.waitingQuestion(),
                                        snapshot.result(), snapshot.resultCode());
                                revision.set(saved.revision());
                                notifyTerminalLifecycle(saved);
                            } catch (SQLException failure) {
                                throw new IllegalStateException("TASK_GRAPH_PERSISTENCE_ERROR", failure);
                            }
                        });
                if (result.state().equals("WAITING")) {
                    active.remove(record.executionId(), running);
                    TaskGraphExecutionRecord waiting = repository.get(record.executionId()).orElseThrow();
                    if (TERMINAL.contains(waiting.state())) return;
                    if (control.cancelRequested() || control.pauseRequested()) {
                        String state = control.cancelRequested() ? "CANCELLED" : "PAUSED";
                        TaskGraphExecutionRecord stopped = repository.save(waiting.executionId(), waiting.revision(), state,
                                waiting.currentNodeId(), waiting.completedNodes(), waiting.toolResults(),
                                waiting.variables(), waiting.outputs(), waiting.checkpoints(), waiting.evidence(),
                                waiting.waitingQuestion(), waiting.result(),
                                control.cancelRequested() ? "TASK_GRAPH_CANCELLED" : "TASK_GRAPH_PAUSED");
                        notifyLifecycle(stopped, state);
                    } else if (result.code().equals("TASK_GRAPH_WAITING_TIME") || isTimedWait(waiting)) {
                        // The executor outcome is authoritative for this just-persisted boundary. During
                        // pause/resume, control can race the follow-up repository read; never reinterpret
                        // a TIME wait as ASK_USER merely because that read observed an adjacent revision.
                        scheduleTimedWait(record.executionId());
                    } else {
                        materializeWaitingQuestion(record.executionId());
                    }
                }
            } catch (SQLException failure) {
                reconcileWorkerFailure(record.executionId(),
                        new IllegalStateException("TASK_GRAPH_WAIT_PERSISTENCE_ERROR", failure));
            } catch (RuntimeException failure) {
                reconcileWorkerFailure(record.executionId(), failure);
            } finally {
                active.remove(record.executionId(), running);
            }
        });
    }

    public boolean handles(WaitingQuestion question) {
        return question != null && question.taskGraphExecutionId() != null;
    }

    public ToolResult answer(WaitingQuestion question, IncomingMessageResolution answer) {
        ToolCall call = new ToolCall("answer-" + (question == null ? "unknown" : question.questionId()),
                "task_graph.answer", Json.object());
        try {
            if (!handles(question)) throw new IllegalArgumentException("question is not owned by Task Graph Runtime");
            String executionId = question.taskGraphExecutionId();
            TaskGraphExecutionRecord record = repository.get(executionId)
                    .orElseThrow(() -> new IllegalArgumentException("execution not found"));
            if (!record.state().equals("WAITING")
                    || !record.companionId().equals(question.companionId())
                    || !record.waitingQuestion().path("questionId").asText().equals(question.questionId())) {
                throw new IllegalArgumentException("waiting question does not match execution");
            }
            WaitingQuestion answered = conversations.answer(question.questionId(), answer.text(), answer.optionId());
            TaskGraphExecutionRecord ready = repository.save(executionId, record.revision(), "READY",
                    record.currentNodeId(), record.completedNodes(), record.toolResults(), record.variables(),
                    record.outputs(), record.checkpoints(), record.evidence(),
                    Json.MAPPER.valueToTree(answered), record.result(), "USER_ANSWERED");
            notifyLifecycle(ready, "RESUMED");
            ToolContext context = new ToolContext(record.controllerId(), record.brainSessionId(), record.companionId());
            submit(context, ready);
            return new ToolResult(call.callId(), call.name(), true, "RESUME_ACCEPTED",
                    inspectJson(ready).put("state", "ACCEPTED"), true);
        } catch (SQLException | IllegalArgumentException | IllegalStateException failure) {
            return ToolResult.rejected(call, "TASK_GRAPH_ANSWER_FAILED", failure.getMessage());
        }
    }

    public ToolResult cancel(WaitingQuestion question, String reason) {
        if (!handles(question)) {
            ToolCall call = new ToolCall("cancel-question", "task_graph.cancel", Json.object());
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_FAILED",
                    "question is not owned by Task Graph Runtime");
        }
        try {
            TaskGraphExecutionRecord record = repository.get(question.taskGraphExecutionId())
                    .orElseThrow(() -> new IllegalArgumentException("execution not found"));
            ToolContext context = new ToolContext(record.controllerId(), record.brainSessionId(),
                    record.companionId());
            return cancel(context, record.executionId(), reason);
        } catch (SQLException | IllegalArgumentException failure) {
            ToolCall call = new ToolCall("cancel-" + question.taskGraphExecutionId(),
                    "task_graph.cancel", Json.object());
            return ToolResult.rejected(call, "TASK_GRAPH_CONTROL_FAILED", failure.getMessage());
        }
    }

    public Set<String> executableNodeTypes() {
        return executableNodeTypes;
    }

    /** Aggregate-only operational telemetry for the authenticated local health endpoint. */
    public ObjectNode telemetry() {
        int activeToolCalls = active.values().stream()
                .mapToInt(value -> value.control.activeCallIds().size()).sum();
        ObjectNode telemetry = Json.object()
                .put("status", "READY")
                .put("activeExecutions", active.size())
                .put("activeToolCalls", activeToolCalls)
                .put("timedWaits", timedWaits.size())
                .put("workerActive", workers.getActiveCount())
                .put("workerPoolSize", workers.getPoolSize())
                .put("workerQueueDepth", workers.getQueue().size())
                .put("parallelActive", parallelWorkers.getActiveCount())
                .put("parallelPoolSize", parallelWorkers.getPoolSize())
                .put("parallelQueueDepth", parallelWorkers.getQueue().size());
        try {
            telemetry.set("durable", repository.telemetry());
        } catch (SQLException failure) {
            telemetry.put("status", "DEGRADED")
                    .set("durable", Json.object().put("available", false));
        }
        return telemetry;
    }

    private void materializeWaitingQuestion(String executionId) {
        if (conversations == null) throw new IllegalStateException("TASK_GRAPH_ASK_USER_UNAVAILABLE");
        try {
            TaskGraphExecutionRecord record = repository.get(executionId).orElseThrow();
            JsonNode request = record.waitingQuestion();
            if (!record.state().equals("WAITING") || request.hasNonNull("questionId")) return;
            ArrayList<ConversationOption> options = new ArrayList<>();
            JsonNode labels = request.path("options");
            if (labels.isArray()) {
                for (int index = 0; index < labels.size(); index++) {
                    String label = labels.path(index).asText();
                    options.add(new ConversationOption("option_" + (index + 1), label, ""));
                }
            }
            if (options.isEmpty()) options.add(new ConversationOption("answer", "Answer", ""));
            JsonNode context = Json.object().put("taskGraphExecutionId", executionId)
                    .put("nodeId", request.path("nodeId").asText());
            WaitingQuestion question = conversations.askTaskGraph(record.companionId(), executionId,
                    request.path("prompt").asText(),
                    "TASK_GRAPH_ASK_USER", options, request.path("freeTextAllowed").asBoolean(),
                    context, null);
            if (!executionId.equals(question.taskGraphExecutionId())) {
                throw new IllegalStateException("TASK_GRAPH_ALREADY_HAS_ANOTHER_WAITING_QUESTION");
            }
            repository.save(executionId, record.revision(), "WAITING", record.currentNodeId(),
                    record.completedNodes(), record.toolResults(), record.variables(), record.outputs(),
                    record.checkpoints(), record.evidence(), Json.MAPPER.valueToTree(question),
                    record.result(), "TASK_GRAPH_WAITING_USER");
        } catch (SQLException failure) {
            throw new IllegalStateException("TASK_GRAPH_QUESTION_PERSISTENCE_ERROR", failure);
        }
    }

    private JsonNode cancelWaitingQuestion(TaskGraphExecutionRecord record, String reason) {
        if (conversations == null || !record.waitingQuestion().hasNonNull("questionId")) {
            return record.waitingQuestion();
        }
        try {
            return Json.MAPPER.valueToTree(
                    conversations.cancel(record.waitingQuestion().path("questionId").asText(), reason));
        } catch (SQLException | IllegalStateException ignored) {
            // Cancellation of the execution remains authoritative; a stale outbox question is still
            // bounded by its execution binding and cannot resume a cancelled graph.
            return record.waitingQuestion();
        }
    }

    private static boolean isTimedWait(TaskGraphExecutionRecord record) {
        return record.state().equals("WAITING")
                && record.waitingQuestion().path("kind").asText().equals("TIME")
                && record.waitingQuestion().path("wakeAtEpochMillis").canConvertToLong();
    }

    private void scheduleTimedWait(String executionId) {
        try {
            TaskGraphExecutionRecord record = repository.get(executionId).orElse(null);
            if (record == null || !isTimedWait(record)) return;
            long wakeAt = record.waitingQuestion().path("wakeAtEpochMillis").asLong();
            long delay = Math.max(0L, wakeAt - System.currentTimeMillis());
            ScheduledFuture<?> scheduled = timers.schedule(
                    () -> resumeTimedWait(executionId, wakeAt), delay, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> prior = timedWaits.put(executionId, scheduled);
            if (prior != null) prior.cancel(false);
        } catch (SQLException failure) {
            reconcileWorkerFailure(executionId,
                    new IllegalStateException("TASK_GRAPH_WAIT_PERSISTENCE_ERROR", failure));
        }
    }

    private void resumeTimedWait(String executionId, long expectedWakeAt) {
        timedWaits.remove(executionId);
        try {
            TaskGraphExecutionRecord record = repository.get(executionId).orElse(null);
            if (record == null || !isTimedWait(record)
                    || record.waitingQuestion().path("wakeAtEpochMillis").asLong() != expectedWakeAt) {
                return;
            }
            TaskGraphExecutionRecord ready = repository.save(executionId, record.revision(), "READY",
                    record.currentNodeId(), record.completedNodes(), record.toolResults(), record.variables(),
                    record.outputs(), record.checkpoints(), record.evidence(), record.waitingQuestion(),
                    record.result(), "TIME_WAIT_ELAPSED");
            ToolContext context = new ToolContext(
                    record.controllerId(), record.brainSessionId(), record.companionId());
            submit(context, ready);
        } catch (SQLException | IllegalArgumentException | IllegalStateException failure) {
            reconcileWorkerFailure(executionId,
                    failure instanceof RuntimeException runtime ? runtime
                            : new IllegalStateException("TASK_GRAPH_WAIT_RESUME_FAILED", failure));
        }
    }

    private void cancelTimedWait(String executionId) {
        ScheduledFuture<?> scheduled = timedWaits.remove(executionId);
        if (scheduled != null) scheduled.cancel(false);
    }

    private static boolean waitingReady(TaskGraphExecutionRecord record) {
        return record.state().equals("WAITING") && record.waitingQuestion().hasNonNull("questionId");
    }

    private void reconcileWorkerFailure(String executionId, RuntimeException failure) {
        try {
            TaskGraphExecutionRecord latest = repository.get(executionId).orElse(null);
            if (latest == null || TERMINAL.contains(latest.state())) return;
            String message = failure.getMessage();
            if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
            if (message.length() > 1_024) message = message.substring(0, 1_024);
            TaskGraphExecutionRecord failed = repository.save(executionId, latest.revision(), "RECONCILIATION_REQUIRED",
                    latest.currentNodeId(), latest.completedNodes(), latest.toolResults(), latest.variables(),
                    latest.outputs(), latest.checkpoints(), latest.evidence(), latest.waitingQuestion(),
                    Json.object().put("message", message), "TASK_GRAPH_WORKER_FAILED");
            notifyLifecycle(failed, "FAILED");
        } catch (SQLException | RuntimeException ignored) {
            // The original failure remains observable through Runtime logs/health. If persistence itself
            // is unavailable, fabricating a durable terminal state would be unsafe.
        }
    }

    private void notifyTerminalLifecycle(TaskGraphExecutionRecord record) {
        switch (record.state()) {
            case "SUCCEEDED" -> notifyLifecycle(record, "SUCCEEDED");
            case "FAILED", "RECONCILIATION_REQUIRED" -> notifyLifecycle(record, "FAILED");
            case "CANCELLED" -> notifyLifecycle(record, "CANCELLED");
            case "PAUSED" -> notifyLifecycle(record, "PAUSED");
            default -> { }
        }
    }

    private void notifyLifecycle(TaskGraphExecutionRecord record, String transition) {
        if (conversations == null) return;
        String reasonCode = boundedReasonCode(record);
        String message = switch (transition) {
            case "STARTED" -> "Task started.";
            case "PAUSED" -> "Task paused. Use the Terminal to resume or cancel.";
            case "RESUMED" -> "Task resumed.";
            case "CANCELLED" -> "Task cancelled.";
            case "SUCCEEDED" -> "Task completed.";
            case "FAILED" -> "Task cannot continue (" + reasonCode + ").";
            default -> throw new IllegalArgumentException("unknown lifecycle transition");
        };
        String identity = record.executionId() + ':' + record.revision() + ':' + transition;
        String eventId = "task-graph-" + Digests.sha256(identity).substring(0, 32);
        JsonNode details = Json.object().put("source", "TASK_GRAPH_RUNTIME")
                .put("executionId", record.executionId()).put("state", record.state())
                .put("transition", transition).put("reasonCode", reasonCode);
        try {
            conversations.appendOnce(eventId, record.companionId(), null, null,
                    "ASSISTANT", "TASK_GRAPH_LIFECYCLE", message, details);
        } catch (SQLException | RuntimeException failure) {
            LOGGER.warn("Unable to enqueue Task Graph lifecycle feedback: execution={} transition={}",
                    record.executionId(), transition, failure);
        }
    }

    private static String boundedReasonCode(TaskGraphExecutionRecord record) {
        String code = record.resultCode();
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) return record.state();
        return code;
    }

    private Map<String, ToolDefinition> ordinaryDefinitions(ToolContext context) {
        return tools.definitions(context).stream().filter(TaskGraphRuntime::isGraphCallable)
                .collect(java.util.stream.Collectors.toMap(ToolDefinition::name, value -> value));
    }

    private RecoveryAssessment assessRecovery(TaskGraphExecutionRecord record) {
        String actualHash = Digests.sha256(Json.canonical(record.graph()));
        if (!actualHash.equals(record.graphHash())) {
            return RecoveryAssessment.rejected("TASK_GRAPH_RECOVERY_CORRUPT",
                    "graph hash does not match persisted execution");
        }
        if (!record.completedNodes().isArray() || !record.toolResults().isObject()
                || !record.variables().isObject() || !record.outputs().isObject()
                || !record.checkpoints().isArray() || !record.evidence().isArray()) {
            return RecoveryAssessment.rejected("TASK_GRAPH_RECOVERY_CORRUPT",
                    "persisted graph state has an invalid shape");
        }
        ToolContext context = new ToolContext(
                record.controllerId(), record.brainSessionId(), record.companionId());
        Map<String, ToolDefinition> definitions = tools.definitions(context).stream()
                .filter(TaskGraphRuntime::isGraphCallable)
                .collect(java.util.stream.Collectors.toMap(ToolDefinition::name, value -> value));
        TaskGraphValidationResult validation =
                validator.validateExecutable(record.graph(), definitions, executableNodeTypes);
        if (!validation.valid()) {
            return RecoveryAssessment.rejected("TASK_GRAPH_RECOVERY_VALIDATION_FAILED",
                    "graph or Tool availability changed since the interrupted execution");
        }
        JsonNode current = record.currentNodeId() == null
                ? null : findNode(record.graph().path("root"), record.currentNodeId());
        if (record.currentNodeId() != null && current == null) {
            return RecoveryAssessment.rejected("TASK_GRAPH_RECOVERY_CORRUPT",
                    "current node is absent from the persisted graph");
        }
        if (containsNodeType(record.graph().path("root"), "parallel")) {
            RecoveryAssessment parallel = allEffectsIdempotent(record.graph().path("root"), definitions);
            if (!parallel.safe()) return parallel;
        } else if (current != null) {
            String tool = current.path("type").asText().equals("read_memory")
                    ? "memory.search"
                    : current.path("type").asText().equals("call_tool")
                    ? current.path("tool").asText() : null;
            if (tool != null) {
                ToolDefinition definition = definitions.get(tool);
                if (!hasConfirmedCurrentToolResult(record, current.path("id").asText(), tool)
                        && (definition == null || !definition.idempotent())) {
                    return RecoveryAssessment.rejected("TASK_GRAPH_RECOVERY_UNCONFIRMED_EFFECT",
                            "interrupted Tool is not currently available as idempotent");
                }
            }
        }
        return RecoveryAssessment.recoverable();
    }

    private static RecoveryAssessment allEffectsIdempotent(
            JsonNode value, Map<String, ToolDefinition> definitions) {
        String type = value.path("type").asText();
        String tool = type.equals("read_memory") ? "memory.search"
                : type.equals("call_tool") ? value.path("tool").asText() : null;
        if (tool != null) {
            ToolDefinition definition = definitions.get(tool);
            if (definition == null || !definition.idempotent()) {
                return RecoveryAssessment.rejected("TASK_GRAPH_RECOVERY_UNCONFIRMED_EFFECT",
                        "parallel graph contains a Tool that is not currently available as idempotent");
            }
        }
        for (JsonNode child : nodeChildren(value)) {
            RecoveryAssessment result = allEffectsIdempotent(child, definitions);
            if (!result.safe()) return result;
        }
        return RecoveryAssessment.recoverable();
    }

    private static boolean hasConfirmedCurrentToolResult(
            TaskGraphExecutionRecord record, String nodeId, String toolName) {
        String nodeKey = record.variables().path("_mcac").path("currentNodeKey").asText();
        if (nodeKey.isBlank()) nodeKey = nodeId;
        // Non-idempotent Tools cannot be wrapped in automatic retry, so their only valid
        // persisted attempt at this recovery boundary is attempt one.
        String exactCallId = record.executionId() + ':' + nodeKey + ":1";
        var results = record.toolResults().fields();
        while (results.hasNext()) {
            var entry = results.next();
            JsonNode value = entry.getValue();
            if (entry.getKey().equals(exactCallId)
                    && nodeId.equals(value.path("nodeId").asText())
                    && toolName.equals(value.path("toolName").asText())
                    && value.path("success").isBoolean() && value.path("code").isTextual()
                    && value.has("observation")) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode findNode(JsonNode value, String nodeId) {
        if (nodeId.equals(value.path("id").asText(null)) && value.hasNonNull("type")) return value;
        for (JsonNode child : nodeChildren(value)) {
            JsonNode found = findNode(child, nodeId);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean containsNodeType(JsonNode value, String nodeType) {
        if (nodeType.equals(value.path("type").asText())) return true;
        for (JsonNode child : nodeChildren(value)) {
            if (containsNodeType(child, nodeType)) return true;
        }
        return false;
    }

    private static List<JsonNode> nodeChildren(JsonNode node) {
        ArrayList<JsonNode> children = new ArrayList<>();
        switch (node.path("type").asText()) {
            case "sequence", "fallback", "parallel" ->
                    node.path("nodes").forEach(children::add);
            case "retry" -> children.add(node.path("node"));
            case "if" -> {
                children.add(node.path("then"));
                if (node.has("else")) children.add(node.path("else"));
            }
            case "switch" -> {
                node.path("cases").forEach(value -> children.add(value.path("node")));
                if (node.has("default")) children.add(node.path("default"));
            }
            case "repeat", "while" -> children.add(node.path("body"));
            default -> {
            }
        }
        return children;
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

    private static boolean isGraphCallable(ToolDefinition definition) {
        return !definition.name().startsWith("task_graph.") && !definition.name().startsWith("task.");
    }

    private static JsonNode boundedEvidence(JsonNode value, int maxBytes) {
        int bytes = serializedBytes(value);
        if (bytes <= maxBytes) return value.deepCopy();
        return Json.object().put("code", "EVIDENCE_ENTRY_OVERSIZED")
                .put("originalBytes", bytes)
                .put("sha256", Digests.sha256(Json.canonical(value)));
    }

    private static void trim(com.fasterxml.jackson.databind.node.ArrayNode values,
                             int maxEntries, int maxBytes) {
        int bytes = serializedBytes(values);
        while (values.size() > maxEntries || bytes > maxBytes) {
            if (values.isEmpty()) break;
            values.remove(0);
            bytes = serializedBytes(values);
        }
    }

    private static int serializedBytes(JsonNode value) {
        return Json.write(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private void cancelActiveTool(Running running, String reason) {
        running.control.activeCallIds().forEach(callId -> tools.cancel(running.context, callId, reason));
    }

    private static ToolResult terminal(ToolCall call, TaskGraphExecutionRecord record) {
        boolean success = record.state().equals("SUCCEEDED") || record.state().equals("WAITING");
        String code = record.state().equals("SUCCEEDED") ? "OK" : record.resultCode();
        return new ToolResult(call.callId(), call.name(), success, code, inspectJson(record), true);
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode inspectJson(TaskGraphExecutionRecord record) {
        var result = Json.object().put("executionId", record.executionId()).put("graphId", record.graphId())
                .put("graphVersion", record.graphVersion()).put("graphHash", record.graphHash())
                .put("state", record.state()).put("currentNodeId",
                        record.currentNodeId() == null ? "" : record.currentNodeId())
                .put("revision", record.revision()).put("resultCode", record.resultCode());
        result.set("completedNodes", record.completedNodes());
        result.set("outputs", record.outputs());
        result.set("variables", record.variables());
        result.set("checkpoints", record.checkpoints());
        result.set("evidence", record.evidence());
        result.set("waitingQuestion", record.waitingQuestion());
        result.set("value", record.result());
        return result;
    }

    @Override public void close() {
        active.forEach((executionId, value) -> {
            if (!persistedWaiting(executionId)) {
                value.control.requestCancel();
                cancelActiveTool(value, "runtime shutdown");
            }
        });
        workers.shutdown();
        parallelWorkers.shutdownNow();
        boolean workersStopped = false;
        boolean parallelStopped = false;
        boolean timersStopped = false;
        boolean interrupted = false;
        try {
            workersStopped = workers.awaitTermination(5, TimeUnit.SECONDS);
            if (!workersStopped) {
                workers.shutdownNow();
                workersStopped = workers.awaitTermination(1, TimeUnit.SECONDS);
            }
            parallelStopped = parallelWorkers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
            workers.shutdownNow();
            parallelWorkers.shutdownNow();
        } finally {
            timedWaits.values().forEach(value -> value.cancel(false));
            timedWaits.clear();
            timers.shutdownNow();
            if (!interrupted) {
                try {
                    timersStopped = timers.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!workersStopped || !parallelStopped || !timersStopped) {
            throw new IllegalStateException("Task Graph workers did not terminate");
        }
    }

    private boolean persistedWaiting(String executionId) {
        try {
            return repository.get(executionId).map(record -> record.state().equals("WAITING")).orElse(false);
        } catch (SQLException failure) {
            return false;
        }
    }

    private record Running(ToolContext context, TaskGraphExecutionControl control) { }
    private record RecoveryAssessment(boolean safe, String code, String message) {
        private static RecoveryAssessment recoverable() {
            return new RecoveryAssessment(true, "OK", "");
        }

        private static RecoveryAssessment rejected(String code, String message) {
            return new RecoveryAssessment(false, code, message);
        }
    }
}
