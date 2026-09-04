package com.mccompanion.core.navigation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Loader-neutral sequencing, budgets and postcondition rules for route world actions. */
public final class NavigationActionController {
    private static final int MAX_BREAK_TICKS = 100;

    private NavigationActionController() { }

    public interface Environment {
        Observation observe(GridPathPlanner.WorldAction action);

        double breakProgress(GridPathPlanner.WorldAction action);

        boolean breakBlock(GridPathPlanner.WorldAction action);

        default boolean placeBlock(GridPathPlanner.WorldAction action) { return false; }
    }

    public enum ObservedState { EXPECTED, SATISFIED, CHANGED, UNSAFE, UNAVAILABLE }

    public record Observation(ObservedState state, String code) {
        public Observation {
            Objects.requireNonNull(state, "state");
            code = code == null ? "" : code;
        }

        public static Observation expected() { return new Observation(ObservedState.EXPECTED, ""); }

        public static Observation satisfied() { return new Observation(ObservedState.SATISFIED, ""); }

        public static Observation changed() { return new Observation(ObservedState.CHANGED, ""); }

        public static Observation unsafe(String code) {
            return new Observation(ObservedState.UNSAFE, code);
        }

        public static Observation unavailable(String code) {
            return new Observation(ObservedState.UNAVAILABLE, code);
        }
    }

    public enum Status { READY_TO_MOVE, RUNNING, REPLAN, BLOCKED }

    public record Result(Status status, String code, GridPathPlanner.WorldAction action,
                         int brokenBlocks, List<GridPathPlanner.WorldAction> destroyed,
                         int placedBlocks, List<GridPathPlanner.WorldAction> placed,
                         boolean mutationOccurred) {
        public Result {
            Objects.requireNonNull(status, "status");
            code = code == null ? "" : code;
            destroyed = destroyed == null ? List.of() : List.copyOf(destroyed);
            placed = placed == null ? List.of() : List.copyOf(placed);
        }
    }

    public static final class Session {
        private final SurvivalNavigationPolicy policy;
        private final Set<ActionKey> satisfied = new HashSet<>();
        private final List<GridPathPlanner.WorldAction> destroyed = new ArrayList<>();
        private final List<GridPathPlanner.WorldAction> placed = new ArrayList<>();
        private ActionKey active;
        private double progress;
        private int activeTicks;
        private int brokenBlocks;
        private int placedBlocks;

        public Session(SurvivalNavigationPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        public Result tick(GridPathPlanner.RouteStep step, Environment environment) {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(environment, "environment");
            for (GridPathPlanner.WorldAction action : step.actions()) {
                if (action.type() == GridPathPlanner.ActionType.OPEN_DOOR) continue;
                Result result = switch (action.type()) {
                    case BREAK_BLOCK -> tickBreak(action, environment);
                    case PLACE_BLOCK -> tickPlace(action, environment);
                    case OPEN_DOOR -> result(Status.READY_TO_MOVE, "DOOR_ACTION_DEFERRED", action, false);
                };
                if (result.status() != Status.READY_TO_MOVE) return result;
            }
            clearActive();
            return result(Status.READY_TO_MOVE, "ACTIONS_VERIFIED", null, false);
        }

        public int brokenBlocks() { return brokenBlocks; }

        public List<GridPathPlanner.WorldAction> destroyed() { return List.copyOf(destroyed); }

        public int placedBlocks() { return placedBlocks; }

        public List<GridPathPlanner.WorldAction> placed() { return List.copyOf(placed); }

        private Result tickBreak(GridPathPlanner.WorldAction action, Environment environment) {
            ActionKey key = ActionKey.of(action);
            Observation observation = environment.observe(action);
            if (satisfied.contains(key)) {
                if (observation.state() == ObservedState.SATISFIED) {
                    return result(Status.READY_TO_MOVE, "ACTION_ALREADY_VERIFIED", action, false);
                }
                satisfied.remove(key);
            }
            if (observation.state() == ObservedState.SATISFIED) {
                satisfied.add(key);
                clearActive();
                return result(Status.READY_TO_MOVE, "ACTION_SATISFIED_EXTERNALLY", action, false);
            }
            if (observation.state() == ObservedState.CHANGED) {
                clearActive();
                return result(Status.REPLAN, "PATH_ACTION_INVALIDATED", action, false);
            }
            if (observation.state() == ObservedState.UNAVAILABLE) {
                clearActive();
                return result(Status.BLOCKED,
                        observation.code().isBlank() ? "PATH_ACTION_UNAVAILABLE" : observation.code(),
                        action, false);
            }
            if (observation.state() == ObservedState.UNSAFE) {
                clearActive();
                return result(Status.BLOCKED,
                        observation.code().isBlank() ? "NAVIGATION_BREAK_UNSAFE" : observation.code(),
                        action, false);
            }
            if (!policy.allowsBreak(action.expectedBlockId())) {
                clearActive();
                return result(Status.BLOCKED, "NAVIGATION_BREAK_NOT_ALLOWED", action, false);
            }
            if (brokenBlocks >= policy.maxBreakBlocks()) {
                clearActive();
                return result(Status.BLOCKED, "PATH_BREAK_BUDGET_EXCEEDED", action, false);
            }
            if (!key.equals(active)) {
                active = key;
                progress = 0.0D;
                activeTicks = 0;
            }
            double increment = environment.breakProgress(action);
            if (!(increment > 0.0D) || !Double.isFinite(increment)) {
                clearActive();
                return result(Status.BLOCKED, "BLOCK_UNBREAKABLE", action, false);
            }
            if (++activeTicks > MAX_BREAK_TICKS) {
                clearActive();
                return result(Status.BLOCKED, "NAVIGATION_BREAK_TIMEOUT", action, false);
            }
            progress += increment;
            if (progress < 1.0D) {
                return result(Status.RUNNING, "BREAKING", action, false);
            }
            if (!environment.breakBlock(action)) {
                Observation afterRejected = environment.observe(action);
                clearActive();
                return afterRejected.state() == ObservedState.SATISFIED
                        || afterRejected.state() == ObservedState.CHANGED
                        ? result(Status.REPLAN, "PATH_ACTION_INVALIDATED", action, false)
                        : result(Status.BLOCKED, "BLOCK_BREAK_REJECTED", action, false);
            }
            Observation after = environment.observe(action);
            if (after.state() != ObservedState.SATISFIED) {
                clearActive();
                return result(Status.BLOCKED, "UNCERTAIN_EFFECT", action, false);
            }
            brokenBlocks++;
            destroyed.add(action);
            satisfied.add(key);
            clearActive();
            return result(Status.RUNNING, "BLOCK_BREAK_VERIFIED", action, true);
        }

        private Result tickPlace(GridPathPlanner.WorldAction action, Environment environment) {
            ActionKey key = ActionKey.of(action);
            Observation observation = environment.observe(action);
            if (satisfied.contains(key)) {
                if (observation.state() == ObservedState.SATISFIED) {
                    return result(Status.READY_TO_MOVE, "ACTION_ALREADY_VERIFIED", action, false);
                }
                satisfied.remove(key);
            }
            if (observation.state() == ObservedState.SATISFIED) {
                satisfied.add(key);
                return result(Status.READY_TO_MOVE, "ACTION_SATISFIED_EXTERNALLY", action, false);
            }
            if (observation.state() == ObservedState.CHANGED) {
                return result(Status.REPLAN, "PATH_ACTION_INVALIDATED", action, false);
            }
            if (observation.state() == ObservedState.UNAVAILABLE) {
                return result(Status.BLOCKED,
                        observation.code().isBlank() ? "PATH_ACTION_UNAVAILABLE" : observation.code(),
                        action, false);
            }
            if (observation.state() == ObservedState.UNSAFE) {
                return result(Status.BLOCKED,
                        observation.code().isBlank() ? "NAVIGATION_PLACE_UNSAFE" : observation.code(),
                        action, false);
            }
            if (!policy.allowsPlace(action.expectedBlockId())) {
                return result(Status.BLOCKED, "NAVIGATION_PLACE_NOT_ALLOWED", action, false);
            }
            if (placedBlocks >= policy.maxPlaceBlocks()) {
                return result(Status.BLOCKED, "PATH_PLACE_BUDGET_EXCEEDED", action, false);
            }
            if (!environment.placeBlock(action)) {
                Observation afterRejected = environment.observe(action);
                return afterRejected.state() == ObservedState.SATISFIED
                        || afterRejected.state() == ObservedState.CHANGED
                        ? result(Status.REPLAN, "PATH_ACTION_INVALIDATED", action, false)
                        : result(Status.BLOCKED, "BLOCK_PLACE_REJECTED", action, false);
            }
            Observation after = environment.observe(action);
            if (after.state() != ObservedState.SATISFIED) {
                return result(Status.BLOCKED, "UNCERTAIN_EFFECT", action, false);
            }
            placedBlocks++;
            placed.add(action);
            satisfied.add(key);
            return result(Status.RUNNING, "BLOCK_PLACE_VERIFIED", action, true);
        }

        private Result result(Status status, String code, GridPathPlanner.WorldAction action,
                              boolean mutationOccurred) {
            return new Result(status, code, action, brokenBlocks, destroyed,
                    placedBlocks, placed, mutationOccurred);
        }

        private void clearActive() {
            active = null;
            progress = 0.0D;
            activeTicks = 0;
        }
    }

    private record ActionKey(GridPathPlanner.ActionType type, GridPathPlanner.Point position,
                             String expectedBlockId) {
        private static ActionKey of(GridPathPlanner.WorldAction action) {
            return new ActionKey(action.type(), action.position(), action.expectedBlockId());
        }
    }
}
