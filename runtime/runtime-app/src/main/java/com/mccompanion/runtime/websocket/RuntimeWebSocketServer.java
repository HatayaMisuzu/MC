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
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.security.PairingTokenStore;
import com.mccompanion.runtime.session.Handshake;
import com.mccompanion.runtime.session.RuntimeSession;
import com.mccompanion.runtime.session.SessionPeer;
import com.mccompanion.runtime.session.SessionRegistry;
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

public final class RuntimeWebSocketServer extends WebSocketServer implements AutoCloseable {
    public static final String PROTOCOL = "mc-companion/1";
    public static final String VERSION = "0.3.0";
    private static final int MAX_MESSAGE_CHARS = 1_048_576;
    private final String pairingToken;
    private final SessionRegistry sessions;
    private final CommandService commands;
    private final RuntimeLog log;
    private final Clock clock;
    private final CountDownLatch started = new CountDownLatch(1);
    private final Map<WebSocket, Peer> peers = new ConcurrentHashMap<>();
    private final Map<WebSocket, Instant> pendingSince = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> headerTokens = new ConcurrentHashMap<>();

    public RuntimeWebSocketServer(InetSocketAddress address, String pairingToken, SessionRegistry sessions,
                                  CommandService commands, RuntimeLog log) {
        this(address, pairingToken, sessions, commands, log, Clock.systemUTC());
    }

    RuntimeWebSocketServer(InetSocketAddress address, String pairingToken, SessionRegistry sessions,
                           CommandService commands, RuntimeLog log, Clock clock) {
        super(address);
        this.pairingToken = pairingToken;
        this.sessions = sessions;
        this.commands = commands;
        this.log = log;
        this.clock = clock;
        setConnectionLostTimeout(30);
        setReuseAddr(true);
    }

    public void startAndAwait(Duration timeout) throws InterruptedException {
        start();
        if (!started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("WebSocket server did not start in time");
        }
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
            case "ack", "gap_summary" -> { /* ACK/gap is intentionally non-blocking; durable task events arrive separately. */ }
            default -> sendError(session.peer(), session, "UNKNOWN_MESSAGE_TYPE", "Unsupported message type");
        }
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
        CompanionStatus status = convert(normalized, CompanionStatus.class);
        sessions.registerCompanion(session, status, normalized);
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
