package com.mccompanion.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.protocol.CompanionStatus;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRegistry implements AutoCloseable {
    private final RuntimeDatabase database;
    private final CompanionRepository companions;
    private final RuntimeLog log;
    private final Clock clock;
    private final Map<String, RuntimeSession> byPeer = new ConcurrentHashMap<>();
    private final Map<String, RuntimeSession> byId = new ConcurrentHashMap<>();
    private final Map<String, RuntimeSession> byWorld = new ConcurrentHashMap<>();
    private final Map<String, RuntimeSession> byCompanion = new ConcurrentHashMap<>();
    private volatile Listener listener = Listener.NOOP;

    public SessionRegistry(RuntimeDatabase database, CompanionRepository companions, RuntimeLog log) {
        this(database, companions, log, Clock.systemUTC());
    }

    public SessionRegistry(RuntimeDatabase database, CompanionRepository companions, RuntimeLog log, Clock clock) {
        this.database = database;
        this.companions = companions;
        this.log = log;
        this.clock = clock;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NOOP : listener;
    }

    public synchronized RuntimeSession register(SessionPeer peer, Handshake handshake) throws SQLException {
        requireText(handshake.worldId(), "worldId");
        Instant now = clock.instant();
        RuntimeSession session = new RuntimeSession(UUID.randomUUID().toString(), peer, handshake, now);
        persistConnected(session);
        RuntimeSession previous = byWorld.put(handshake.worldId(), session);
        if (previous != null && previous != session) {
            unregister(previous.peer(), "REPLACED_BY_RECONNECT");
            previous.peer().close(4000, "World session replaced by reconnect");
        }
        byPeer.put(peer.id(), session);
        byId.put(session.sessionId(), session);
        log.info("Mod session connected: session=" + session.sessionId() + ", world=" + handshake.worldId()
                + ", loader=" + handshake.loader() + ", minecraft=" + handshake.minecraftVersion());
        return session;
    }

    /** Called only after the accepted handshake reply has been written to preserve wire ordering. */
    public void ready(RuntimeSession session) {
        listener.onConnected(session);
    }

    public synchronized void unregister(SessionPeer peer, String reason) {
        RuntimeSession session = byPeer.remove(peer.id());
        if (session == null) {
            return;
        }
        byId.remove(session.sessionId(), session);
        byWorld.remove(session.handshake().worldId(), session);
        session.companionIds().forEach(id -> byCompanion.remove(id, session));
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE runtime_session SET state='DISCONNECTED', disconnected_at=?, last_seen_at=? WHERE session_id=?
                """)) {
            statement.setLong(1, clock.millis());
            statement.setLong(2, clock.millis());
            statement.setString(3, session.sessionId());
            statement.executeUpdate();
        } catch (SQLException failure) {
            log.error("Unable to persist disconnected session " + session.sessionId(), failure);
        }
        log.warn("Mod session disconnected: session=" + session.sessionId() + ", reason=" + reason);
        listener.onDisconnected(session, reason);
    }

    public void touch(SessionPeer peer) throws SQLException {
        RuntimeSession session = byPeer.get(peer.id());
        if (session == null) {
            return;
        }
        Instant now = clock.instant();
        session.touch(now);
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE runtime_session SET last_seen_at=? WHERE session_id=?")) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, session.sessionId());
            statement.executeUpdate();
        }
    }

    public void registerCompanion(RuntimeSession session, CompanionStatus status, JsonNode statusJson) throws SQLException {
        requireText(status.companionId(), "companionId");
        if (!session.handshake().worldId().equals(status.worldId())) {
            throw new IllegalArgumentException("Companion status worldId does not match the authenticated session");
        }
        session.addCompanion(status.companionId());
        byCompanion.put(status.companionId(), session);
        JsonNode persistedStatus = statusJson == null ? Json.object() : statusJson;
        companions.upsert(status.companionId(), session.sessionId(), session.handshake().worldId(), status.ownerId(),
                status.displayName(), persistedStatus);
        listener.onCompanionUpdated(session, status, persistedStatus);
    }

    /** Converts sessions left CONNECTED by an unclean previous process into durable disconnected history. */
    public int recoverStaleSessions() throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE runtime_session SET state='DISCONNECTED', disconnected_at=?, last_seen_at=?
                WHERE state='CONNECTED'
                """)) {
            statement.setLong(1, clock.millis());
            statement.setLong(2, clock.millis());
            return statement.executeUpdate();
        }
    }

    public List<RuntimeSession> expireHeartbeat(Duration timeout) {
        Instant cutoff = clock.instant().minus(timeout);
        List<RuntimeSession> expired = new ArrayList<>();
        for (RuntimeSession session : byId.values()) {
            if (session.lastSeen().isBefore(cutoff)) {
                expired.add(session);
                session.peer().close(4001, "Heartbeat timeout");
                unregister(session.peer(), "HEARTBEAT_TIMEOUT");
            }
        }
        return List.copyOf(expired);
    }

    public Optional<RuntimeSession> forPeer(SessionPeer peer) { return Optional.ofNullable(byPeer.get(peer.id())); }
    public Optional<RuntimeSession> forCompanion(String companionId) { return Optional.ofNullable(byCompanion.get(companionId)); }
    public Optional<RuntimeSession> forWorld(String worldId) { return Optional.ofNullable(byWorld.get(worldId)); }
    public List<RuntimeSession> sessions() { return List.copyOf(byId.values()); }

    private void persistConnected(RuntimeSession session) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runtime_session(session_id, world_id, protocol, mod_version, minecraft_version,
                  loader, capabilities_json, state, connected_at, last_seen_at, disconnected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CONNECTED', ?, ?, NULL)
                """)) {
            statement.setString(1, session.sessionId());
            statement.setString(2, session.handshake().worldId());
            statement.setString(3, session.handshake().protocol());
            statement.setString(4, session.handshake().modVersion());
            statement.setString(5, session.handshake().minecraftVersion());
            statement.setString(6, session.handshake().loader());
            statement.setString(7, Json.write(session.handshake().capabilities()));
            statement.setLong(8, session.connectedAt().toEpochMilli());
            statement.setLong(9, session.connectedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(field + " is missing or invalid");
        }
        return value;
    }

    @Override
    public void close() {
        for (RuntimeSession session : sessions()) {
            session.peer().close(1001, "Runtime shutting down");
            unregister(session.peer(), "RUNTIME_SHUTDOWN");
        }
    }

    public interface Listener {
        Listener NOOP = new Listener() { };
        default void onConnected(RuntimeSession session) { }
        default void onDisconnected(RuntimeSession session, String reason) { }
        default void onCompanionUpdated(RuntimeSession session, CompanionStatus status, JsonNode statusJson) { }
    }
}
