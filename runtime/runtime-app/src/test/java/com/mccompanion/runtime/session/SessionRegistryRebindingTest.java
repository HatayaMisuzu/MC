package com.mccompanion.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mccompanion.protocol.CapabilitySet;
import com.mccompanion.protocol.CompanionBodyState;
import com.mccompanion.protocol.CompanionStatus;
import com.mccompanion.protocol.PositionDto;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRegistryRebindingTest {
  @TempDir Path temporary;

  @Test
  void newerDisconnectDoesNotLetAnOlderActiveSessionReclaimAuthority() throws Exception {
    try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("authority-floor.db"));
         RuntimeLog log = new RuntimeLog(temporary.resolve("authority-floor.log"), false, new Redactor())) {
      database.initialize();
      CompanionRepository companions = new CompanionRepository(database);
      try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
        RuntimeSession oldSession = sessions.register(new Peer("floor-old"), handshake("floor-old-world"));
        RuntimeSession newSession = sessions.register(new Peer("floor-new"), handshake("floor-new-world"));
        sessions.registerCompanion(oldSession, status("floor-old-world"), Json.object());
        sessions.registerCompanion(newSession, status("floor-new-world"), Json.object());
        sessions.unregister(newSession.peer(), "CURRENT_DISCONNECT");

        IllegalArgumentException stale = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> sessions.registerCompanion(oldSession, status("floor-old-world"), Json.object()));
        assertEquals("STALE_COMPANION_SESSION", stale.getMessage());
        assertTrue(sessions.forCompanion("companion-a").isEmpty());
      }
    }
  }

  @Test
  void rebindingCompanionAtomicallyRevokesOldSessionAuthority() throws Exception {
    try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("runtime.db"));
         RuntimeLog log = new RuntimeLog(temporary.resolve("runtime.log"), false, new Redactor())) {
      database.initialize();
      CompanionRepository companions = new CompanionRepository(database);
      try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
        RuntimeSession oldSession = sessions.register(new Peer("old"), handshake("old-world"));
        RuntimeSession newSession = sessions.register(new Peer("new"), handshake("new-world"));
        sessions.registerCompanion(oldSession, status("old-world"), Json.object());
        assertTrue(oldSession.companionIds().contains("companion-a"));

        sessions.registerCompanion(newSession, status("new-world"), Json.object());

        assertFalse(oldSession.companionIds().contains("companion-a"));
        assertTrue(newSession.companionIds().contains("companion-a"));
        assertFalse(sessions.isAuthoritative(oldSession, "companion-a"));
        assertTrue(sessions.isAuthoritative(newSession, "companion-a"));
        assertEquals(newSession, sessions.forCompanion("companion-a").orElseThrow());
        assertEquals(newSession.sessionId(), companions.get("companion-a").orElseThrow().sessionId());
        IllegalArgumentException stale = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> sessions.registerCompanion(oldSession, status("old-world"), Json.object()));
        assertEquals("STALE_COMPANION_SESSION", stale.getMessage());
        sessions.unregister(oldSession.peer(), "LATE_OLD_DISCONNECT");
        assertEquals(newSession, sessions.forCompanion("companion-a").orElseThrow());
        sessions.unregister(newSession.peer(), "CURRENT_DISCONNECT");
        assertTrue(sessions.forCompanion("companion-a").isEmpty());
        IllegalArgumentException resurrected = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> sessions.registerCompanion(oldSession, status("old-world"), Json.object()));
        assertEquals("STALE_COMPANION_SESSION", resurrected.getMessage());
      }
    }
  }

  private static Handshake handshake(String world) {
    return new Handshake("mc-companion/1", "test", "1.21.1", "fabric", world, Json.object());
  }

  private static CompanionStatus status(String world) {
    return new CompanionStatus("companion-a", "owner-a", "Companion", world,
        "minecraft:overworld", new PositionDto(0, 64, 0), CompanionBodyState.SPAWNED,
        null, null, 0, 0, true, CapabilitySet.empty(), Instant.now());
  }

  private record Peer(String id) implements SessionPeer {
    @Override public String remoteAddress() { return "loopback"; }
    @Override public boolean isOpen() { return true; }
    @Override public void send(String text) { }
    @Override public void close(int code, String reason) { }
  }
}
