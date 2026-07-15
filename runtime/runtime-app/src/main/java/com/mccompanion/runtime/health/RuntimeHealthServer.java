package com.mccompanion.runtime.health;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.config.RuntimeConfig;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.AgentKernel;
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
        this.log = log;
        this.startedAt = Clock.systemUTC().instant();
        server = HttpServer.create(new InetSocketAddress(config.server.bind, config.server.managementPort), 8);
        server.createContext("/health", this::health);
        server.createContext("/commands", this::commands);
        server.createContext("/agent", this::agent);
        server.createContext("/tasks", this::tasks);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-runtime-health");
            thread.setDaemon(true);
            return thread;
        }));
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
                if (result.accepted() && result.executableIntent().isPresent() && request.path("execute").asBoolean(true)) {
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

    @Override
    public void close() {
        server.stop(0);
    }
}
