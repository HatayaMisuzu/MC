package com.mccompanion.runtime.execution;

import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveControllerTest {
    @Test
    void progressesPausesResumesAndCompletesOnlyFromDriverEvidence() {
        FakeDriver driver = new FakeDriver(
                PrimitiveObservation.progress(Json.object().put("distance", 4)),
                PrimitiveObservation.complete(Json.object().put("distance", 0).put("verified", true)));
        PrimitiveController action = new PrimitiveController(driver, 20, 5);
        assertEquals(PrimitiveState.RUNNING, action.start().state());
        assertEquals(PrimitiveState.RUNNING, action.tick().state());
        assertEquals(PrimitiveState.PAUSED, action.pause().state());
        assertEquals(PrimitiveState.PAUSED, action.tick().state());
        action.resume();
        assertEquals(PrimitiveState.SUCCEEDED, action.tick().state());
        assertTrue(action.result().evidence().path("verified").asBoolean());
    }

    @Test
    void cancelIsImmediateAndIdempotentAtSafeDriverBoundary() {
        FakeDriver driver = new FakeDriver(PrimitiveObservation.waiting(Json.object()));
        PrimitiveController action = new PrimitiveController(driver, 20, 5);
        action.start();
        assertEquals(PrimitiveState.CANCELLED, action.cancel().state());
        assertEquals(PrimitiveState.CANCELLED, action.cancel().state());
        assertEquals(1, driver.stops);
    }

    @Test
    void detectsStuckAndTimeoutWithoutUnboundedRetries() {
        FakeDriver stuck = new FakeDriver(PrimitiveObservation.waiting(Json.object()));
        PrimitiveController action = new PrimitiveController(stuck, 20, 3);
        action.start(); action.tick(); action.tick();
        assertEquals("ACTION_STUCK", action.tick().failureCode());

        FakeDriver progressing = new FakeDriver(PrimitiveObservation.progress(Json.object()));
        PrimitiveController timeout = new PrimitiveController(progressing, 2, 2);
        timeout.start(); timeout.tick(); timeout.tick();
        assertEquals("ACTION_TIMEOUT", timeout.tick().failureCode());
    }

    private static final class FakeDriver implements PrimitiveDriver {
        private final ArrayDeque<PrimitiveObservation> observations = new ArrayDeque<>();
        private PrimitiveObservation last;
        private int stops;
        FakeDriver(PrimitiveObservation... values) { for (var value : values) observations.add(value); last = values[values.length - 1]; }
        @Override public PrimitiveObservation tick() { return observations.isEmpty() ? last : observations.removeFirst(); }
        @Override public void stop() { stops++; }
    }
}
