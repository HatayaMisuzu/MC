package com.mccompanion.runtime.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.mccompanion.protocol.BehaviorEvent;
import com.mccompanion.protocol.CommandAccepted;
import com.mccompanion.protocol.CompanionStatus;
import com.mccompanion.protocol.ErrorEnvelope;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.agent.AgentKernel;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.agent.StepState;
import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.task.TaskType;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.conversation.IncomingMessageClassifier;
import com.mccompanion.runtime.conversation.IncomingMessageKind;
import com.mccompanion.runtime.security.PairingTokenStore;
import com.mccompanion.runtime.session.Handshake;
import com.mccompanion.runtime.session.RuntimeSession;
import com.mccompanion.runtime.session.SessionPeer;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.provider.ProviderRouter;
import com.mccompanion.runtime.brain.ExternalBrainCoordinator;
import com.mccompanion.runtime.brain.BrainTurnResult;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import com.mccompanion.runtime.tool.RegistryToolGateway;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RuntimeWebSocketServer extends WebSocketServer implements AutoCloseable {
    public static final String PROTOCOL = "mc-companion/1";
    public static final String VERSION = "0.3.0";
    private static final int MAX_MESSAGE_CHARS = 1_048_576;
    private final String pairingToken;
    private final SessionRegistry sessions;
    private final CommandService commands;
    private final CompanionRepository companions;
    private final ProviderRouter providers;
    private final AgentPlanRepository plans;
    private final AgentKernel kernel;
    private final CapabilityVisibility capabilityVisibility;
    private final MemoryRepository memories;
    private final ConversationService conversations;
    private final ExternalBrainCoordinator externalBrain;
    private final TaskGraphRuntime taskGraphRuntime;
    private volatile RegistryToolGateway registryQueries;
    private final IncomingMessageClassifier incomingMessages = new IncomingMessageClassifier();
    private final ExecutorService planningExecutor;
    private final RuntimeLog log;
    private final Clock clock;
    private final CountDownLatch started = new CountDownLatch(1);
    private final Map<WebSocket, Peer> peers = new ConcurrentHashMap<>();
    private final Map<WebSocket, Instant> pendingSince = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> headerTokens = new ConcurrentHashMap<>();

    public RuntimeWebSocketServer(InetSocketAddress address, String pairingToken, SessionRegistry sessions,
                                  CommandService commands, CompanionRepository companions, ProviderRouter providers,
                                  AgentPlanRepository plans, AgentKernel kernel,
                                  CapabilityVisibility capabilityVisibility, MemoryRepository memories,
                                  ConversationService conversations, RuntimeLog log) {
        this(address, pairingToken, sessions, commands, companions, providers, plans, kernel,
                capabilityVisibility, memories, conversations, null, null, log, Clock.systemUTC());
    }

    public RuntimeWebSocketServer(InetSocketAddress address, String pairingToken, SessionRegistry sessions,
                                  CommandService commands, CompanionRepository companions, ProviderRouter providers,
                                  AgentPlanRepository plans, AgentKernel kernel,
                                  CapabilityVisibility capabilityVisibility, MemoryRepository memories,
                                  ConversationService conversations, ExternalBrainCoordinator externalBrain,
                                  RuntimeLog log) {
        this(address, pairingToken, sessions, commands, companions, providers, plans, kernel,
                capabilityVisibility, memories, conversations, externalBrain, null, log, Clock.systemUTC());
    }

    public RuntimeWebSocketServer(InetSocketAddress address, String pairingToken, SessionRegistry sessions,
                                  CommandService commands, CompanionRepository companions, ProviderRouter providers,
                                  AgentPlanRepository plans, AgentKernel kernel,
                                  CapabilityVisibility capabilityVisibility, MemoryRepository memories,
                                  ConversationService conversations, ExternalBrainCoordinator externalBrain,
                                  TaskGraphRuntime taskGraphRuntime, RuntimeLog log) {
        this(address, pairingToken, sessions, commands, companions, providers, plans, kernel,
                capabilityVisibility, memories, conversations, externalBrain, taskGraphRuntime,
                log, Clock.systemUTC());
    }

    RuntimeWebSocketServer(InetSocketAddress address, String pairingToken, SessionRegistry sessions,
                           CommandService commands, CompanionRepository companions, ProviderRouter providers,
                           AgentPlanRepository plans, AgentKernel kernel,
                           CapabilityVisibility capabilityVisibility, MemoryRepository memories,
                           ConversationService conversations, ExternalBrainCoordinator externalBrain,
                           TaskGraphRuntime taskGraphRuntime, RuntimeLog log, Clock clock) {
        super(address);
        this.pairingToken = pairingToken;
        this.sessions = sessions;
        this.commands = commands;
        this.companions = companions;
        this.providers = providers;
        this.plans = plans;
        this.kernel = kernel;
        this.capabilityVisibility = capabilityVisibility;
        this.memories = memories;
        this.conversations = conversations;
        this.externalBrain = externalBrain;
        this.taskGraphRuntime = taskGraphRuntime;
        this.log = log;
        this.clock = clock;
        this.planningExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-player-planner"); thread.setDaemon(true); return thread;
        });
        setConnectionLostTimeout(30);
        setReuseAddr(true);
    }

    public void startAndAwait(Duration timeout) throws InterruptedException {
        start();
        if (!started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("WebSocket server did not start in time");
        }
    }

    public void attachRegistryQueries(RegistryToolGateway gateway) {
        if (registryQueries != null) throw new IllegalStateException("Registry query gateway is already attached");
        registryQueries = java.util.Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public void onOpen(WebSocket socket, ClientHandshake handshake) {
        Peer peer = new Peer(socket);
        peers.put(socket, peer);
        pendingSince.put(socket, clock.instant());
        String authorization = handshake.getFieldValue("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            headerTokens.put(socket, authorization.substring(7).trim());
        } else {
            String token = handshake.getFieldValue("X-Companion-Token");
            if (token != null && !token.isBlank()) headerTokens.put(socket, token.trim());
        }
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
        Peer peer = peers.computeIfAbsent(socket, Peer::new);
        if (text == null || text.length() > MAX_MESSAGE_CHARS) {
            peer.close(1009, "Message too large");
            return;
        }
        JsonNode message;
        try {
            message = Json.parse(text);
            if (!message.isObject()) throw new IllegalArgumentException("JSON object required");
        } catch (IllegalArgumentException malformed) {
            sendError(peer, null, "INVALID_JSON", "Message must be valid JSON");
            return;
        }
        Optional<RuntimeSession> current = sessions.forPeer(peer);
        if (current.isEmpty()) {
            handleHandshake(socket, peer, message);
            return;
        }
        RuntimeSession session = current.get();
        String sessionId = message.path("sessionId").asText(session.sessionId());
        if (!session.sessionId().equals(sessionId)) {
            sendError(peer, session, "SESSION_MISMATCH", "sessionId does not match this connection");
            return;
        }
        long sequence = message.path("sequence").asLong(-1);
        if (!session.acceptIncomingSequence(sequence)) {
            sendError(peer, session, "STALE_SEQUENCE", "sequence must be strictly increasing");
            return;
        }
        try {
            sessions.touch(peer);
            route(session, message);
        } catch (SQLException failure) {
            log.error("Unable to persist WebSocket message for session=" + session.sessionId(), failure);
            sendError(peer, session, "PERSISTENCE_ERROR", "Runtime could not persist this message");
        } catch (RuntimeException failure) {
            log.error("Unable to process WebSocket message for session=" + session.sessionId(), failure);
            sendError(peer, session, "INVALID_MESSAGE", "Message fields were invalid");
        }
    }

    private void handleHandshake(WebSocket socket, Peer peer, JsonNode message) {
        JsonNode body = message.has("payload") && message.path("payload").isObject() ? message.path("payload") : message;
        String type = message.path("type").asText("hello");
        if (!(type.equals("hello") || type.equals("handshake"))) {
            reject(peer, "HANDSHAKE_REQUIRED", "First message must be a handshake");
            return;
        }
        String candidate = firstText(message, "token", "authToken");
        if (candidate == null) candidate = firstText(body, "token", "authToken");
        if (candidate == null) candidate = headerTokens.get(socket);
        if (!PairingTokenStore.matches(pairingToken, candidate)) {
            reject(peer, "AUTH_FAILED", "Pairing token was rejected");
            return;
        }
        String protocol = body.path("protocol").asText(message.path("protocol").asText(""));
        if (!PROTOCOL.equals(protocol)) {
            reject(peer, "UNSUPPORTED_PROTOCOL", "Runtime supports " + PROTOCOL);
            return;
        }
        try {
            Handshake handshake = new Handshake(protocol,
                    required(body, "modVersion"), required(body, "minecraftVersion"),
                    required(body, "loader"), required(body, "worldId"), body.path("capabilities"));
            RuntimeSession session = sessions.register(peer, handshake);
            pendingSince.remove(socket);
            headerTokens.remove(socket);
            ObjectNode response = Json.object().put("protocol", PROTOCOL).put("type", "hello_ack")
                    .put("accepted", true).put("sessionId", session.sessionId())
                    .put("runtimeVersion", VERSION);
            response.putObject("controlPolicy").put("singleController", true)
                    .put("leaseSeconds", CommandService.LEASE_DURATION.toSeconds())
                    .put("safeIdleOnDisconnect", true);
            peer.send(Json.write(response));
            sendSubscription(session);
            sessions.ready(session);
        } catch (IllegalArgumentException invalid) {
            reject(peer, "INVALID_HANDSHAKE", invalid.getMessage());
        } catch (SQLException failure) {
            log.error("Unable to persist WebSocket handshake", failure);
            reject(peer, "PERSISTENCE_ERROR", "Runtime could not register the session");
        }
    }

    private void route(RuntimeSession session, JsonNode message) throws SQLException {
        String type = message.path("type").asText("");
        JsonNode payload = message.path("payload");
        switch (type) {
            case "heartbeat", "ping" -> sendHeartbeatAck(session, message.path("sequence").asLong());
            case "companion_list" -> registerCompanionList(session, payload);
            case "companion_registered", "companion_status", "status_update", "status_event",
                    "reconciliation_status" -> registerCompanion(session, payload.isObject() ? payload : message);
            case "command_accepted" -> commands.onCommandAccepted(convert(payload, CommandAccepted.class));
            case "behavior_event", "event" -> commands.onBehaviorEvent(convert(payload, BehaviorEvent.class));
            case "protocol_error", "error" -> commands.onProtocolError(convert(payload, ErrorEnvelope.class));
            case "registry_result" -> {
                if (registryQueries == null || !registryQueries.complete(session, payload)) {
                    sendError(session.peer(), session, "UNKNOWN_QUERY_RESULT",
                            "Registry result has no active query binding");
                }
            }
            case "player_request" -> handlePlayerRequest(session, payload);
            case "conversation_delivery_ack" -> acknowledgeConversationDelivery(session, payload);
            case "ack", "gap_summary" -> { /* ACK/gap is intentionally non-blocking; durable task events arrive separately. */ }
            default -> sendError(session.peer(), session, "UNKNOWN_MESSAGE_TYPE", "Unsupported message type");
        }
    }

    private void acknowledgeConversationDelivery(RuntimeSession session, JsonNode payload) throws SQLException {
        String companionId = required(payload, "companionId");
        String eventId = required(payload, "eventId");
        RuntimeSession owner = sessions.forCompanion(companionId).orElse(null);
        if (owner != session) throw new IllegalArgumentException("conversation ack does not belong to this session");
        conversations.acknowledgeGameDelivery(companionId, eventId);
    }

    private void handlePlayerRequest(RuntimeSession session, JsonNode payload) {
        String requestId = required(payload, "requestId");
        String companionId = required(payload, "companionId");
        String ownerId = required(payload, "ownerId");
        String text = payload.path("text").asText("").strip();
        if (text.isEmpty() || text.length() > 512) throw new IllegalArgumentException("player request text must be 1..512 characters");
        planningExecutor.execute(() -> {
            ObjectNode reply = Json.object().put("requestId", requestId).put("companionId", companionId);
            com.mccompanion.runtime.conversation.ConversationEvent directReply = null;
            try {
                var companion = companions.get(companionId).orElseThrow(() -> new IllegalArgumentException("COMPANION_NOT_FOUND"));
                if (!session.companionIds().contains(companionId) || !ownerId.equals(companion.ownerId())) {
                    throw new IllegalArgumentException("OWNER_AUTHORIZATION_FAILED");
                }
                var active = commands.activeTaskFor(companionId);
                var activePlan = plans.activeForCompanion(companionId);
                var waiting = conversations.repository().activeForCompanion(companionId);
                var incoming = incomingMessages.classify(text, waiting.orElse(null));
                if (waiting.isPresent() && taskGraphRuntime != null
                        && taskGraphRuntime.handles(waiting.orElseThrow())) {
                    if (incoming.kind() == IncomingMessageKind.WAITING_ANSWER) {
                        var resumed = taskGraphRuntime.answer(waiting.orElseThrow(), incoming);
                        reply.put("accepted", resumed.success()).put("source", "task-graph")
                                .put("code", resumed.code()).put("reply", "Answer received.")
                                .put("questionId", waiting.orElseThrow().questionId())
                                .put("executionId", waiting.orElseThrow().taskGraphExecutionId());
                        reply.set("taskGraph", resumed.observation());
                        ObjectNode message = envelope(session, "player_reply");
                        message.set("payload", reply);
                        if (session.peer().isOpen()) session.peer().send(Json.write(message));
                        return;
                    }
                    if (incoming.kind() == IncomingMessageKind.CONTROL) {
                        var cancelled = taskGraphRuntime.cancel(waiting.orElseThrow(), "OWNER_CANCELLED");
                        reply.put("accepted", cancelled.success()).put("source", "task-graph")
                                .put("code", cancelled.code()).put("reply", "Cancelled.")
                                .put("executionId", waiting.orElseThrow().taskGraphExecutionId());
                        ObjectNode message = envelope(session, "player_reply");
                        message.set("payload", reply);
                        if (session.peer().isOpen()) session.peer().send(Json.write(message));
                        return;
                    }
                    if (incoming.kind() == IncomingMessageKind.GOAL_MODIFICATION) {
                        taskGraphRuntime.cancel(waiting.orElseThrow(), "OWNER_MODIFIED_GOAL");
                        waiting = java.util.Optional.empty();
                    }
                }
                if (waiting.isPresent() && waiting.orElseThrow().brainSessionId() == null
                        && incoming.kind() == IncomingMessageKind.WAITING_ANSWER) {
                    var resumed = kernel.resumeWaitingAnswer(waiting.orElseThrow(), incoming);
                    reply.put("accepted", true).put("source", "waiting-answer")
                            .put("reply", "已收到你的回答，并继续原来的任务。")
                            .put("decision", DecisionKind.REPLAN.name())
                            .put("questionId", waiting.orElseThrow().questionId())
                            .put("planId", resumed.planId()).put("planState", resumed.state().name())
                            .put("resumedOriginalPlan", true);
                    ObjectNode message = envelope(session, "player_reply");
                    message.set("payload", reply);
                    if (session.peer().isOpen()) session.peer().send(Json.write(message));
                    return;
                }
                var recentConversation = conversations.recentTranscript(companionId, 12);
                if (incoming.kind() != IncomingMessageKind.WAITING_ANSWER) {
                    conversations.hear(companionId, activePlan.map(value -> value.planId()).orElse(null),
                            "MESSAGE", text, Json.object().put("channel", "GAME"));
                }
                var visible = capabilityVisibility.resolve(session.handshake(), companion.status());
                JsonNode verifiedWorld = memories.enrichVerifiedWorld(companionId, companion.status());
                AgentContext context = new AgentContext(companionId, verifiedWorld, recentConversation,
                        active.<JsonNode>map(Json.MAPPER::valueToTree).orElseGet(Json::object),
                        memories.verifiedLandmarkKeys(companionId),
                        visible.availableNames(), memories.preferenceContext(companionId, 24), 5);
                if (externalBrain != null) {
                    if (waiting.isPresent() && waiting.orElseThrow().brainSessionId() != null
                            && incoming.kind() == IncomingMessageKind.CONTROL) {
                        conversations.repository().cancel(waiting.orElseThrow().questionId(), "OWNER_CANCELLED");
                        externalBrain.cancel("runtime-primary", companionId, "OWNER_CANCELLED");
                        reply.put("accepted", true).put("source", "external-brain")
                                .put("code", "BRAIN_CANCELLED").put("decision", BrainTurnResult.Kind.CANCEL.name())
                                .put("reply", "Cancelled.");
                        ObjectNode brainMessage = envelope(session, "player_reply");
                        brainMessage.set("payload", reply);
                        if (session.peer().isOpen()) session.peer().send(Json.write(brainMessage));
                        return;
                    }
                    if (waiting.isPresent() && waiting.orElseThrow().brainSessionId() != null
                            && incoming.kind() == IncomingMessageKind.GOAL_MODIFICATION) {
                        conversations.repository().cancel(waiting.orElseThrow().questionId(), "GOAL_MODIFIED");
                    }
                    var brainResult = waiting.isPresent() && waiting.orElseThrow().brainSessionId() != null
                            && incoming.kind() == IncomingMessageKind.WAITING_ANSWER
                            ? externalBrain.answer("runtime-primary", waiting.orElseThrow(), incoming, context)
                            : externalBrain.continueTurn("runtime-primary", companionId, text, context);
                    reply.put("accepted", true).put("source", "external-brain").put("code", brainResult.code())
                            .put("decision", brainResult.kind().name()).put("reply", brainResult.response());
                    reply.set("capabilityStates", visible.toJson());
                    reply.set("toolResults", Json.MAPPER.valueToTree(brainResult.toolResults()));
                    if (brainResult.question() != null) {
                        reply.set("waitingQuestion", Json.MAPPER.valueToTree(brainResult.question()));
                    }
                    com.mccompanion.runtime.conversation.ConversationEvent brainReply = null;
                    if (brainResult.kind() == BrainTurnResult.Kind.FINAL_RESPONSE && !brainResult.response().isBlank()) {
                        brainReply = conversations.recordDirectReply(companionId, null,
                                brainResult.kind() == BrainTurnResult.Kind.ASK_USER ? "QUESTION" : "CHAT",
                                brainResult.response(), Json.object().put("channel", "GAME")
                                        .put("source", "external-brain")
                                        .put("brainSessionId", brainResult.sessionId()));
                        reply.put("conversationEventId", brainReply.eventId());
                    }
                    ObjectNode brainMessage = envelope(session, "player_reply");
                    brainMessage.set("payload", reply);
                    if (session.peer().isOpen()) {
                        session.peer().send(Json.write(brainMessage));
                        if (brainReply != null) conversations.markDirectReplyDelivered(brainReply.eventId());
                        if (brainResult.question() != null) conversations.repository()
                                .markQuestionGameDelivered(brainResult.question().questionId());
                    }
                    return;
                }
                var result = providers.plan(text, context);
                reply.put("accepted", result.accepted()).put("source", result.source())
                        .put("reply", result.decision().reply()).put("decision", result.decision().kind().name());
                reply.set("capabilityStates", visible.toJson());
                if (!result.accepted()) reply.put("code", result.errorCode()).put("reply", result.userMessage());
                else if (activePlan.isPresent() && (result.decision().kind() == DecisionKind.CREATE_PLAN
                        || result.decision().kind() == DecisionKind.REPLAN) && !result.decision().steps().isEmpty()) {
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
                        var cancellation = commands.execute("goal-change-" + requestId, companionId,
                                new Intent(TaskType.STOP, Json.object().put("action", "cancel"), text));
                        reply.put("accepted", cancellation.accepted()).put("code", cancellation.code());
                        if (!cancellation.accepted()) reply.put("reply", cancellation.message());
                    } else {
                        int queuedStep = queued.currentStep();
                        var old = queued.steps().stream().filter(step -> step.index() != queuedStep)
                                .filter(step -> step.state() == StepState.RUNNING || step.state() == StepState.BLOCKED
                                        || step.state() == StepState.PAUSED || step.state() == StepState.CANCELLED)
                                .max(java.util.Comparator.comparingInt(candidate -> candidate.index())).orElseThrow();
                        queued = plans.activateGoalModification(queued.planId(), queued.revision(), old.index(),
                                StepState.CANCELLED, Json.object().put("noActiveBehavior", true), "GOAL_MODIFIED");
                        queued = kernel.start(queued.planId());
                    }
                    reply.put("planId", queued.planId()).put("planState", queued.state().name())
                            .put("goalModified", true);
                } else if (result.executableIntent().isPresent()) {
                    var execution = commands.execute("game-" + requestId, companionId, result.executableIntent().get());
                    reply.put("accepted", execution.accepted()).put("code", execution.code()).put("taskId", execution.taskId());
                    if (!execution.accepted()) reply.put("reply", execution.message());
                } else if (result.decision().kind() == DecisionKind.CREATE_PLAN || result.decision().kind() == DecisionKind.REPLAN) {
                    var plan = plans.create(companionId, text, result.decision());
                    plan = kernel.start(plan.planId());
                    reply.put("planId", plan.planId()).put("planState", plan.state().name());
                }
                String assistantReply = reply.path("reply").asText("");
                if (!assistantReply.isBlank()) {
                    directReply = conversations.recordDirectReply(companionId, reply.path("planId").asText(null),
                            ConversationService.kindForDecision(result.decision().kind()), assistantReply,
                            Json.object().put("channel", "GAME").put("source", result.source())
                                    .put("decision", result.decision().kind().name()));
                    reply.put("conversationEventId", directReply.eventId());
                }
            } catch (Exception failure) {
                log.warn("Game text request stopped safely: " + failure.getClass().getSimpleName());
                reply.put("accepted", false).put("code", "REQUEST_BLOCKED")
                        .put("reply", "我现在无法安全开始这个任务；当前状态已保留，请稍后重试或取消。");
            }
            ObjectNode message = envelope(session, "player_reply");
            message.set("payload", reply);
            if (session.peer().isOpen()) {
                session.peer().send(Json.write(message));
                if (directReply != null) {
                    try { conversations.markDirectReplyDelivered(directReply.eventId()); }
                    catch (java.sql.SQLException failure) {
                        log.error("Unable to mark direct conversation reply delivered", failure);
                    }
                }
            }
        });
    }

    private void registerCompanionList(RuntimeSession session, JsonNode payload) throws SQLException {
        JsonNode values = payload.isArray() ? payload : payload.path("companions");
        if (!values.isArray()) throw new IllegalArgumentException("companion_list requires an array");
        for (JsonNode companion : values) registerCompanion(session, companion);
    }

    private void registerCompanion(RuntimeSession session, JsonNode companion) throws SQLException {
        if (!companion.isObject()) {
            throw new IllegalArgumentException("companion status must be a JSON object");
        }
        ObjectNode normalized = (ObjectNode) companion.deepCopy();
        if (!normalized.hasNonNull("worldId")) {
            normalized.put("worldId", session.handshake().worldId());
        }
        ObjectNode protocolView = Json.object();
        for (String field : java.util.List.of("companionId", "ownerId", "displayName", "worldId", "dimension",
                "position", "bodyState", "behaviorId", "behaviorState", "behaviorRevision", "controlEpoch",
                "runtimeConnected", "capabilities", "observedAt")) {
            if (normalized.has(field)) protocolView.set(field, normalized.get(field));
        }
        CompanionStatus status = convert(protocolView, CompanionStatus.class);
        sessions.registerCompanion(session, status, normalized);
        memories.rememberObservedContainers(status.companionId(), normalized);
        conversations.deliverPending(status.companionId());
    }

    private void sendSubscription(RuntimeSession session) {
        ObjectNode message = envelope(session, "subscription").put("name", "status")
                .put("intervalMillis", 1000);
        message.putObject("payload").put("includeCompanions", true).put("includeBehaviors", true);
        session.peer().send(Json.write(message));
        ObjectNode query = envelope(session, "query").put("name", "list_companions");
        query.set("payload", Json.object());
        session.peer().send(Json.write(query));
    }

    private void sendHeartbeatAck(RuntimeSession session, long acknowledged) {
        ObjectNode message = envelope(session, "heartbeat_ack").put("ackSequence", acknowledged);
        message.set("payload", Json.object());
        session.peer().send(Json.write(message));
    }

    private ObjectNode envelope(RuntimeSession session, String type) {
        return Json.object().put("protocol", PROTOCOL).put("type", type)
                .put("sessionId", session.sessionId()).put("worldId", session.handshake().worldId())
                .put("sequence", session.nextSequence()).put("timestamp", clock.millis());
    }

    private void reject(Peer peer, String code, String message) {
        ObjectNode response = Json.object().put("protocol", PROTOCOL).put("type", "hello_ack")
                .put("accepted", false).put("runtimeVersion", VERSION).put("code", code).put("message", message);
        peer.send(Json.write(response));
        peer.close(1008, code);
    }

    private void sendError(SessionPeer peer, RuntimeSession session, String code, String message) {
        ObjectNode error = Json.object().put("protocol", PROTOCOL).put("type", "error")
                .put("code", code).put("message", message).put("timestamp", clock.millis());
        if (session != null) {
            error.put("sessionId", session.sessionId()).put("sequence", session.nextSequence());
        }
        peer.send(Json.write(error));
    }

    public void sweepPending(Duration timeout) {
        Instant cutoff = clock.instant().minus(timeout);
        pendingSince.forEach((socket, opened) -> {
            if (opened.isBefore(cutoff)) {
                Peer peer = peers.get(socket);
                if (peer != null) reject(peer, "HANDSHAKE_TIMEOUT", "Handshake was not received in time");
                pendingSince.remove(socket);
            }
        });
    }

    @Override
    public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        Peer peer = peers.remove(socket);
        pendingSince.remove(socket);
        headerTokens.remove(socket);
        if (peer != null) sessions.unregister(peer, reason == null || reason.isBlank() ? "CONNECTION_CLOSED" : reason);
    }

    @Override
    public void onError(WebSocket socket, Exception exception) {
        log.error("WebSocket transport error", exception);
    }

    @Override
    public void onStart() {
        started.countDown();
        log.info("Runtime WebSocket listening on " + getAddress().getHostString() + ":" + getPort());
    }

    @Override
    public void close() {
        planningExecutor.shutdownNow();
        try {
            stop(3_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > 512) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static <T> T convert(JsonNode node, Class<T> type) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(type.getSimpleName() + " payload must be a JSON object");
        }
        try {
            return Json.MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " payload", invalid);
        }
    }

    private static final class Peer implements SessionPeer {
        private final WebSocket socket;
        private Peer(WebSocket socket) { this.socket = socket; }
        @Override public String id() { return Integer.toHexString(System.identityHashCode(socket)); }
        @Override public String remoteAddress() { return String.valueOf(socket.getRemoteSocketAddress()); }
        @Override public boolean isOpen() { return socket.isOpen(); }
        @Override public void send(String text) { socket.send(text); }
        @Override public void close(int code, String reason) { socket.close(code, reason); }
    }
}
