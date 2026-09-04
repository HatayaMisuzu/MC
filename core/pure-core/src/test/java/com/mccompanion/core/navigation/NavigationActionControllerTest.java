package com.mccompanion.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NavigationActionControllerTest {
    @Test
    void requiresARealPostconditionBeforeCountingTheBreak() {
        FakeEnvironment environment = new FakeEnvironment();
        NavigationActionController.Session session = new NavigationActionController.Session(
                SurvivalNavigationPolicy.breaking(1, 8, List.of("minecraft:dirt")));
        GridPathPlanner.RouteStep step = step(action(1));

        assertEquals(NavigationActionController.Status.RUNNING,
                session.tick(step, environment).status());
        NavigationActionController.Result broken = session.tick(step, environment);
        assertEquals(NavigationActionController.Status.RUNNING, broken.status());
        assertTrue(broken.mutationOccurred());
        assertEquals(1, broken.brokenBlocks());
        assertEquals(List.of(action(1)), broken.destroyed());
        assertEquals(NavigationActionController.Status.READY_TO_MOVE,
                session.tick(step, environment).status());
    }

    @Test
    void enforcesActualMutationBudgetAcrossRouteSteps() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.increment = 1.0D;
        NavigationActionController.Session session = new NavigationActionController.Session(
                SurvivalNavigationPolicy.breaking(1, 8, List.of("minecraft:dirt")));
        assertTrue(session.tick(step(action(1)), environment).mutationOccurred());

        environment.present = true;
        NavigationActionController.Result exceeded = session.tick(step(action(2)), environment);

        assertEquals(NavigationActionController.Status.BLOCKED, exceeded.status());
        assertEquals("PATH_BREAK_BUDGET_EXCEEDED", exceeded.code());
        assertFalse(exceeded.mutationOccurred());
    }

    @Test
    void changedOrUnsafeWorldStateNeverBecomesSuccess() {
        NavigationActionController.Session session = new NavigationActionController.Session(
                SurvivalNavigationPolicy.breaking(1, 8, List.of("minecraft:dirt")));
        FakeEnvironment changed = new FakeEnvironment();
        changed.observation = NavigationActionController.Observation.changed();
        assertEquals(NavigationActionController.Status.REPLAN,
                session.tick(step(action(1)), changed).status());

        FakeEnvironment unsafe = new FakeEnvironment();
        unsafe.observation = NavigationActionController.Observation.unsafe("PROTECTED_BLOCK");
        NavigationActionController.Result rejected = session.tick(step(action(1)), unsafe);
        assertEquals(NavigationActionController.Status.BLOCKED, rejected.status());
        assertEquals("PROTECTED_BLOCK", rejected.code());
    }

    @Test
    void countsPlacementOnlyAfterTheExactBlockAppears() {
        SurvivalNavigationPolicy policy = new SurvivalNavigationPolicy(
                0, 1, 8, java.util.Set.of(), java.util.Set.of("minecraft:cobblestone"));
        NavigationActionController.Session session = new NavigationActionController.Session(policy);
        GridPathPlanner.WorldAction placement = new GridPathPlanner.WorldAction(
                GridPathPlanner.ActionType.PLACE_BLOCK,
                new GridPathPlanner.Point(1, -1, 0), "minecraft:cobblestone");
        boolean[] present = {false};
        NavigationActionController.Environment environment = new NavigationActionController.Environment() {
            @Override public NavigationActionController.Observation observe(GridPathPlanner.WorldAction action) {
                return present[0] ? NavigationActionController.Observation.satisfied()
                        : NavigationActionController.Observation.expected();
            }

            @Override public double breakProgress(GridPathPlanner.WorldAction action) { return 0; }

            @Override public boolean breakBlock(GridPathPlanner.WorldAction action) { return false; }

            @Override public boolean placeBlock(GridPathPlanner.WorldAction action) {
                present[0] = true;
                return true;
            }
        };

        NavigationActionController.Result placed = session.tick(
                new GridPathPlanner.RouteStep(new GridPathPlanner.Point(1, 0, 0),
                        GridPathPlanner.Movement.BRIDGE, List.of(placement), 3.0D, 0), environment);

        assertTrue(placed.mutationOccurred());
        assertEquals(1, placed.placedBlocks());
        assertEquals(List.of(placement), placed.placed());
    }

    private static GridPathPlanner.WorldAction action(int x) {
        return new GridPathPlanner.WorldAction(GridPathPlanner.ActionType.BREAK_BLOCK,
                new GridPathPlanner.Point(x, 0, 0), "minecraft:dirt");
    }

    private static GridPathPlanner.RouteStep step(GridPathPlanner.WorldAction action) {
        return new GridPathPlanner.RouteStep(action.position(), GridPathPlanner.Movement.BREAK_AND_MOVE,
                List.of(action), 3.0D, 0);
    }

    private static final class FakeEnvironment implements NavigationActionController.Environment {
        private NavigationActionController.Observation observation;
        private boolean present = true;
        private double increment = 0.6D;

        @Override public NavigationActionController.Observation observe(
                GridPathPlanner.WorldAction action) {
            return observation != null ? observation : present
                    ? NavigationActionController.Observation.expected()
                    : NavigationActionController.Observation.satisfied();
        }

        @Override public double breakProgress(GridPathPlanner.WorldAction action) {
            return increment;
        }

        @Override public boolean breakBlock(GridPathPlanner.WorldAction action) {
            present = false;
            return true;
        }
    }
}
