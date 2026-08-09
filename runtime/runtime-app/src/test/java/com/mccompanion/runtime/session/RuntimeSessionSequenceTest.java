package com.mccompanion.runtime.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mccompanion.runtime.json.Json;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RuntimeSessionSequenceTest {
  @Test
  void observedSequenceRemainsConsumedAfterRoutingFailureAndRetryUsesNextSequence() {
    RuntimeSession session = new RuntimeSession("session", new Peer(),
        new Handshake("mc-companion/1", "test", "1.21.1", "fabric", "world", Json.object()),
        Instant.EPOCH, 1);
    assertTrue(session.acceptIncomingSequence(7));
    // Routing failure happens after this fence. Replaying 7 is rejected; retry identity uses 8.
    assertFalse(session.acceptIncomingSequence(7));
    assertTrue(session.acceptIncomingSequence(8));
  }

  private static final class Peer implements SessionPeer {
    @Override public String id() { return "peer"; }
    @Override public String remoteAddress() { return "loopback"; }
    @Override public boolean isOpen() { return true; }
    @Override public void send(String text) { }
    @Override public void close(int code, String reason) { }
  }
}
