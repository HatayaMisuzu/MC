package com.mccompanion.core.body.daily.navigation;

import com.mccompanion.core.navigation.GridPathPlanner;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded, pauseable controller for continuous daily-action navigation.
 *
 * <p>The caller supplies the tick clock and a version adapter. This class handles arrival,
 * dynamic goals, route invalidation, local replanning, stuck recovery, timeout and lifecycle
 * outcomes; it never teleports, edits blocks or invents movement success.
 */
public final class DailyNavigator {
    public static final Config DEFAULT_CONFIG = new Config(1.5D, 3, 80, 20 * 60 * 5, 0.04D);

    private final NavigationPort port;
    private final Config config;
    private Lifecycle state = Lifecycle.IDLE;
    private Supplier<Goal> goalSupplier;
    private Goal goal;
    private List<GridPathPlanner.Point> route = List.of();
    private int routeIndex;
    private int replans;
    private int stagnantTicks;
    private int startedTick;
    private int pausedAtTick = -1;
    private int pausedTicks;
    private double bestDistanceSquared = Double.POSITIVE_INFINITY;
    private String worldAtStart;
    private NavigationResult last = NavigationResult.idle();

    public DailyNavigator(NavigationPort port) {
        this(port, DEFAULT_CONFIG);
    }

    public DailyNavigator(NavigationPort port, Config config) {
        this.port = Objects.requireNonNull(port, "port");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start(NavPoint target, int tick) {
        start(() -> new Goal(target, port.worldKey()), tick);
    }

    public void start(Supplier<Goal> dynamicGoal, int tick) {
        goalSupplier = Objects.requireNonNull(dynamicGoal, "dynamicGoal");
        goal = null;
        route = List.of();
        routeIndex = 0;
        replans = 0;
        stagnantTicks = 0;
        startedTick = tick;
        pausedAtTick = -1;
        pausedTicks = 0;
        bestDistanceSquared = Double.POSITIVE_INFINITY;
        worldAtStart = port.worldKey();
        state = Lifecycle.RUNNING;
        last = NavigationResult.running(0, 0, "STARTED");
    }

    public NavigationResult tick(int tick) {
        if (state == Lifecycle.IDLE) return NavigationResult.idle();
        if (state == Lifecycle.PAUSED) return last.withStatus(Status.PAUSED, "PAUSED");
        if (state == Lifecycle.CANCELLED || state.isTerminal()) return last;
        if (!Objects.equals(worldAtStart, port.worldKey())) return finish(Status.WORLD_CHANGED, "WORLD_CHANGED");
        int activeTicks = tick - startedTick - pausedTicks;
        if (pausedAtTick >= 0) activeTicks -= Math.max(0, tick - pausedAtTick);
        if (activeTicks > config.timeoutTicks()) return finish(Status.TIMEOUT, "TIMEOUT");

        Goal currentGoal;
        try {
            currentGoal = Objects.requireNonNull(goalSupplier.get(), "dynamic goal");
        } catch (RuntimeException error) {
            return finish(Status.WORLD_CHANGED, "WORLD_CHANGED");
        }
        if (!Objects.equals(currentGoal.worldKey(), port.worldKey())) {
            return finish(Status.WORLD_CHANGED, "WORLD_CHANGED");
        }
        boolean targetMoved = goal == null || !currentGoal.target().equals(goal.target());
        goal = currentGoal;

        NavPoint current = port.currentPoint();
        double distanceSquared = port.currentPosition().distanceSquared(goal.target().center());
        if (distanceSquared <= config.arrivalDistance() * config.arrivalDistance()) {
            return finish(Status.ARRIVED, "ARRIVED");
        }

        while (routeIndex < route.size()) {
            NavPoint waypoint = NavPoint.from(route.get(routeIndex));
            if (!current.equals(waypoint)
                    && port.currentPosition().distanceSquared(waypoint.center()) > 0.36D) break;
            routeIndex++;
            stagnantTicks = 0;
            bestDistanceSquared = Double.POSITIVE_INFINITY;
        }
        boolean routeMissing = routeIndex >= route.size();
        boolean routeInvalid = !routeMissing && !port.traversable(current, NavPoint.from(route.get(routeIndex)));
        if (targetMoved || routeMissing || routeInvalid) {
            if (routeInvalid && replans >= config.maxReplans()) return finish(Status.UNREACHABLE, "UNREACHABLE");
            NavigationResult replanned = replan(current, tick, routeInvalid ? "ROUTE_INVALID" : "REPLAN");
            if (replanned.status() != Status.RUNNING) return replanned;
        }

        if (routeIndex >= route.size()) {
            NavigationResult replanned = replan(current, tick, "ROUTE_EXHAUSTED");
            if (replanned.status() != Status.RUNNING) return replanned;
        }

        NavPoint next = NavPoint.from(route.get(routeIndex));
        if (!port.openDoor(next)) {
            return finish(Status.UNREACHABLE, "PASSAGE_INTERACTION_FAILED");
        }
        Vec delta = next.center().subtract(port.currentPosition());
        double nextDistance = delta.lengthSquared();
        if (nextDistance + config.progressEpsilon() < bestDistanceSquared) {
            bestDistanceSquared = nextDistance;
            stagnantTicks = 0;
        } else if (++stagnantTicks >= config.stuckTicks()) {
            if (replans >= config.maxReplans()) return finish(Status.STUCK, "STUCK");
            NavigationResult recovered = replan(current, tick, "STUCK_RECOVERY");
            if (recovered.status() != Status.RUNNING) return recovered;
            return recovered;
        }
        port.applyMove(delta.normalizedHorizontal(), next.y() > current.y());
        last = running("RUNNING");
        return last;
    }

    public void pause() {
        pause(-1);
    }

    public void pause(int tick) {
        if (state == Lifecycle.RUNNING) {
            state = Lifecycle.PAUSED;
            pausedAtTick = tick;
            port.stop();
            last = last.withStatus(Status.PAUSED, "PAUSED");
        }
    }

    public void resume() {
        resume(-1);
    }

    public void resume(int tick) {
        if (state == Lifecycle.PAUSED) {
            if (pausedAtTick >= 0 && tick >= pausedAtTick) pausedTicks += tick - pausedAtTick;
            pausedAtTick = -1;
            state = Lifecycle.RUNNING;
            last = last.withStatus(Status.RUNNING, "RESUMED");
        }
    }

    public NavigationResult cancel() {
        if (!state.isTerminal() && state != Lifecycle.IDLE) {
            state = Lifecycle.CANCELLED;
            port.stop();
            last = last.withStatus(Status.CANCELLED, "CANCELLED");
        }
        return last;
    }

    public Snapshot snapshot() {
        return new Snapshot(state, goal, route, routeIndex, replans, stagnantTicks,
                startedTick, pausedTicks, pausedAtTick, worldAtStart, last);
    }

    /** Restores a paused or running action without replaying a completed route segment. */
    public void restore(Snapshot snapshot, Supplier<Goal> dynamicGoal) {
        Objects.requireNonNull(snapshot, "snapshot");
        goalSupplier = Objects.requireNonNull(dynamicGoal, "dynamicGoal");
        state = snapshot.state();
        goal = snapshot.goal();
        route = List.copyOf(snapshot.route());
        routeIndex = Math.max(0, Math.min(snapshot.waypointIndex(), route.size()));
        replans = snapshot.replans();
        stagnantTicks = snapshot.stagnantTicks();
        startedTick = snapshot.startedTick();
        pausedTicks = snapshot.pausedTicks();
        pausedAtTick = snapshot.pausedAtTick();
        worldAtStart = snapshot.worldAtStart();
        last = snapshot.last();
    }

    private NavigationResult replan(NavPoint current, int tick, String reason) {
        if (replans > config.maxReplans()) return finish(Status.UNREACHABLE, "REPLAN_LIMIT");
        GridPathPlanner.Plan plan = planGoalOrReachableNeighbor(current, goal.target());
        if (plan.status() != GridPathPlanner.Status.READY) {
            return finish(switch (plan.status()) {
                        case TARGET_UNLOADED -> Status.TARGET_UNLOADED;
                        case BUDGET_EXCEEDED -> Status.BUDGET_EXCEEDED;
                        default -> Status.UNREACHABLE;
                    }, switch (plan.status()) {
                        case TARGET_UNLOADED -> "TARGET_UNLOADED";
                        case BUDGET_EXCEEDED -> "BUDGET_EXCEEDED";
                        default -> "UNREACHABLE";
                    });
        }
        replans++;
        route = plan.points();
        routeIndex = 0;
        stagnantTicks = 0;
        bestDistanceSquared = Double.POSITIVE_INFINITY;
        last = running(reason);
        return last;
    }

    /**
     * Interaction goals are often occupied blocks (beds, brewing stands, crops). A player only
     * needs to reach interaction distance, so if the exact cell is blocked, route to the closest
     * reachable neighboring cell that is still inside the configured arrival radius.
     */
    private GridPathPlanner.Plan planGoalOrReachableNeighbor(NavPoint current, NavPoint target) {
        GridPathPlanner.Plan direct = port.plan(current, target);
        if (direct.status() != GridPathPlanner.Status.UNREACHABLE) return direct;
        GridPathPlanner.Plan best = null;
        NavPoint bestTarget = null;
        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int zOffset = -1; zOffset <= 1; zOffset++) {
                if (xOffset == 0 && zOffset == 0) continue;
                NavPoint candidate = new NavPoint(target.x() + xOffset, target.y(), target.z() + zOffset);
                if (candidate.center().distanceSquared(target.center())
                        > config.arrivalDistance() * config.arrivalDistance()) continue;
                GridPathPlanner.Plan alternative = port.plan(current, candidate);
                if (alternative.status() != GridPathPlanner.Status.READY) continue;
                if (best == null || alternative.points().size() < best.points().size()
                        || alternative.points().size() == best.points().size()
                        && candidate.compareTo(bestTarget) < 0) {
                    best = alternative;
                    bestTarget = candidate;
                }
            }
        }
        return best == null ? direct : best;
    }

    private NavigationResult finish(Status terminal, String code) {
        port.stop();
        state = terminal == Status.CANCELLED ? Lifecycle.CANCELLED : Lifecycle.TERMINAL;
        last = new NavigationResult(terminal, code, replans, stagnantTicks, routeIndex, route.size());
        return last;
    }

    public enum Status {
        IDLE, RUNNING, PAUSED, ARRIVED, UNREACHABLE, TARGET_UNLOADED, BUDGET_EXCEEDED, STUCK, TIMEOUT,
        WORLD_CHANGED, CANCELLED
    }

    public enum Lifecycle {
        IDLE, RUNNING, PAUSED, TERMINAL, CANCELLED;

        boolean isTerminal() { return this == TERMINAL || this == CANCELLED; }
    }

    public record Goal(NavPoint target, String worldKey) {
        public Goal {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(worldKey, "worldKey");
        }
    }

    public record Config(double arrivalDistance, int maxReplans, int stuckTicks,
                         int timeoutTicks, double progressEpsilon) {
        public Config {
            if (!(arrivalDistance > 0) || maxReplans < 0 || stuckTicks < 1 || timeoutTicks < 1
                    || !(progressEpsilon >= 0)) throw new IllegalArgumentException("invalid navigation limits");
        }
    }

    public record NavigationResult(Status status, String code, int replans, int stagnantTicks,
                                   int waypointIndex, int waypointCount) {
        static NavigationResult idle() { return new NavigationResult(Status.IDLE, "IDLE", 0, 0, 0, 0); }
        static NavigationResult running(int replans, int stagnantTicks, String code) {
            return new NavigationResult(Status.RUNNING, code, replans, stagnantTicks, 0, 0);
        }
        NavigationResult withStatus(Status next, String nextCode) {
            return new NavigationResult(next, nextCode, replans, stagnantTicks, waypointIndex, waypointCount);
        }
    }

    private NavigationResult running(String code) {
        return new NavigationResult(Status.RUNNING, code, replans, stagnantTicks, routeIndex, route.size());
    }

    public record Snapshot(Lifecycle state, Goal goal, List<GridPathPlanner.Point> route, int waypointIndex,
                           int replans, int stagnantTicks, int startedTick, int pausedTicks,
                           int pausedAtTick, String worldAtStart, NavigationResult last) {
        public Snapshot {
            route = List.copyOf(route);
        }
    }
}
