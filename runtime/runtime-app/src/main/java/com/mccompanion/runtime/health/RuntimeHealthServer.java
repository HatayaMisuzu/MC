package com.mccompanion.runtime.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.config.RuntimeConfig;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.AgentKernel;
import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.agent.StepState;
import com.mccompanion.runtime.brain.ExternalBrainCoordinator;
import com.mccompanion.runtime.brain.BrainTurnResult;
import com.mccompanion.runtime.brain.BrainAuditRepository;
import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.memory.MemoryKind;
import com.mccompanion.runtime.security.PairingTokenStore;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.provider.ProviderRouter;
import com.mccompanion.runtime.websocket.RuntimeWebSocketServer;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.conversation.IncomingMessageClassifier;
import com.mccompanion.runtime.conversation.IncomingMessageKind;
import com.mccompanion.runtime.task.TaskType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Loopback-only authenticated management endpoint used to prove Runtime identity and readiness. */
public final class RuntimeHealthServer implements AutoCloseable {
    private final RuntimeConfig config;
    private final String pairingToken;
    private final SessionRegistry sessions;
    private final CommandService commands;
    private final CompanionRepository companions;
    private final AgentPlanRepository plans;
    private final AgentKernel kernel;
    private final ProviderRouter providers;
    private final CapabilityVisibility capabilityVisibility;
    private final ConversationService conversations;
    private final MemoryRepository memories;
    private final ExternalBrainCoordinator externalBrain;
    private final BrainAuditRepository brainAudit;
    private final IncomingMessageClassifier incomingMessages = new IncomingMessageClassifier();
    private final RuntimeLog log;
    private final Instant startedAt;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ExecutorService planningExecutor;

    public RuntimeHealthServer(
            RuntimeConfig config,
            String pairingToken,
            SessionRegistry sessions,
            CommandService commands,
            CompanionRepository companions,
            AgentPlanRepository plans,
            AgentKernel kernel,
            ProviderRouter providers,
            CapabilityVisibility capabilityVisibility,
            ConversationService conversations,
            MemoryRepository memories,
            ExternalBrainCoordinator externalBrain,
            BrainAuditRepository brainAudit,
            RuntimeLog log) throws IOException {
        this.config = config;
        this.pairingToken = pairingToken;
        this.sessions = sessions;
        this.commands = commands;
        this.companions = companions;
        this.plans = plans;
        this.kernel = kernel;
        this.providers = providers;
        this.capabilityVisibility = capabilityVisibility;
        this.conversations = conversations;
        this.memories = memories;
        this.externalBrain = externalBrain;
        this.brainAudit = brainAudit;
        this.log = log;
        this.startedAt = Clock.systemUTC().instant();
        server = HttpServer.create(new InetSocketAddress(config.server.bind, config.server.managementPort), 8);
        server.createContext("/health", this::health);
        server.createContext("/commands", this::commands);
        server.createContext("/agent", exchange -> dispatchPlanning(exchange, this::agent));
        server.createContext("/brain", this::brainDispatch);
        server.createContext("/tasks", this::tasks);
        server.createContext("/plans", this::plans);
        server.createContext("/conversations", this::conversations);
        server.createContext("/memories", this::memoryManagement);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-runtime-management");
            thread.setDaemon(true);
            return thread;
        });
        planningExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-runtime-planning");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
    }

    private void brainDispatch(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod()) && "/brain".equals(exchange.getRequestURI().getPath())) {
            dispatchPlanning(exchange, this::brain);
        } else {
            brain(exchange);
        }
    }

    private void dispatchPlanning(HttpExchange exchange, ExchangeHandler handler) throws IOException {
        try {
            planningExecutor.execute(() -> {
                try {
                    handler.handle(exchange);
                } catch (IOException failure) {
                    log.error("Management planning request failed", failure);
                    exchange.close();
                }
            });
        } catch (RejectedExecutionException rejected) {
            try (exchange) {
                sendJson(exchange, 503, Json.object().put("code", "RUNTIME_STOPPING"));
            }
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void memoryManagement(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authenticated(exchange)) return;
            try {
                String companionId = queryParameter(exchange, "companionId");
                if (companionId == null || companionId.isBlank()) {
                    sendJson(exchange, 400, Json.object().put("code", "COMPANION_ID_REQUIRED")); return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    ObjectNode body = Json.object().put("companionId", companionId);
                    String search = queryParameter(exchange, "query");
                    if (search != null && !search.isBlank()) {
                        body.set("memories", Json.MAPPER.valueToTree(memories.search(companionId, search, 100)));
                    } else {
                        String kindValue = queryParameter(exchange, "kind");
                        if (kindValue != null && !kindValue.isBlank()) {
                            body.set("memories", Json.MAPPER.valueToTree(memories.list(companionId,
                                    MemoryKind.valueOf(kindValue.toUpperCase(java.util.Locale.ROOT)), 100)));
                        } else {
                            ObjectNode byKind = body.putObject("byKind");
                            for (MemoryKind kind : MemoryKind.values()) byKind.set(kind.name(),
                                    Json.MAPPER.valueToTree(memories.list(companionId, kind, 100)));
                        }
                    }
                    sendJson(exchange, 200, body); return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
                    MemoryKind kind = MemoryKind.valueOf(required(request, "kind").toUpperCase(java.util.Locale.ROOT));
                    String key = requiredText(request, "key", 256);
                    JsonNode value = request.path("value");
                    if (value.isMissingNode() || Json.write(value).length() > 16_384) {
                        throw new IllegalArgumentException("memory value is required and bounded");
                    }
                    long ttlSeconds = request.path("ttlSeconds").asLong(0);
                    if (ttlSeconds < 0 || ttlSeconds > 31_536_000L) throw new IllegalArgumentException("ttlSeconds is invalid");
                    var fact = memories.remember(companionId, kind, key, value, true, 1.0,
                            ttlSeconds == 0 ? null : java.time.Duration.ofSeconds(ttlSeconds), "USER");
                    sendJson(exchange, 200, Json.MAPPER.valueToTree(fact)); return;
                }
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    String memoryId = queryParameter(exchange, "memoryId");
                    String kindValue = queryParameter(exchange, "kind");
                    if (memoryId != null && !memoryId.isBlank()) {
                        sendJson(exchange, 200, Json.object().put("deleted", memories.delete(companionId, memoryId))); return;
                    }
                    if (kindValue != null && !kindValue.isBlank()) {
                        int deleted = memories.clear(companionId,
                                MemoryKind.valueOf(kindValue.toUpperCase(java.util.Locale.ROOT)));
                        sendJson(exchange, 200, Json.object().put("deleted", deleted)); return;
                    }
                    sendJson(exchange, 400, Json.object().put("code", "MEMORY_ID_OR_KIND_REQUIRED")); return;
                }
                exchange.sendResponseHeaders(405, -1);
            } catch (IllegalArgumentException failure) {
                sendJson(exchange, 400, Json.object().put("code", "INVALID_MEMORY_REQUEST")
                        .put("message", failure.getMessage()));
            } catch (java.sql.SQLException failure) {
                log.error("Memory management failed", failure);
                sendJson(exchange, 500, Json.object().put("code", "PERSISTENCE_ERROR"));
            }
        }
    }

    private void brain(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authenticated(exchange)) return;
            if (exchange.getRequestURI().getPath().equals("/brain/audit")) {
                if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                String companionId = queryParameter(exchange, "companionId");
                if (companionId == null || companionId.isBlank()) {
                    sendJson(exchange, 400, Json.object().put("code", "COMPANION_ID_REQUIRED")); return;
                }
                try { sendJson(exchange, 200, brainAudit.inspect(companionId, 50)); }
                catch (java.sql.SQLException failure) {
                    log.error("Unable to inspect Brain audit", failure);
                    sendJson(exchange, 500, Json.object().put("code", "PERSISTENCE_ERROR"));
                }
                return;
            }
            if (externalBrain == null) {
                sendJson(exchange, 503, Json.object().put("code", "EXTERNAL_BRAIN_UNAVAILABLE"));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                ObjectNode body = Json.object().put("activeControllerId",
                        externalBrain.activeControllerId() == null ? "" : externalBrain.activeControllerId());
                body.set("health", Json.MAPPER.valueToTree(externalBrain.health()));
                sendJson(exchange, 200, body);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
                String controllerId = required(request, "controllerId");
                String companionId = required(request, "companionId");
                String text = requiredText(request, "text", 4096);
                var companion = companions.get(companionId)
                        .orElseThrow(() -> new IllegalArgumentException("Companion is not registered"));
                var activeTask = commands.activeTaskFor(companionId);
                var activePlan = plans.activeForCompanion(companionId);
                var waiting = conversations.repository().activeForCompanion(companionId);
                var incoming = incomingMessages.classify(text, waiting.orElse(null));
                var recent = conversations.recentTranscript(companionId, 12);
                var session = sessions.forCompanion(companionId).orElse(null);
                var visible = capabilityVisibility.resolve(session == null ? null : session.handshake(), companion.status());
                JsonNode verifiedWorld = memories.enrichVerifiedWorld(companionId, companion.status());
                ObjectNode taskContext = activeTask
                        .<ObjectNode>map(value -> (ObjectNode) Json.MAPPER.valueToTree(value)).orElseGet(Json::object);
                waiting.ifPresent(value -> taskContext.set("waitingQuestion", Json.MAPPER.valueToTree(value)));
                AgentContext context = new AgentContext(companionId, verifiedWorld, recent, taskContext,
                        memories.verifiedLandmarkKeys(companionId), visible.availableNames(),
                        memories.preferenceContext(companionId, 24), 5);
                if (waiting.isPresent() && waiting.orElseThrow().brainSessionId() != null
                        && incoming.kind() == IncomingMessageKind.CONTROL) {
                    conversations.repository().cancel(waiting.orElseThrow().questionId(), "OWNER_CANCELLED");
                    externalBrain.cancel(controllerId, companionId, "OWNER_CANCELLED");
                    sendJson(exchange, 200, Json.object().put("accepted", true).put("code", "BRAIN_CANCELLED"));
                    return;
                }
                if (waiting.isPresent() && waiting.orElseThrow().brainSessionId() != null
                        && incoming.kind() == IncomingMessageKind.GOAL_MODIFICATION) {
                    conversations.repository().cancel(waiting.orElseThrow().questionId(), "GOAL_MODIFIED");
                }
                if (incoming.kind() != IncomingMessageKind.WAITING_ANSWER) {
                    conversations.hear(companionId, activePlan.map(value -> value.planId()).orElse(null),
                            "MESSAGE", text, Json.object().put("channel", "EXTERNAL_BRAIN"));
                }
                var result = waiting.isPresent() && waiting.orElseThrow().brainSessionId() != null
                        && incoming.kind() == IncomingMessageKind.WAITING_ANSWER
                        ? externalBrain.answer(controllerId, waiting.orElseThrow(), incoming, context)
                        : externalBrain.continueTurn(controllerId, companionId, text, context);
                if (result.kind() == BrainTurnResult.Kind.FINAL_RESPONSE && !result.response().isBlank()) {
                    conversations.say(companionId, activePlan.map(value -> value.planId()).orElse(null),
                            "CHAT", result.response(), Json.object().put("channel", "EXTERNAL_BRAIN")
                                    .put("brainSessionId", result.sessionId()));
                }
                ObjectNode body = Json.object().put("accepted", true).put("code", result.code());
                body.set("result", Json.MAPPER.valueToTree(result));
                body.set("capabilityStates", visible.toJson());
                sendJson(exchange, 200, body);
            } catch (IllegalArgumentException failure) {
                sendJson(exchange, 400, Json.object().put("accepted", false)
                        .put("code", "INVALID_REQUEST").put("message", failure.getMessage()));
            } catch (IllegalStateException failure) {
                int status = "BRAIN_CONTROLLER_ALREADY_ACTIVE".equals(failure.getMessage()) ? 409 : 502;
                sendJson(exchange, status, Json.object().put("accepted", false)
                        .put("code", failure.getMessage() == null ? "EXTERNAL_BRAIN_ERROR" : failure.getMessage()));
            } catch (java.sql.SQLException failure) {
                log.error("Unable to build external Brain context", failure);
                sendJson(exchange, 500, Json.object().put("accepted", false).put("code", "PERSISTENCE_ERROR"));
            }
        }
    }

    private void conversations(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authenticated(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String companionId = queryParameter(exchange, "companionId");
            if (companionId == null || companionId.isBlank()) {
                sendJson(exchange, 400, Json.object().put("code", "COMPANION_ID_REQUIRED")); return;
            }
            try {
                ObjectNode body = Json.object().put("companionId", companionId);
                body.set("events", Json.MAPPER.valueToTree(conversations.repository().list(companionId, 100)));
                conversations.repository().activeForCompanion(companionId)
                        .ifPresent(value -> body.set("waitingQuestion", Json.MAPPER.valueToTree(value)));
                sendJson(exchange, 200, body);
            } catch (java.sql.SQLException failure) {
                log.error("Unable to inspect conversations", failure);
                sendJson(exchange, 500, Json.object().put("code", "PERSISTENCE_ERROR"));
            }
        }
    }

    private void agent(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authenticated(exchange)) return;
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                var request = Json.MAPPER.readTree(exchange.getRequestBody());
                String commandId = required(request, "commandId");
                String companionId = required(request, "companionId");
                String text = requiredText(request, "text", 4096);
                var companion = companions.get(companionId)
                        .orElseThrow(() -> new IllegalArgumentException("Companion is not registered"));
                var active = commands.activeTaskFor(companionId);
                var activePlan = plans.activeForCompanion(companionId);
                var waiting = conversations.repository().activeForCompanion(companionId);
                var incoming = incomingMessages.classify(text, waiting.orElse(null));
                if (waiting.isPresent() && incoming.kind() == IncomingMessageKind.WAITING_ANSWER) {
                    var resumed = kernel.resumeWaitingAnswer(waiting.orElseThrow(), incoming);
                    ObjectNode body = Json.object().put("accepted", true).put("source", "waiting-answer")
                            .put("message", "已收到你的回答，并继续原来的任务。")
                            .put("questionId", waiting.orElseThrow().questionId())
                            .put("planId", resumed.planId()).put("planState", resumed.state().name())
                            .put("planRevision", resumed.revision()).put("currentStep", resumed.currentStep())
                            .put("resumedOriginalPlan", true);
                    sendJson(exchange, 200, body);
                    return;
                }
                var recentConversation = conversations.recentTranscript(companionId, 12);
                conversations.hear(companionId, activePlan.map(value -> value.planId()).orElse(null),
                        "MESSAGE", text, Json.object().put("channel", "HTTP"));
                var visible = capabilityVisibility.resolve(
                        sessions.forCompanion(companionId).map(value -> value.handshake()).orElse(null),
                        companion.status());
                AgentContext context = new AgentContext(companionId, companion.status(), recentConversation,
                        active.<com.fasterxml.jackson.databind.JsonNode>map(Json.MAPPER::valueToTree).orElseGet(Json::object),
                        strings(request.path("knownLandmarks"), 64), visible.availableNames(),
                        memories.preferenceContext(companionId, 24), 5);
                var result = providers.plan(text, context);
                ObjectNode body = Json.object().put("accepted", result.accepted())
                        .put("source", result.source());
                body.set("decision", Json.MAPPER.valueToTree(result.decision()));
                body.set("capabilityStates", visible.toJson());
                visible.availableNames().forEach(body.putArray("availableCapabilities")::add);
                if (result.errorCode() != null) body.put("code", result.errorCode()).put("message", result.userMessage());
                if (result.accepted() && activePlan.isPresent()
                        && (result.decision().kind() == DecisionKind.CREATE_PLAN
                        || result.decision().kind() == DecisionKind.REPLAN)
                        && !result.decision().steps().isEmpty()) {
                    AgentDecision value = result.decision();
                    AgentDecision revision = new AgentDecision(DecisionKind.REPLAN, value.understoodGoal(),
                            value.constraints(), value.assumptions(), value.steps(), value.reply(),
                            "OWNER_MODIFIED_GOAL: " + value.reason());
                    var previous = activePlan.orElseThrow();
                    var queued = plans.queueGoalModification(previous.planId(), previous.revision(), text, revision,
                            Json.object().put("ownerModifiedGoal", true)
                                    .put("previousRequest", previous.requestText()));
                    if (waiting.isPresent()) {
                        conversations.repository().cancel(waiting.orElseThrow().questionId(), "OWNER_MODIFIED_GOAL");
                    }
                    if (active.isPresent()) {
                        var cancellation = commands.execute("goal-change-" + commandId, companionId,
                                new Intent(TaskType.STOP, Json.object().put("action", "cancel"), text));
                        body.set("execution", cancellation.toJson());
                    } else {
                        int queuedStep = queued.currentStep();
                        var old = queued.steps().stream().filter(step -> step.index() != queuedStep)
                                .filter(step -> step.state() == StepState.RUNNING || step.state() == StepState.BLOCKED
                                        || step.state() == StepState.PAUSED || step.state() == StepState.CANCELLED)
                                .max(java.util.Comparator.comparingInt(candidate -> candidate.index())).orElseThrow();
                        queued = plans.activateGoalModification(queued.planId(), queued.revision(), old.index(),
                                StepState.CANCELLED, Json.object().put("noActiveBehavior", true), "GOAL_MODIFIED");
                        if (request.path("execute").asBoolean(true)) queued = kernel.start(queued.planId());
                    }
                    body.put("executionState", queued.state().name()).put("planId", queued.planId())
                            .put("planRevision", queued.revision()).put("currentStep", queued.currentStep())
                            .put("planState", queued.state().name()).put("goalModified", true);
                } else if (result.accepted() && result.executableIntent().isPresent() && request.path("execute").asBoolean(true)) {
                    body.set("execution", commands.execute(commandId, companionId, result.executableIntent().get()).toJson());
                } else if (result.accepted() && (result.decision().kind() == com.mccompanion.runtime.agent.DecisionKind.CREATE_PLAN
                        || result.decision().kind() == com.mccompanion.runtime.agent.DecisionKind.REPLAN)) {
                    var plan = plans.create(companionId, text, result.decision());
                    if (request.path("execute").asBoolean(true)) plan = kernel.start(plan.planId());
                    body.put("executionState", plan.state().name()).put("planId", plan.planId())
                            .put("planRevision", plan.revision()).put("currentStep", plan.currentStep())
                            .put("planState", plan.state().name());
                } else if (result.accepted()) {
                    body.put("executionState", "NO_ACTION");
                }
                String assistantReply = result.accepted() ? result.decision().reply() : result.userMessage();
                if (assistantReply != null && !assistantReply.isBlank()) {
                    String replyPlanId = body.path("planId").asText(null);
                    conversations.say(companionId, replyPlanId,
                            ConversationService.kindForDecision(result.decision().kind()), assistantReply,
                            Json.object().put("channel", "HTTP").put("source", result.source())
                                    .put("decision", result.decision().kind().name()));
                }
                sendJson(exchange, result.accepted() ? 200 : 422, body);
            } catch (IllegalArgumentException invalid) {
                sendJson(exchange, 400, Json.object().put("accepted", false)
                        .put("code", "INVALID_REQUEST").put("message", invalid.getMessage()));
            } catch (java.sql.SQLException failure) {
                log.error("Unable to build agent context", failure);
                sendJson(exchange, 500, Json.object().put("accepted", false).put("code", "PERSISTENCE_ERROR"));
            }
        }
    }

    private void commands(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authenticated(exchange)) return;
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                var request = Json.MAPPER.readTree(exchange.getRequestBody());
                String commandId = required(request, "commandId");
                String companionId = required(request, "companionId");
                TaskType type = TaskType.valueOf(required(request, "type"));
                var arguments = request.path("arguments").isObject() ? request.path("arguments") : Json.object();
                CommandReply reply = commands.execute(commandId, companionId,
                        new Intent(type, arguments, request.path("originalText").asText(type.name())));
                sendJson(exchange, 200, reply.toJson());
            } catch (IllegalArgumentException invalid) {
                sendJson(exchange, 400, Json.object().put("accepted", false)
                        .put("code", "INVALID_REQUEST").put("message", invalid.getMessage()));
            }
        }
    }

    private void tasks(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authenticated(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String prefix = "/tasks/";
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
                sendJson(exchange, 400, Json.object().put("code", "TASK_ID_REQUIRED"));
                return;
            }
            String taskId = path.substring(prefix.length());
            try {
                var task = commands.task(taskId);
                if (task.isEmpty()) {
                    sendJson(exchange, 404, Json.object().put("code", "TASK_NOT_FOUND"));
                    return;
                }
                ObjectNode body = Json.object();
                body.set("task", Json.MAPPER.valueToTree(task.get()));
                body.set("events", Json.MAPPER.valueToTree(commands.taskEvents(taskId)));
                var lease = commands.leaseFor(task.get().companionId());
                body.put("leaseActive", lease.isPresent());
                lease.ifPresent(value -> body.put("leaseId", value.token()).put("controlEpoch", value.epoch()));
                sendJson(exchange, 200, body);
            } catch (Exception failure) {
                log.error("Unable to inspect Runtime task", failure);
                sendJson(exchange, 500, Json.object().put("code", "PERSISTENCE_ERROR"));
            }
        }
    }

    private void plans(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authenticated(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String prefix = "/plans/";
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
                sendJson(exchange, 400, Json.object().put("code", "PLAN_ID_REQUIRED")); return;
            }
            try {
                var plan = plans.get(path.substring(prefix.length()));
                if (plan.isEmpty()) { sendJson(exchange, 404, Json.object().put("code", "PLAN_NOT_FOUND")); return; }
                ObjectNode body = Json.object();
                body.set("plan", Json.MAPPER.valueToTree(plan.orElseThrow()));
                sendJson(exchange, 200, body);
            } catch (java.sql.SQLException failure) {
                log.error("Unable to inspect agent plan", failure);
                sendJson(exchange, 500, Json.object().put("code", "PERSISTENCE_ERROR"));
            }
        }
    }

    public void start() {
        server.start();
        log.info("Runtime health endpoint listening on " + config.server.bind + ':' + config.server.managementPort);
    }

    private void health(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!authenticated(exchange)) return;
            ObjectNode body = Json.object()
                    .put("runtimeVersion", RuntimeWebSocketServer.VERSION)
                    .put("protocolVersion", RuntimeWebSocketServer.PROTOCOL)
                    .put("profileId", config.server.profileId)
                    .put("instanceId", config.server.instanceId)
                    .put("port", config.server.port)
                    .put("managementPort", config.server.managementPort)
                    .put("pid", ProcessHandle.current().pid())
                    .put("startedAt", startedAt.toString())
                    .put("databaseStatus", "READY")
                    .put("sessionCount", sessions.sessions().size())
                    .put("onlineCompanionCount", sessions.sessions().stream()
                            .mapToInt(session -> session.companionIds().size())
                            .sum());
            sendJson(exchange, 200, body);
        }
    }

    private boolean authenticated(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String candidate = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : null;
        if (PairingTokenStore.matches(pairingToken, candidate)) return true;
        exchange.sendResponseHeaders(401, -1);
        return false;
    }

    private static void sendJson(HttpExchange exchange, int status, com.fasterxml.jackson.databind.JsonNode body)
            throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String required(com.fasterxml.jackson.databind.JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank() || value.length() > 512) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String requiredText(com.fasterxml.jackson.databind.JsonNode node, String field, int maximum) {
        String value = node.path(field).asText("").strip();
        if (value.isEmpty() || value.length() > maximum) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static java.util.List<String> strings(com.fasterxml.jackson.databind.JsonNode value, int maximum) {
        if (!value.isArray()) return java.util.List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (var item : value) {
            if (result.size() >= maximum) break;
            if (item.isTextual() && !item.asText().isBlank() && item.asText().length() <= 128) result.add(item.asText());
        }
        return java.util.List.copyOf(result);
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return parts.length == 1 ? "" : java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
        planningExecutor.shutdownNow();
        executor.shutdownNow();
    }
}
