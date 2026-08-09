package com.mccompanion.core.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mccompanion.minecraft.bridge.ConnectionEpochGate;
import org.junit.jupiter.api.Test;

class ConnectionEpochGateTest {
  @Test
  void lateCallbacksFromReplacedConnectionCannotAffectCurrentConnection() {
    ConnectionEpochGate<Object> gate = new ConnectionEpochGate<>();
    Object oldConnection = new Object();
    Object newConnection = new Object();
    long oldAttempt = gate.beginAttempt();
    assertTrue(gate.activate(oldAttempt, oldConnection));
    assertTrue(gate.deactivate(oldAttempt, oldConnection));
    long newAttempt = gate.beginAttempt();
    assertTrue(gate.activate(newAttempt, newConnection));
    assertFalse(gate.isLatestAttempt(oldAttempt));
    assertFalse(gate.deactivate(oldAttempt, oldConnection));
    assertTrue(gate.isCurrent(newAttempt, newConnection));
  }

  @Test
  void lateOpenFromOlderAttemptIsRejected() {
    ConnectionEpochGate<Object> gate = new ConnectionEpochGate<>();
    Object oldConnection = new Object();
    Object newConnection = new Object();
    long oldAttempt = gate.beginAttempt();
    long newAttempt = gate.beginAttempt();
    assertFalse(gate.activate(oldAttempt, oldConnection));
    assertTrue(gate.activate(newAttempt, newConnection));
  }
}
