package com.mccompanion.core.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BodyControlArbiterTest {
  private final UUID companion = UUID.randomUUID();
  private final BodyControlArbiter arbiter = new BodyControlArbiter();

  @Test
  void ownerPreemptsRuntimeAndOldRuntimeCannotReappear() {
    assertTrue(
        arbiter
            .claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK, "lease-1")
            .accepted());
    var owner =
        arbiter.claim(companion, BodyControlArbiter.Authority.OWNER_IMMEDIATE, "owner-stop");
    assertTrue(owner.accepted());
    assertEquals(BodyControlArbiter.Authority.OWNER_IMMEDIATE, owner.current());

    var replay = arbiter.claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK, "lease-1");
    assertFalse(replay.accepted());
    assertEquals("OWNER_CONTROL_ACTIVE", replay.code());
  }

  @Test
  void safetyIsLatchedAboveOwnerAndRuntimeUntilHazardClears() {
    arbiter.claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK, "travel");
    var safety =
        arbiter.claim(companion, BodyControlArbiter.Authority.SAFETY_REFLEX, "lava");
    assertTrue(safety.accepted());
    assertEquals(
        "SAFETY_REFLEX_ACTIVE",
        arbiter
            .claim(companion, BodyControlArbiter.Authority.OWNER_IMMEDIATE, "resume")
            .code());
    assertEquals(
        "SAFETY_REFLEX_ACTIVE",
        arbiter
            .claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK, "resume")
            .code());

    assertTrue(
        arbiter
            .release(companion, BodyControlArbiter.Authority.SAFETY_REFLEX, "hazard-cleared")
            .accepted());
    assertTrue(
        arbiter
            .claim(companion, BodyControlArbiter.Authority.OWNER_IMMEDIATE, "resume")
            .accepted());
  }

  @Test
  void revisionsAreMonotonicAndCompanionStateCanBeForgotten() {
    long first =
        arbiter
            .claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK, "task")
            .revision();
    long second = arbiter.releaseCurrent(companion, "complete").revision();
    assertTrue(second > first);
    assertEquals(1, arbiter.trackedCompanions());
    arbiter.clear(companion);
    assertEquals(0, arbiter.trackedCompanions());
    assertEquals(BodyControlArbiter.Authority.IDLE, arbiter.snapshot(companion).authority());
  }
}
