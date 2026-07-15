package com.mccompanion.runtime.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

/** start/tick/pause/resume/cancel/result lifecycle with timeout and stuck detection. */
public final class PrimitiveController {
    private final PrimitiveDriver driver;
    private final int timeoutTicks;
    private final int stuckTicks;
    private PrimitiveState state = PrimitiveState.CREATED;
    private int elapsed;
    private int stagnant;
    private String failureCode = "";
    private JsonNode evidence = Json.object();

    public PrimitiveController(PrimitiveDriver driver, int timeoutTicks, int stuckTicks) {
        if (driver == null || timeoutTicks < 1 || stuckTicks < 1 || stuckTicks > timeoutTicks) throw new IllegalArgumentException("Invalid primitive budget");
        this.driver = driver; this.timeoutTicks = timeoutTicks; this.stuckTicks = stuckTicks;
    }

    public synchronized Snapshot start() {
        require(PrimitiveState.CREATED); driver.start(); state = PrimitiveState.RUNNING; return snapshot();
    }

    public synchronized Snapshot tick() {
        if (state != PrimitiveState.RUNNING) return snapshot();
        if (++elapsed > timeoutTicks) return terminate(PrimitiveState.FAILED, "ACTION_TIMEOUT");
        PrimitiveObservation observation;
        try { observation = driver.tick(); }
        catch (RuntimeException failure) { return terminate(PrimitiveState.FAILED, "ACTION_DRIVER_ERROR"); }
        evidence = observation.evidence();
        if (!observation.failureCode().isBlank()) return terminate(PrimitiveState.FAILED, observation.failureCode());
        if (observation.complete()) return terminate(PrimitiveState.SUCCEEDED, "");
        stagnant = observation.progressed() ? 0 : stagnant + 1;
        if (stagnant >= stuckTicks) return terminate(PrimitiveState.FAILED, "ACTION_STUCK");
        return snapshot();
    }

    public synchronized Snapshot pause() { require(PrimitiveState.RUNNING); state = PrimitiveState.PAUSED; driver.stop(); return snapshot(); }
    public synchronized Snapshot resume() { require(PrimitiveState.PAUSED); state = PrimitiveState.RUNNING; driver.start(); return snapshot(); }
    public synchronized Snapshot cancel() {
        if (!state.terminal()) { driver.stop(); state = PrimitiveState.CANCELLED; failureCode = "OWNER_CANCELLED"; }
        return snapshot();
    }
    public synchronized Snapshot result() { return snapshot(); }

    private Snapshot terminate(PrimitiveState next, String code) { driver.stop(); state = next; failureCode = code; return snapshot(); }
    private void require(PrimitiveState required) { if (state != required) throw new IllegalStateException("Primitive is " + state); }
    private Snapshot snapshot() { return new Snapshot(state, elapsed, stagnant, failureCode, evidence.deepCopy()); }

    public record Snapshot(PrimitiveState state, int elapsedTicks, int stagnantTicks, String failureCode, JsonNode evidence) { }
}
