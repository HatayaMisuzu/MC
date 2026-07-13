package com.mccompanion.runtime.health;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.config.RuntimeConfig;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.security.PairingTokenStore;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.websocket.RuntimeWebSocketServer;
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
    private final RuntimeLog log;
    private final Instant startedAt;
    private final HttpServer server;

    public RuntimeHealthServer(
            RuntimeConfig config,
            String pairingToken,
            SessionRegistry sessions,
            RuntimeLog log) throws IOException {
        this.config = config;
        this.pairingToken = pairingToken;
        this.sessions = sessions;
        this.log = log;
        this.startedAt = Clock.systemUTC().instant();
        server = HttpServer.create(new InetSocketAddress(config.server.bind, config.server.managementPort), 8);
        server.createContext("/health", this::health);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-runtime-health");
            thread.setDaemon(true);
            return thread;
        }));
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
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String candidate = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring(7).trim() : null;
            if (!PairingTokenStore.matches(pairingToken, candidate)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
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
                    .put("sessionCount", sessions.sessions().size());
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
