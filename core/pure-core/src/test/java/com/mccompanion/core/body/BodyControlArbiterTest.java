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

    var safetyClaim = arbiter.snapshot(companion).claimToken();
    assertTrue(
        arbiter
            .release(companion, BodyControlArbiter.Authority.SAFETY_REFLEX,
                safetyClaim, "hazard-cleared")
            .accepted());
    assertTrue(
        arbiter
            .claim(companion, BodyControlArbiter.Authority.OWNER_IMMEDIATE, "resume")
            .accepted());
  }

  @Test
  void revisionsAreMonotonicAndCompanionStateCanBeForgotten() {
    var claim = arbiter.claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK, "task");
    long first = claim.revision();
    long second = arbiter.releaseCurrent(companion, claim.claimToken(), "complete").revision();
    assertTrue(second > first);
    assertEquals(1, arbiter.trackedCompanions());
    arbiter.clear(companion);
    assertEquals(0, arbiter.trackedCompanions());
    assertEquals(BodyControlArbiter.Authority.IDLE, arbiter.snapshot(companion).authority());
  }

  @Test
  void staleClaimCannotReleaseNewRuntimeOwnerOrSafetyOwner() {
    var runtimeA = arbiter.claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK,
        "lease-A/epoch-1/behavior-A", "runtime-A");
    var runtimeB = arbiter.claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK,
        "lease-B/epoch-2/behavior-B", "runtime-B");
    assertFalse(arbiter.release(companion, BodyControlArbiter.Authority.RUNTIME_TASK,
        runtimeA.claimToken(), "old completion").accepted());
    assertEquals("STALE_CONTROL_CLAIM", arbiter.release(companion,
        BodyControlArbiter.Authority.RUNTIME_TASK, runtimeA.claimToken(), "old completion").code());
    assertEquals(BodyControlArbiter.Authority.RUNTIME_TASK, arbiter.snapshot(companion).authority());
    assertTrue(arbiter.release(companion, BodyControlArbiter.Authority.RUNTIME_TASK,
        runtimeB.claimToken(), "current completion").accepted());

    var safetyA = arbiter.claim(companion, BodyControlArbiter.Authority.SAFETY_REFLEX,
        "hazard-A", "lava");
    var safetyB = arbiter.claim(companion, BodyControlArbiter.Authority.SAFETY_REFLEX,
        "hazard-B", "fire");
    assertEquals("STALE_CONTROL_CLAIM", arbiter.release(companion,
        BodyControlArbiter.Authority.SAFETY_REFLEX, safetyA.claimToken(), "old hazard cleared").code());
    assertEquals(BodyControlArbiter.Authority.SAFETY_REFLEX, arbiter.snapshot(companion).authority());
    assertTrue(arbiter.release(companion, BodyControlArbiter.Authority.SAFETY_REFLEX,
        safetyB.claimToken(), "current hazard cleared").accepted());
  }

  @Test
  void releaseWithoutTokenAndDuplicateReleaseAreSafeNoOps() {
    var claim = arbiter.claim(companion, BodyControlArbiter.Authority.OWNER_IMMEDIATE,
        "owner-A", "stop");
    assertEquals("CONTROL_CLAIM_REQUIRED", arbiter.releaseCurrent(companion, "legacy").code());
    assertTrue(arbiter.releaseCurrent(companion, claim.claimToken(), "stop").accepted());
    assertEquals("UNCHANGED", arbiter.releaseCurrent(companion, claim.claimToken(), "duplicate").code());
  }
}
