package com.mccompanion.core.body.menu;

import java.util.Set;

/** One bounded invocation, followed by observation. A reconnect/retry must create a fresh inspected handle. */
public final class MenuActionController {
    private MenuActionController() { }
    public enum State { ACCEPTED, WAITING_OBSERVATION, SUCCESS, PRECONDITION_FAILED, FAILED, UNKNOWN, CANCELLED }
    public record Request(String handle, String action, Integer slot, Integer button) { }
    public record Result(State state, String code) {
        public boolean terminal() { return state != State.ACCEPTED && state != State.WAITING_OBSERVATION; }
        public boolean success() { return state == State.SUCCESS; }
    }

    public static final class Session {
        private final Request request;
        private final long startedTick;
        private Result result = new Result(State.ACCEPTED, "ACCEPTED");
        private InventoryMenuPort.View before;
        private InventoryMenuPort.Mutation mutation;
        private long dispatchedTick;

        public Session(Request request, long tick) {
            this.request = java.util.Objects.requireNonNull(request);
            this.startedTick = tick;
        }
        public Request request() { return request; }
        public Result result() { return result; }
        public Result cancel() {
            if (!result.terminal()) result = new Result(State.CANCELLED,
                    mutation == null ? "CANCELLED" : "CANCELLED_EFFECT_REQUIRES_RECONCILIATION");
            return result;
        }
        public Result tick(InventoryMenuPort port, long tick) {
            if (result.terminal()) return result;
            port.stopInput();
            if (tick < startedTick || tick - startedTick > 20) {
                return finish(mutation == null ? State.FAILED : State.UNKNOWN, "MENU_ACTION_TIMEOUT");
            }
            if (mutation == null) {
                if (request.action() == null || !Set.of("CLICK", "QUICK_MOVE", "CLOSE").contains(request.action())) {
                    return finish(State.PRECONDITION_FAILED, "MENU_ACTION_INVALID");
                }
                var valid = port.validate(request.handle());
                if (!valid.valid()) return finish(State.PRECONDITION_FAILED, valid.code());
                if (!request.action().equals("CLOSE") && (request.slot() == null || request.slot() < 0
                        || request.slot() >= valid.slotCount() || request.slot() > 127)) {
                    return finish(State.PRECONDITION_FAILED, "MENU_SLOT_INVALID");
                }
                if (request.action().equals("CLICK") && (request.button() == null || request.button() < 0 || request.button() > 1)) {
                    return finish(State.PRECONDITION_FAILED, "MENU_BUTTON_INVALID");
                }
                before = port.observe();
                if (!before.open()) return finish(State.PRECONDITION_FAILED, "MENU_SESSION_CHANGED");
                mutation = port.apply(request);
                dispatchedTick = tick;
                if (!mutation.accepted()) return finish(State.PRECONDITION_FAILED, mutation.code());
                return finish(State.WAITING_OBSERVATION, "WAITING_OBSERVATION");
            }
            if (tick == dispatchedTick) return result;
            var after = port.observe();
            if (request.action().equals("CLOSE")) {
                return finish(!after.open() ? State.SUCCESS : State.UNKNOWN,
                        !after.open() ? "MENU_ACTION_COMPLETE" : "MENU_CLOSE_FAILED");
            }
            if (!after.open() || !before.identity().equals(after.identity())) {
                return finish(State.UNKNOWN, "MENU_SESSION_CHANGED_AFTER_ACTION");
            }
            return finish(mutation.effectObserved() ? State.SUCCESS : State.UNKNOWN,
                    mutation.effectObserved() ? "MENU_ACTION_COMPLETE" : "UNCERTAIN_EFFECT");
        }
        private Result finish(State state, String code) { return result = new Result(state, code); }
    }
}
