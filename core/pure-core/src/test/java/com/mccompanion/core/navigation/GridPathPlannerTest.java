package com.mccompanion.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GridPathPlannerTest {
    @Test
    void routesAroundObservedObstacleWithoutInventingAWorldMutation() {
        var blocked = Set.of(
                new GridPathPlanner.Point(2, 0, -1),
                new GridPathPlanner.Point(2, 0, 0),
                new GridPathPlanner.Point(2, 0, 1));
        GridPathPlanner.Plan plan = GridPathPlanner.plan(
                new GridPathPlanner.Point(0, 0, 0),
                new GridPathPlanner.Point(4, 0, 0),
                environment(blocked, Set.of()));

        assertEquals(GridPathPlanner.Status.READY, plan.status());
        assertEquals(
                new GridPathPlanner.Point(4, 0, 0),
                plan.points().get(plan.points().size() - 1));
        assertTrue(plan.points().stream().noneMatch(blocked::contains));
        assertTrue(plan.points().size() > 4);
    }

    @Test
    void reportsUnloadedAndUnreachableTargetsPrecisely() {
        GridPathPlanner.Point start = new GridPathPlanner.Point(0, 0, 0);
        GridPathPlanner.Point target = new GridPathPlanner.Point(2, 0, 0);
        assertEquals(
                GridPathPlanner.Status.TARGET_UNLOADED,
                GridPathPlanner.plan(start, target, environment(Set.of(), Set.of(target))).status());

        Set<GridPathPlanner.Point> blocked = new HashSet<>();
        blocked.add(new GridPathPlanner.Point(1, 0, 0));
        blocked.add(new GridPathPlanner.Point(-1, 0, 0));
        blocked.add(new GridPathPlanner.Point(0, 0, 1));
        blocked.add(new GridPathPlanner.Point(0, 0, -1));
        blocked.add(new GridPathPlanner.Point(1, 1, 0));
        blocked.add(new GridPathPlanner.Point(-1, 1, 0));
        blocked.add(new GridPathPlanner.Point(0, 1, 1));
        blocked.add(new GridPathPlanner.Point(0, 1, -1));
        blocked.add(new GridPathPlanner.Point(1, -1, 0));
        blocked.add(new GridPathPlanner.Point(-1, -1, 0));
        blocked.add(new GridPathPlanner.Point(0, -1, 1));
        blocked.add(new GridPathPlanner.Point(0, -1, -1));
        blocked.add(new GridPathPlanner.Point(0, 1, 0));
        blocked.add(new GridPathPlanner.Point(0, -1, 0));
        GridPathPlanner.Plan unreachable = GridPathPlanner.plan(start, target, environment(blocked, Set.of()));
        assertEquals(GridPathPlanner.Status.UNREACHABLE, unreachable.status());
        assertFalse(unreachable.exploredNodes() == 0);
    }

    @Test
    void choosesLowerObservedTraversalCostWithoutChangingTheWorldModel() {
        GridPathPlanner.Point start = new GridPathPlanner.Point(0, 0, 0);
        GridPathPlanner.Point target = new GridPathPlanner.Point(4, 0, 0);
        Set<GridPathPlanner.Point> expensive = Set.of(
                new GridPathPlanner.Point(1, 0, 0),
                new GridPathPlanner.Point(2, 0, 0),
                new GridPathPlanner.Point(3, 0, 0));
        GridPathPlanner.Plan plan = GridPathPlanner.plan(
                start,
                target,
                new GridPathPlanner.Environment() {
                    @Override
                    public boolean loaded(GridPathPlanner.Point point) {
                        return true;
                    }

                    @Override
                    public GridPathPlanner.Traversal traversal(
                            GridPathPlanner.Point from,
                            GridPathPlanner.Point to) {
                        if (to.y() != 0) return GridPathPlanner.Traversal.blocked();
                        return GridPathPlanner.Traversal.passable(expensive.contains(to) ? 8.0D : 1.0D);
                    }
                });

        assertEquals(GridPathPlanner.Status.READY, plan.status());
        assertTrue(plan.points().stream().noneMatch(expensive::contains));
        assertEquals(target, plan.points().get(plan.points().size() - 1));
    }

    private static GridPathPlanner.Environment environment(
            Set<GridPathPlanner.Point> blocked,
            Set<GridPathPlanner.Point> unloaded) {
        return new GridPathPlanner.Environment() {
            @Override
            public boolean loaded(GridPathPlanner.Point point) {
                return !unloaded.contains(point);
            }

            @Override
            public GridPathPlanner.Traversal traversal(
                    GridPathPlanner.Point from,
                    GridPathPlanner.Point to) {
                return blocked.contains(to) || to.y() != 0
                        ? GridPathPlanner.Traversal.blocked()
                        : GridPathPlanner.Traversal.passable(1.0D + Math.abs(to.y() - from.y()) * 0.25D);
            }
        };
    }
}
