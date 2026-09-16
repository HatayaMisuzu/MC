package com.mccompanion.core.body;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BodyControlLifecycleTest {
    @Test void releaseIsBoundToTheCurrentAuthorityAndOwner() {
        var lifecycle = new BodyControlLifecycle();
        UUID companion = UUID.randomUUID();
        lifecycle.claim(companion, BodyControlArbiter.Authority.RUNTIME_TASK, "runtime:1", "start");

        assertTrue(lifecycle.release(companion, BodyControlArbiter.Authority.RUNTIME_TASK,
                "runtime:old", "late completion").isEmpty());
        assertEquals(BodyControlArbiter.Authority.RUNTIME_TASK, lifecycle.snapshot(companion).authority());
        assertTrue(lifecycle.release(companion, BodyControlArbiter.Authority.RUNTIME_TASK,
                "runtime:1", "complete").orElseThrow().accepted());
        assertEquals(BodyControlArbiter.Authority.IDLE, lifecycle.snapshot(companion).authority());
    }

    @Test void ownerClaimCannotBeReleasedByAnotherOwner() {
        var lifecycle = new BodyControlLifecycle();
        UUID companion = UUID.randomUUID();
        lifecycle.claim(companion, BodyControlArbiter.Authority.OWNER_IMMEDIATE, "owner:a", "stop");

        assertTrue(lifecycle.releaseCurrent(companion, "owner:b", "complete").isEmpty());
        assertEquals(BodyControlArbiter.Authority.OWNER_IMMEDIATE, lifecycle.snapshot(companion).authority());
    }

    @Test void classifiesOnlyRecoveryAndSafetyStopsAsSuspensions() {
        assertTrue(BodyControlLifecycle.isSuspension("RUNTIME_DISCONNECTED"));
        assertTrue(BodyControlLifecycle.isSuspension("RECOVERY_REQUIRED"));
        assertFalse(BodyControlLifecycle.isSuspension("VERIFIED"));
        assertEquals("runtime:7:behavior:3", BodyControlLifecycle.runtimeIdentity(7, "behavior", 3));
    }
}
