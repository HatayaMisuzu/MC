package com.mccompanion.runtime.health;

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
import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
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
import java.util.concurrent.Executors;

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
    private final IncomingMessageClassifier incomingMessages = new IncomingMessageClassifier();
    private final RuntimeLog log;
    private final Instant startedAt;
    private final HttpServer server;

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
        this.log = log;
        this.startedAt = Clock.systemUTC().instant();
        server = HttpServer.create(new InetSocketAddress(config.server.bind, config.server.managementPort), 8);
        server.createContext("/health", this::health);
        server.createContext("/commands", this::commands);
        server.createContext("/agent", this::agent);
        server.createContext("/tasks", this::tasks);
        server.createContext("/conversations", this::conversations);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-runtime-health");
            thread.setDaemon(true);
            return thread;
        }));
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
                var visible = capabilityVisibility.resolve(
                        sessions.forCompanion(companionId).map(value -> value.handshake()).orElse(null),
                        companion.status());
                AgentContext context = new AgentContext(companionId, companion.status(), java.util.List.of(),
                        active.<com.fasterxml.jackson.databind.JsonNode>map(Json.MAPPER::valueToTree).orElseGet(Json::object),
                        strings(request.path("knownLandmarks"), 64), visible.availableNames(), 5);
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
    }
}
