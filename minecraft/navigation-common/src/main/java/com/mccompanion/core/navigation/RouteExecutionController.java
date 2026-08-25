package com.mccompanion.core.navigation;

import java.util.List;
import java.util.Objects;

/**
 * Loader-neutral lifecycle for following a planned route.
 *
 * <p>The controller owns target-change detection, route invalidation, local replanning,
 * waypoint progress, timeout and stuck recovery. The Loader remains responsible for live world
 * observation and vanilla player input or interaction.
 */
public final class RouteExecutionController {
    public static final Config DEFAULT_CONFIG = new Config(
            20 * 60 * 5, 3, 80, 0.45D, 1.1D, 0.04D);

    private RouteExecutionController() { }

    public interface Environment {
        GridPathPlanner.Plan plan(Position target, GridPathPlanner.Budget budget);

        boolean remainsTraversable(GridPathPlanner.Point from, GridPathPlanner.RouteStep next);
    }

    public enum Status { RUNNING, PAUSED, ARRIVED, BLOCKED }

    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("non-finite navigation position");
            }
        }

        public Position subtract(Position other) {
            return new Position(x - other.x, y - other.y, z - other.z);
        }

        public double lengthSquared() { return x * x + y * y + z * z; }

        public double distanceSquared(Position other) { return subtract(other).lengthSquared(); }

        public static Position center(GridPathPlanner.Point point) {
            return new Position(point.x() + 0.5D, point.y(), point.z() + 0.5D);
        }
    }

    public record Input(int tick, String worldKey, Position currentPosition,
                        GridPathPlanner.Point currentCell, Position targetPosition,
                        GridPathPlanner.Point targetCell, double arrivalDistanceSquared,
                        GridPathPlanner.Budget budget) {
        public Input {
            worldKey = worldKey == null ? "" : worldKey;
            Objects.requireNonNull(currentPosition, "currentPosition");
            Objects.requireNonNull(currentCell, "currentCell");
            Objects.requireNonNull(targetPosition, "targetPosition");
            Objects.requireNonNull(targetCell, "targetCell");
            budget = budget == null ? GridPathPlanner.DEFAULT_BUDGET : budget;
            if (!(arrivalDistanceSquared >= 0.0D) || !Double.isFinite(arrivalDistanceSquared)) {
                throw new IllegalArgumentException("invalid arrival distance");
            }
        }
    }

    public record Result(Status status, String code, GridPathPlanner.RouteStep step,
                         Position delta, boolean direct, boolean replanned, int replanCount) {
        public Result {
            Objects.requireNonNull(status, "status");
            code = code == null ? "" : code;
            if (replanCount < 0) throw new IllegalArgumentException("negative replan count");
        }
    }

    public record Config(int timeoutTicks, int maxReplans, int stuckTicks,
                         double waypointHorizontalDistanceSquared,
                         double waypointVerticalTolerance, double progressEpsilon) {
        public Config {
            if (timeoutTicks < 1 || maxReplans < 0 || stuckTicks < 1
                    || !(waypointHorizontalDistanceSquared > 0.0D)
                    || !(waypointVerticalTolerance > 0.0D) || !(progressEpsilon >= 0.0D)) {
                throw new IllegalArgumentException("invalid route execution config");
            }
        }
    }

    public record Snapshot(List<GridPathPlanner.RouteStep> route, GridPathPlanner.Point goal,
                           int waypointIndex, double bestWaypointDistanceSquared,
                           int stagnantTicks, int replanCount, int startedTick,
                           int pausedTicks, int pausedAtTick, String worldAtStart,
                           int consumedRiskUnits, int consumedActionSteps) {
        public Snapshot {
            route = route == null ? List.of() : List.copyOf(route);
            worldAtStart = worldAtStart == null ? "" : worldAtStart;
            if (consumedRiskUnits < 0 || consumedActionSteps < 0) {
                throw new IllegalArgumentException("negative consumed navigation budget");
            }
        }

        public Snapshot(List<GridPathPlanner.RouteStep> route, GridPathPlanner.Point goal,
                        int waypointIndex, double bestWaypointDistanceSquared,
                        int stagnantTicks, int replanCount, int startedTick,
                        int pausedTicks, int pausedAtTick, String worldAtStart) {
            this(route, goal, waypointIndex, bestWaypointDistanceSquared, stagnantTicks,
                    replanCount, startedTick, pausedTicks, pausedAtTick, worldAtStart, 0, 0);
        }
    }

    public static final class Session {
        private final Config config;
        private List<GridPathPlanner.RouteStep> route = List.of();
        private GridPathPlanner.Point goal;
        private int waypointIndex;
        private double bestWaypointDistanceSquared = Double.POSITIVE_INFINITY;
        private int stagnantTicks;
        private int replanCount;
        private final int startedTick;
        private int pausedTicks;
        private int pausedAtTick = -1;
        private String worldAtStart;
        private int consumedRiskUnits;
        private int consumedActionSteps;

        public Session(int startedTick, String worldAtStart) {
            this(startedTick, worldAtStart, DEFAULT_CONFIG);
        }

        public Session(int startedTick, String worldAtStart, Config config) {
            this.startedTick = startedTick;
            this.worldAtStart = worldAtStart == null ? "" : worldAtStart;
            this.config = Objects.requireNonNull(config, "config");
        }

        public Result tick(Input input, Environment environment) {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(environment, "environment");
            if (pausedAtTick >= 0) return result(Status.PAUSED, "PAUSED", null, null, false, false);
            if (!worldAtStart.equals(input.worldKey())) {
                return result(Status.BLOCKED, "WORLD_CHANGED", null, null, false, false);
            }
            if (input.tick() - startedTick - pausedTicks > config.timeoutTicks()) {
                return result(Status.BLOCKED, "BEHAVIOR_TIMEOUT", null, null, false, false);
            }
            Position targetDelta = input.targetPosition().subtract(input.currentPosition());
            if (targetDelta.lengthSquared() <= input.arrivalDistanceSquared()) {
                return result(Status.ARRIVED, "ARRIVED", null, targetDelta, false, false);
            }

            // Consume an observed waypoint before validating the next edge. During a jump the
            // player's block cell can already equal the landing waypoint; validating that as a
            // zero-length current-cell -> landing edge incorrectly reports the route unreachable.
            advanceReachedWaypoints(input.currentPosition());
            boolean targetMoved = !input.targetCell().equals(goal);
            boolean routeMissing = waypointIndex >= route.size();
            GridPathPlanner.Point plannedFrom = waypointIndex == 0
                    ? input.currentCell() : route.get(waypointIndex - 1).point();
            boolean routeInvalid = !routeMissing
                    && !environment.remainsTraversable(plannedFrom, route.get(waypointIndex));
            boolean replanned = false;
            if (targetMoved || routeMissing || routeInvalid) {
                if (routeInvalid && ++replanCount > config.maxReplans()) {
                    return result(Status.BLOCKED, "STUCK", null, null, false, false);
                }
                String failure = replan(input, environment);
                if (failure != null) return result(Status.BLOCKED, failure, null, null, false, false);
                replanned = true;
            }

            if (waypointIndex >= route.size()) {
                return result(Status.RUNNING, "DIRECT_TO_TARGET", null, targetDelta, true, replanned);
            }

            GridPathPlanner.RouteStep step = route.get(waypointIndex);
            Position delta = Position.center(step.point()).subtract(input.currentPosition());
            double distanceSquared = delta.lengthSquared();
            if (distanceSquared + config.progressEpsilon() < bestWaypointDistanceSquared) {
                bestWaypointDistanceSquared = distanceSquared;
                stagnantTicks = 0;
            } else if (++stagnantTicks >= config.stuckTicks()) {
                if (++replanCount > config.maxReplans()) {
                    return result(Status.BLOCKED, "STUCK", null, null, false, false);
                }
                String failure = replan(input, environment);
                if (failure != null) return result(Status.BLOCKED, failure, null, null, false, false);
                replanned = true;
                if (route.isEmpty()) {
                    return result(Status.RUNNING, "DIRECT_TO_TARGET", null, targetDelta, true, true);
                }
                step = route.get(waypointIndex);
                delta = Position.center(step.point()).subtract(input.currentPosition());
            }
            return result(Status.RUNNING, replanned ? "REPLANNED" : "RUNNING",
                    step, delta, false, replanned);
        }

        public void pause(int tick) {
            if (pausedAtTick < 0) pausedAtTick = tick;
        }

        public void resume(int tick) {
            if (pausedAtTick >= 0) {
                pausedTicks += Math.max(0, tick - pausedAtTick);
                pausedAtTick = -1;
            }
        }

        /** World actions are bounded work on the current waypoint, not evidence of being stuck. */
        public void holdForAction() {
            stagnantTicks = 0;
        }

        /** Invalidates the current route after a bounded world-action observation changed. */
        public boolean invalidateForReplan() {
            if (++replanCount > config.maxReplans()) return false;
            route = List.of();
            waypointIndex = 0;
            bestWaypointDistanceSquared = Double.POSITIVE_INFINITY;
            stagnantTicks = 0;
            return true;
        }

        public Snapshot snapshot() {
            return new Snapshot(route, goal, waypointIndex, bestWaypointDistanceSquared,
                    stagnantTicks, replanCount, startedTick, pausedTicks, pausedAtTick, worldAtStart,
                    consumedRiskUnits, consumedActionSteps);
        }

        public static Session restore(Snapshot snapshot, Config config) {
            Objects.requireNonNull(snapshot, "snapshot");
            Session session = new Session(snapshot.startedTick(), snapshot.worldAtStart(), config);
            session.route = snapshot.route();
            session.goal = snapshot.goal();
            session.waypointIndex = Math.max(0, Math.min(snapshot.waypointIndex(), session.route.size()));
            session.bestWaypointDistanceSquared = snapshot.bestWaypointDistanceSquared();
            session.stagnantTicks = Math.max(0, snapshot.stagnantTicks());
            session.replanCount = Math.max(0, snapshot.replanCount());
            session.pausedTicks = Math.max(0, snapshot.pausedTicks());
            session.pausedAtTick = snapshot.pausedAtTick();
            session.consumedRiskUnits = snapshot.consumedRiskUnits();
            session.consumedActionSteps = snapshot.consumedActionSteps();
            return session;
        }

        private void advanceReachedWaypoints(Position current) {
            while (waypointIndex < route.size()) {
                Position waypoint = Position.center(route.get(waypointIndex).point());
                double dx = current.x() - waypoint.x();
                double dz = current.z() - waypoint.z();
                if (dx * dx + dz * dz > config.waypointHorizontalDistanceSquared()
                        || Math.abs(current.y() - waypoint.y()) > config.waypointVerticalTolerance()) break;
                GridPathPlanner.RouteStep reached = route.get(waypointIndex);
                consumedRiskUnits += reached.riskUnits();
                consumedActionSteps += reached.actions().size();
                waypointIndex++;
                bestWaypointDistanceSquared = Double.POSITIVE_INFINITY;
                stagnantTicks = 0;
            }
        }

        private String replan(Input input, Environment environment) {
            GridPathPlanner.Budget remaining = new GridPathPlanner.Budget(
                    input.budget().maxBrokenBlocks(), input.budget().maxPlacedBlocks(),
                    Math.max(0, input.budget().maxRiskUnits() - consumedRiskUnits),
                    Math.max(0, input.budget().maxActionSteps() - consumedActionSteps));
            GridPathPlanner.Plan plan = environment.plan(input.targetPosition(), remaining);
            if (plan.status() != GridPathPlanner.Status.READY) {
                return switch (plan.status()) {
                    case TARGET_UNLOADED -> "TARGET_CHUNK_UNLOADED";
                    case OUT_OF_RANGE -> "TARGET_OUT_OF_RANGE";
                    case BUDGET_EXCEEDED -> "PATH_BUDGET_EXCEEDED";
                    case UNREACHABLE -> "PATH_UNREACHABLE";
                    case READY -> null;
                };
            }
            goal = input.targetCell();
            route = plan.steps();
            waypointIndex = 0;
            bestWaypointDistanceSquared = Double.POSITIVE_INFINITY;
            stagnantTicks = 0;
            return null;
        }

        private Result result(Status status, String code, GridPathPlanner.RouteStep step,
                              Position delta, boolean direct, boolean replanned) {
            return new Result(status, code, step, delta, direct, replanned, replanCount);
        }
    }
}
