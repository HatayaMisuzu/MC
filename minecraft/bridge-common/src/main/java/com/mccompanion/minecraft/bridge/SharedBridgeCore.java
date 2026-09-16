package com.mccompanion.minecraft.bridge;

import com.mccompanion.protocol.BuildIdentity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Shared connection, handshake, sequence and bounded ordered delivery core. It never retains game objects. */
public final class SharedBridgeCore implements AutoCloseable {
    public interface Host {
        void execute(Runnable work);
        void connected();
        void disconnected(String reason);
        void message(Map<String, Object> message);
        void publishStatus();
        void diagnostic(String code);
    }
    private final BridgeCodec codec;
    private final Host host;
    private final URI uri;
    private final Path tokenFile;
    private final Map<String, Object> hello;
    private final String worldId;
    private final ScheduledExecutorService executor;
    private final HttpClient client;
    private final ConnectionEpochGate<WebSocket> epochs = new ConnectionEpochGate<>();
    private final ArrayDeque<String> outgoing = new ArrayDeque<>();
    private WebSocket socket;
    private String sessionId;
    private long sequence, incomingSequence = -1, activeEpoch;
    private boolean connecting, closed, sending;
    private java.util.concurrent.ScheduledFuture<?> handshakeTimeout;

    public SharedBridgeCore(URI uri, Path tokenFile, Map<String, Object> hello, BridgeCodec codec, Host host) {
        this.uri = java.util.Objects.requireNonNull(uri);
        this.tokenFile = java.util.Objects.requireNonNull(tokenFile);
        this.hello = BridgeValues.freeze(hello);
        this.worldId = (String) hello.get("worldId");
        this.codec = java.util.Objects.requireNonNull(codec);
        this.host = java.util.Objects.requireNonNull(host);
        executor = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "mc-companion-runtime-bridge"); thread.setDaemon(true); return thread;
        });
        client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(3)).build();
    }
    public void start() {
        executor.scheduleWithFixedDelay(this::connect, 0, 5, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> {
            long epoch;
            synchronized (this) { if (!connected()) return; epoch = activeEpoch; }
            dispatch(epoch, host::publishStatus);
        }, 1, 1, TimeUnit.SECONDS);
    }
    public synchronized boolean connected() { return !closed && socket != null && sessionId != null; }
    public synchronized String sessionId() { return sessionId; }
    private void connect() {
        synchronized (this) { if (closed || connecting || socket != null) return; connecting = true; }
        try {
            if (!Files.isRegularFile(tokenFile)) { synchronized (this) { connecting = false; } return; }
            String token = Files.readString(tokenFile).trim();
            if (token.length() < 16 || token.length() > 512 || token.chars().anyMatch(Character::isWhitespace)) {
                throw new IOException("PAIRING_TOKEN_INVALID");
            }
            long epoch;
            synchronized (this) { if (closed) return; epoch = epochs.beginAttempt(); activeEpoch = epoch; }
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token).buildAsync(uri, new Listener(epoch))
                    .whenComplete((connection, failure) -> {
                        synchronized (this) { if (epochs.isLatestAttempt(epoch)) connecting = false; }
                        if (failure != null) host.diagnostic("CONNECTION_FAILED");
                    });
        } catch (IOException | RuntimeException failure) {
            synchronized (this) { connecting = false; }
            host.diagnostic("BRIDGE_CONFIGURATION_UNAVAILABLE");
        }
    }
    public synchronized boolean send(String type, Map<String, Object> payload) {
        if (!connected()) return false;
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("protocol", BuildIdentity.PROTOCOL); envelope.put("type", type);
        envelope.put("sessionId", sessionId); envelope.put("worldId", worldId);
        envelope.put("sequence", ++sequence); envelope.put("sentAt", Instant.now().toString());
        envelope.put("payload", BridgeValues.freeze(payload));
        return enqueue(envelope);
    }
    private synchronized boolean enqueue(Map<String, Object> message) {
        if (socket == null || closed) return false;
        if (outgoing.size() >= 128) { disconnect(activeEpoch, socket, "OUTBOUND_LIMIT"); return false; }
        try {
            String text = codec.encode(message);
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) throw new IOException("MESSAGE_TOO_LARGE");
            outgoing.add(text); drain(); return true;
        } catch (IOException | RuntimeException failure) {
            disconnect(activeEpoch, socket, "ENCODE_FAILED"); return false;
        }
    }
    private synchronized void drain() {
        if (sending || outgoing.isEmpty() || socket == null) return;
        sending = true;
        WebSocket current = socket; long epoch = activeEpoch;
        try {
            current.sendText(outgoing.peek(), true).orTimeout(10, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
                synchronized (this) {
                    if (!epochs.isCurrent(epoch, current)) return;
                    sending = false;
                    if (failure != null) { disconnect(epoch, current, "SEND_FAILED"); return; }
                    outgoing.poll(); drain();
                }
            });
        } catch (RuntimeException failure) { disconnect(epoch, current, "SEND_FAILED"); }
    }
    private void receive(long epoch, WebSocket source, String text) {
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
            disconnect(epoch, source, "MESSAGE_TOO_LARGE"); return;
        }
        final Map<String, Object> message;
        try { message = BridgeValues.freeze(codec.decode(text)); }
        catch (IOException | RuntimeException invalid) { disconnect(epoch, source, "INVALID_MESSAGE"); return; }
        synchronized (this) {
            if (!epochs.isCurrent(epoch, source) || closed) return;
            if ("hello_ack".equals(message.get("type"))) {
                if (sessionId != null || !Boolean.TRUE.equals(message.get("accepted"))
                        || !BuildIdentity.PROTOCOL.equals(message.get("protocol"))
                        || !BuildIdentity.PRODUCT_VERSION.equals(message.get("runtimeVersion"))
                        || !(message.get("sessionId") instanceof String id) || id.isBlank() || id.length() > 512) {
                    disconnect(epoch, source, "HANDSHAKE_REJECTED:" + message.getOrDefault("code", "COMPONENT_UPGRADE_REQUIRED"));
                    return;
                }
                sessionId = id; sequence = 0; incomingSequence = -1;
                cancelHandshakeTimeout();
                dispatch(epoch, () -> { host.connected(); host.publishStatus(); });
                return;
            }
            if (sessionId == null || !sessionId.equals(message.get("sessionId"))) return;
            if (!BuildIdentity.PROTOCOL.equals(message.get("protocol")) || !worldId.equals(message.get("worldId"))) {
                disconnect(epoch, source, "SESSION_CONTEXT_MISMATCH"); return;
            }
            Object value = message.get("sequence");
            if (!(value instanceof Number next) || next.doubleValue() != next.longValue() || next.longValue() <= incomingSequence) return;
            incomingSequence = next.longValue();
        }
        dispatch(epoch, () -> host.message(message));
    }
    private void dispatch(long epoch, Runnable work) {
        host.execute(() -> {
            synchronized (this) { if (!connected() || activeEpoch != epoch) return; }
            try { work.run(); }
            catch (RuntimeException invalid) {
                synchronized (this) {
                    if (activeEpoch == epoch && socket != null) disconnect(epoch, socket, "HOST_CALLBACK_FAILED");
                }
            }
        });
    }
    private synchronized void disconnect(long epoch, WebSocket expected, String reason) {
        if (!epochs.deactivate(epoch, expected)) return;
        cancelHandshakeTimeout();
        socket = null; sessionId = null; outgoing.clear(); sending = false;
        expected.abort();
        host.diagnostic(reason);
        host.execute(() -> {
            synchronized (this) { if (connected() && activeEpoch != epoch) return; }
            host.disconnected(reason);
        });
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; epochs.invalidate();
        cancelHandshakeTimeout();
        WebSocket old = socket; socket = null; sessionId = null; outgoing.clear(); sending = false;
        if (old != null) old.abort();
        executor.shutdownNow();
        // Lifecycle assembly calls close on the server thread.
        host.disconnected("SERVER_STOPPING");
    }
    private void cancelHandshakeTimeout() {
        if (handshakeTimeout != null) { handshakeTimeout.cancel(false); handshakeTimeout = null; }
    }
    private final class Listener implements WebSocket.Listener {
        private final long epoch;
        private final StringBuilder partial = new StringBuilder();
        private Listener(long epoch) { this.epoch = epoch; }
        @Override public void onOpen(WebSocket connection) {
            synchronized (SharedBridgeCore.this) {
                if (closed || !epochs.activate(epoch, connection)) { connection.abort(); return; }
                socket = connection;
                handshakeTimeout = executor.schedule(() -> {
                    synchronized (SharedBridgeCore.this) {
                        if (sessionId == null) disconnect(epoch, connection, "HANDSHAKE_TIMEOUT");
                    }
                }, 5, TimeUnit.SECONDS);
                enqueue(Map.of("protocol", BuildIdentity.PROTOCOL, "type", "hello", "payload", hello));
            }
            connection.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket connection, CharSequence data, boolean last) {
            if (!epochs.isCurrent(epoch, connection)) return null;
            if (partial.length() + data.length() > 1_048_576) { disconnect(epoch, connection, "MESSAGE_TOO_LARGE"); return null; }
            partial.append(data);
            if (last) { String text = partial.toString(); partial.setLength(0); receive(epoch, connection, text); }
            connection.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket connection, int code, String reason) {
            disconnect(epoch, connection, "CONNECTION_CLOSED"); return null;
        }
        @Override public void onError(WebSocket connection, Throwable failure) { disconnect(epoch, connection, "CONNECTION_ERROR"); }
    }
}
