package com.mccompanion.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RouteExecutionControllerTest {
    @Test
    void followsTypedStepsAndReplansWhenTheGoalMoves() {
        FakeEnvironment environment = new FakeEnvironment();
        RouteExecutionController.Session session = new RouteExecutionController.Session(0, "overworld");
        GridPathPlanner.Point current = new GridPathPlanner.Point(0, 0, 0);
        GridPathPlanner.Point target = new GridPathPlanner.Point(3, 0, 0);

        RouteExecutionController.Result first = session.tick(input(0, current, target), environment);
        assertEquals(RouteExecutionController.Status.RUNNING, first.status());
        assertEquals(GridPathPlanner.Movement.WALK, first.step().movement());
        assertTrue(first.replanned());

        current = first.step().point();
        target = new GridPathPlanner.Point(3, 0, 2);
        RouteExecutionController.Result moved = session.tick(input(1, current, target), environment);
        assertEquals(RouteExecutionController.Status.RUNNING, moved.status());
        assertTrue(moved.replanned());
        assertTrue(environment.plans >= 2);
    }

    @Test
    void reportsBudgetFailureWithoutReturningAMove() {
        RouteExecutionController.Session session = new RouteExecutionController.Session(0, "overworld");
        FakeEnvironment environment = new FakeEnvironment();
        environment.status = GridPathPlanner.Status.BUDGET_EXCEEDED;

        RouteExecutionController.Result result = session.tick(input(0,
                new GridPathPlanner.Point(0, 0, 0), new GridPathPlanner.Point(2, 0, 0)), environment);

        assertEquals(RouteExecutionController.Status.BLOCKED, result.status());
        assertEquals("PATH_BUDGET_EXCEEDED", result.code());
        assertEquals(null, result.step());
    }

    @Test
    void pausedTicksDoNotConsumeTimeoutBudget() {
        var config = new RouteExecutionController.Config(3, 2, 5, 0.45D, 1.1D, 0.04D);
        RouteExecutionController.Session session = new RouteExecutionController.Session(0, "overworld", config);
        FakeEnvironment environment = new FakeEnvironment();
        session.pause(1);
        assertEquals(RouteExecutionController.Status.PAUSED,
                session.tick(input(100, new GridPathPlanner.Point(0, 0, 0),
                        new GridPathPlanner.Point(4, 0, 0)), environment).status());
        session.resume(100);
        assertEquals(RouteExecutionController.Status.RUNNING,
                session.tick(input(101, new GridPathPlanner.Point(0, 0, 0),
                        new GridPathPlanner.Point(4, 0, 0)), environment).status());
    }

    @Test
    void invalidatedRouteReplansAndStuckRecoveryIsBounded() {
        var config = new RouteExecutionController.Config(100, 1, 1, 0.45D, 1.1D, 0.04D);
        RouteExecutionController.Session session = new RouteExecutionController.Session(0, "overworld", config);
        FakeEnvironment environment = new FakeEnvironment();
        GridPathPlanner.Point current = new GridPathPlanner.Point(0, 0, 0);
        GridPathPlanner.Point target = new GridPathPlanner.Point(4, 0, 0);
        session.tick(input(0, current, target), environment);

        environment.traversable = false;
        RouteExecutionController.Result invalidated = session.tick(input(1, current, target), environment);
        assertEquals(RouteExecutionController.Status.RUNNING, invalidated.status());
        assertTrue(invalidated.replanned());
        assertEquals(1, invalidated.replanCount());

        environment.traversable = true;
        RouteExecutionController.Result terminal = invalidated;
        for (int tick = 2; tick < 8 && terminal.status() == RouteExecutionController.Status.RUNNING; tick++) {
            terminal = session.tick(input(tick, current, target), environment);
        }
        assertEquals(RouteExecutionController.Status.BLOCKED, terminal.status());
        assertEquals("STUCK", terminal.code());
    }

    @Test
    void worldIdentityChangeStopsTheRoute() {
        RouteExecutionController.Session session = new RouteExecutionController.Session(0, "overworld");
        RouteExecutionController.Input changed = new RouteExecutionController.Input(1, "nether",
                RouteExecutionController.Position.center(new GridPathPlanner.Point(0, 0, 0)),
                new GridPathPlanner.Point(0, 0, 0),
                RouteExecutionController.Position.center(new GridPathPlanner.Point(2, 0, 0)),
                new GridPathPlanner.Point(2, 0, 0), 0.25D, GridPathPlanner.DEFAULT_BUDGET);

        RouteExecutionController.Result result = session.tick(changed, new FakeEnvironment());

        assertEquals(RouteExecutionController.Status.BLOCKED, result.status());
        assertEquals("WORLD_CHANGED", result.code());
    }

    @Test
    void replanningUsesOnlyTheRemainingCumulativeRiskBudget() {
        RouteExecutionController.Session session = new RouteExecutionController.Session(0, "overworld");
        GridPathPlanner.Point start = new GridPathPlanner.Point(0, 0, 0);
        GridPathPlanner.Point target = new GridPathPlanner.Point(3, 0, 0);
        RouteExecutionController.Environment risky = new RouteExecutionController.Environment() {
            @Override public GridPathPlanner.Plan plan(
                    RouteExecutionController.Position destination, GridPathPlanner.Budget budget) {
                if (budget.maxRiskUnits() < 1) {
                    return new GridPathPlanner.Plan(GridPathPlanner.Status.BUDGET_EXCEEDED, List.of(), 1);
                }
                GridPathPlanner.RouteStep step = new GridPathPlanner.RouteStep(
                        new GridPathPlanner.Point(1, 0, 0), GridPathPlanner.Movement.WALK,
                        List.of(), 1.0D, 1);
                return new GridPathPlanner.Plan(GridPathPlanner.Status.READY, List.of(step.point()), 1,
                        List.of(step), 1.0D, new GridPathPlanner.BudgetUse(0, 0, 1, 0));
            }

            @Override public boolean remainsTraversable(
                    GridPathPlanner.Point from, GridPathPlanner.RouteStep next) { return true; }
        };
        java.util.function.BiFunction<Integer, GridPathPlanner.Point, RouteExecutionController.Input> input =
                (tick, current) -> new RouteExecutionController.Input(tick, "overworld",
                        RouteExecutionController.Position.center(current), current,
                        RouteExecutionController.Position.center(target), target, 0.25D,
                        new GridPathPlanner.Budget(0, 0, 1, 4));

        session.tick(input.apply(0, start), risky);
        session.tick(input.apply(1, new GridPathPlanner.Point(1, 0, 0)), risky);
        RouteExecutionController.Result exhausted = session.tick(
                input.apply(2, new GridPathPlanner.Point(1, 0, 0)), risky);

        assertEquals(RouteExecutionController.Status.BLOCKED, exhausted.status());
        assertEquals("PATH_BUDGET_EXCEEDED", exhausted.code());
        assertEquals(1, session.snapshot().consumedRiskUnits());
    }

    @Test
    void consumesReachedGapLandingBeforeValidatingTheNextEdge() {
        RouteExecutionController.Session session = new RouteExecutionController.Session(0, "overworld");
        GridPathPlanner.Point start = new GridPathPlanner.Point(0, 0, 0);
        GridPathPlanner.Point landing = new GridPathPlanner.Point(2, 0, 0);
        GridPathPlanner.Point target = new GridPathPlanner.Point(3, 0, 0);
        RouteExecutionController.Environment gap = new RouteExecutionController.Environment() {
            int plans;

            @Override public GridPathPlanner.Plan plan(
                    RouteExecutionController.Position destination, GridPathPlanner.Budget budget) {
                if (plans++ > 0) {
                    return new GridPathPlanner.Plan(GridPathPlanner.Status.UNREACHABLE, List.of(), 1);
                }
                List<GridPathPlanner.RouteStep> steps = List.of(
                        new GridPathPlanner.RouteStep(landing, GridPathPlanner.Movement.JUMP_GAP,
                                List.of(), 2.0D, 0),
                        new GridPathPlanner.RouteStep(target, GridPathPlanner.Movement.WALK,
                                List.of(), 1.0D, 0));
                return new GridPathPlanner.Plan(GridPathPlanner.Status.READY,
                        steps.stream().map(GridPathPlanner.RouteStep::point).toList(), 1,
                        steps, 3.0D, GridPathPlanner.BudgetUse.none());
            }

            @Override public boolean remainsTraversable(
                    GridPathPlanner.Point from, GridPathPlanner.RouteStep next) {
                // Minecraft classifies a same-cell edge as blocked; reaching the landing must
                // advance the route before this validation runs.
                return !from.equals(next.point());
            }
        };

        RouteExecutionController.Result started = session.tick(input(0, start, target), gap);
        RouteExecutionController.Result landed = session.tick(input(1, landing, target), gap);
        RouteExecutionController.Result arrived = session.tick(input(2, target, target), gap);

        assertEquals(GridPathPlanner.Movement.JUMP_GAP, started.step().movement());
        assertEquals(RouteExecutionController.Status.RUNNING, landed.status());
        assertEquals(target, landed.step().point());
        assertEquals(RouteExecutionController.Status.ARRIVED, arrived.status());
        assertEquals(0, landed.replanCount());
    }

    private static RouteExecutionController.Input input(int tick, GridPathPlanner.Point current,
                                                        GridPathPlanner.Point target) {
        return new RouteExecutionController.Input(tick, "overworld",
                RouteExecutionController.Position.center(current), current,
                RouteExecutionController.Position.center(target), target, 0.25D,
                GridPathPlanner.DEFAULT_BUDGET);
    }

    private static final class FakeEnvironment implements RouteExecutionController.Environment {
        GridPathPlanner.Status status = GridPathPlanner.Status.READY;
        boolean traversable = true;
        int plans;

        @Override public GridPathPlanner.Plan plan(RouteExecutionController.Position target,
                                                   GridPathPlanner.Budget budget) {
            plans++;
            if (status != GridPathPlanner.Status.READY) {
                return new GridPathPlanner.Plan(status, List.of(), 1);
            }
            int targetX = (int) Math.floor(target.x());
            int targetY = (int) Math.floor(target.y());
            int targetZ = (int) Math.floor(target.z());
            List<GridPathPlanner.RouteStep> steps = new ArrayList<>();
            for (int x = 1; x <= targetX; x++) steps.add(step(new GridPathPlanner.Point(x, 0, 0)));
            for (int z = 1; z <= targetZ; z++) steps.add(step(new GridPathPlanner.Point(targetX, targetY, z)));
            return new GridPathPlanner.Plan(GridPathPlanner.Status.READY,
                    steps.stream().map(GridPathPlanner.RouteStep::point).toList(), 1,
                    steps, steps.size(), GridPathPlanner.BudgetUse.none());
        }

        @Override public boolean remainsTraversable(GridPathPlanner.Point from,
                                                    GridPathPlanner.RouteStep next) {
            return traversable;
        }

        private static GridPathPlanner.RouteStep step(GridPathPlanner.Point point) {
            return new GridPathPlanner.RouteStep(point, GridPathPlanner.Movement.WALK,
                    List.of(), 1.0D, 0);
        }
    }
}
