package com.mccompanion.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SurvivalMovementPolicyTest {
    @Test
    void selectsSprintDoorSwimAndClimbFromObservedFacts() {
        assertEquals(GridPathPlanner.Movement.SPRINT,
                SurvivalMovementPolicy.classify(observation(1, 0, false, false, false,
                        true, 0)).movement());

        GridPathPlanner.Traversal door = SurvivalMovementPolicy.classify(
                observation(1, 0, false, false, true, true, 0));
        assertEquals(GridPathPlanner.Movement.OPEN_DOOR, door.movement());
        assertEquals(GridPathPlanner.ActionType.OPEN_DOOR, door.actions().get(0).type());

        assertEquals(GridPathPlanner.Movement.SWIM,
                SurvivalMovementPolicy.classify(observation(0, 1, true, false, false,
                        false, 0)).movement());
        assertEquals(GridPathPlanner.Movement.CLIMB,
                SurvivalMovementPolicy.classify(observation(0, 1, false, true, false,
                        false, 0)).movement());
    }

    @Test
    void allowsOnlyObservedSafeDropsAndChargesRisk() {
        GridPathPlanner.Traversal safe = SurvivalMovementPolicy.classify(
                observation(1, -3, false, false, false, false, 1));
        assertTrue(safe.passable());
        assertEquals(GridPathPlanner.Movement.DROP, safe.movement());
        assertEquals(3, safe.riskUnits());

        var blocked = new SurvivalMovementPolicy.Observation(new GridPathPlanner.Point(1, -3, 0),
                1, -3, true, true, true, true, false, false, false,
                false, false, false, false, 0, "minecraft:air");
        assertFalse(SurvivalMovementPolicy.classify(blocked).passable());
    }

    @Test
    void hazardsAndBlockingEntitiesRemainHardBoundaries() {
        var hazard = new SurvivalMovementPolicy.Observation(new GridPathPlanner.Point(1, 0, 0),
                1, 0, true, true, true, true, false, false, false,
                true, false, true, true, 0, "minecraft:air");
        var entity = new SurvivalMovementPolicy.Observation(new GridPathPlanner.Point(1, 0, 0),
                1, 0, true, true, true, true, false, false, false,
                true, true, false, true, 0, "minecraft:air");
        assertFalse(SurvivalMovementPolicy.classify(hazard).passable());
        assertFalse(SurvivalMovementPolicy.classify(entity).passable());
    }

    @Test
    void turnsAnExplicitlyAuthorizedBlockedVolumeIntoABoundedBreakStep() {
        GridPathPlanner.WorldAction action = new GridPathPlanner.WorldAction(
                GridPathPlanner.ActionType.BREAK_BLOCK,
                new GridPathPlanner.Point(1, 0, 0), "minecraft:dirt");
        var blockedVolume = new SurvivalMovementPolicy.Observation(
                new GridPathPlanner.Point(1, 0, 0), 1, 0,
                true, false, true, true, false, false, false, true,
                false, false, false, 0, "minecraft:dirt", List.of(action), 3.0D,
                List.of(), 0.0D, false);

        GridPathPlanner.Traversal traversal = SurvivalMovementPolicy.classify(blockedVolume);

        assertTrue(traversal.passable());
        assertEquals(GridPathPlanner.Movement.BREAK_AND_MOVE, traversal.movement());
        assertEquals(List.of(action), traversal.actions());
        assertEquals(1, traversal.brokenBlocks());
    }

    @Test
    void classifiesExplicitSupportPlacementAndBoundedGapJump() {
        GridPathPlanner.WorldAction support = new GridPathPlanner.WorldAction(
                GridPathPlanner.ActionType.PLACE_BLOCK,
                new GridPathPlanner.Point(1, -1, 0), "minecraft:cobblestone");
        var bridge = new SurvivalMovementPolicy.Observation(
                new GridPathPlanner.Point(1, 0, 0), 1, 0,
                true, true, true, false, false, false, false, true,
                false, false, false, 0, "minecraft:air", List.of(), 0.0D,
                List.of(support), 2.0D, false);
        GridPathPlanner.Traversal bridgeTraversal = SurvivalMovementPolicy.classify(bridge);
        assertTrue(bridgeTraversal.passable());
        assertEquals(GridPathPlanner.Movement.BRIDGE, bridgeTraversal.movement());
        assertEquals(1, bridgeTraversal.placedBlocks());

        var gap = new SurvivalMovementPolicy.Observation(
                new GridPathPlanner.Point(2, 0, 0), 2, 0,
                true, true, true, true, false, false, false, true,
                false, false, true, 0, "minecraft:air", List.of(), 0.0D,
                List.of(), 0.0D, true);
        GridPathPlanner.Traversal jump = SurvivalMovementPolicy.classify(gap);
        assertTrue(jump.passable());
        assertEquals(GridPathPlanner.Movement.JUMP_GAP, jump.movement());
        assertEquals(2, jump.riskUnits());
    }

    private static SurvivalMovementPolicy.Observation observation(
            int horizontal, int vertical, boolean water, boolean climbable,
            boolean door, boolean sprint, int risk) {
        return new SurvivalMovementPolicy.Observation(
                new GridPathPlanner.Point(horizontal, vertical, 0), horizontal, vertical,
                true, true, true, true, water, climbable, door, true,
                false, false, sprint, risk, door ? "minecraft:oak_door" : "minecraft:air");
    }
}
