package com.mccompanion.core.navigation;

import java.util.List;

/** Converts bounded, version-neutral Minecraft cell observations into one typed route edge. */
public final class SurvivalMovementPolicy {
    private SurvivalMovementPolicy() { }

    public static GridPathPlanner.Traversal classify(Observation observation) {
        if (observation == null || !observation.loaded() || observation.hazard()
                || observation.blockingEntity() || observation.horizontalDistance() > 2
                || observation.horizontalDistance() == 2 && !observation.jumpGapAllowed()
                || observation.horizontalDistance() == 0 && observation.verticalDelta() == 0
                || observation.verticalDelta() > 1 || observation.verticalDelta() < -4) {
            return GridPathPlanner.Traversal.blocked();
        }
        boolean verticalOnly = observation.horizontalDistance() == 0;
        boolean jumpGap = observation.horizontalDistance() == 2;
        if (verticalOnly && Math.abs(observation.verticalDelta()) != 1
                || verticalOnly && !observation.water() && !observation.climbable()) {
            return GridPathPlanner.Traversal.blocked();
        }
        boolean blockedVolume = !observation.feetPassable() || !observation.headPassable();
        boolean needsSupport = !observation.supported() && !observation.water()
                && !observation.climbable() && !jumpGap;
        if (blockedVolume && observation.breakActions().isEmpty()
                || needsSupport && observation.placeActions().isEmpty()
                || observation.verticalDelta() < -1 && !observation.dropColumnClear()) {
            return GridPathPlanner.Traversal.blocked();
        }

        int dropRisk = Math.max(0, -observation.verticalDelta() - 1);
        int gapRisk = jumpGap ? 2 : 0;
        int risk = Math.min(1_000, observation.riskUnits() + dropRisk + gapRisk);
        GridPathPlanner.Movement movement;
        double baseCost;
        if (blockedVolume) {
            movement = GridPathPlanner.Movement.BREAK_AND_MOVE;
            baseCost = 2.0D + observation.breakCost() + observation.placeCost();
        } else if (needsSupport) {
            movement = observation.verticalDelta() > 0
                    ? GridPathPlanner.Movement.PLACE_SUPPORT : GridPathPlanner.Movement.BRIDGE;
            baseCost = 3.0D + observation.placeCost();
        } else if (jumpGap) {
            movement = GridPathPlanner.Movement.JUMP_GAP;
            baseCost = 2.5D;
        } else if (observation.water()) {
            movement = GridPathPlanner.Movement.SWIM;
            baseCost = 4.0D + Math.abs(observation.verticalDelta());
        } else if (observation.climbable() && verticalOnly) {
            movement = GridPathPlanner.Movement.CLIMB;
            baseCost = 3.0D;
        } else if (observation.verticalDelta() > 0) {
            movement = GridPathPlanner.Movement.STEP_UP;
            baseCost = 1.75D;
        } else if (observation.verticalDelta() < 0) {
            movement = GridPathPlanner.Movement.DROP;
            baseCost = 1.0D + Math.abs(observation.verticalDelta()) * 0.5D;
        } else if (observation.door()) {
            movement = GridPathPlanner.Movement.OPEN_DOOR;
            baseCost = 2.0D;
        } else if (observation.sprintAllowed() && risk == 0) {
            movement = GridPathPlanner.Movement.SPRINT;
            baseCost = 0.75D;
        } else {
            movement = GridPathPlanner.Movement.WALK;
            baseCost = 1.0D;
        }

        java.util.ArrayList<GridPathPlanner.WorldAction> actions = new java.util.ArrayList<>(3);
        if (blockedVolume) actions.addAll(observation.breakActions());
        if (needsSupport) actions.addAll(observation.placeActions());
        if (!blockedVolume && !needsSupport && observation.door()) {
            actions.add(new GridPathPlanner.WorldAction(GridPathPlanner.ActionType.OPEN_DOOR,
                    observation.target(), observation.targetBlockId()));
        }
        return GridPathPlanner.Traversal.withActions(
                movement, baseCost + risk * 2.0D, risk, actions);
    }

    public record Observation(GridPathPlanner.Point target, int horizontalDistance,
                              int verticalDelta, boolean loaded, boolean feetPassable,
                              boolean headPassable, boolean supported, boolean water,
                              boolean climbable, boolean door, boolean dropColumnClear,
                              boolean blockingEntity, boolean hazard, boolean sprintAllowed,
                              int riskUnits, String targetBlockId,
                              List<GridPathPlanner.WorldAction> breakActions, double breakCost,
                              List<GridPathPlanner.WorldAction> placeActions, double placeCost,
                              boolean jumpGapAllowed) {
        public Observation {
            if (target == null || horizontalDistance < 0 || riskUnits < 0 || riskUnits > 1_000
                    || !Double.isFinite(breakCost) || breakCost < 0.0D
                    || !Double.isFinite(placeCost) || placeCost < 0.0D) {
                throw new IllegalArgumentException("invalid movement observation");
            }
            targetBlockId = targetBlockId == null ? "" : targetBlockId;
            breakActions = breakActions == null ? List.of() : List.copyOf(breakActions);
            if (breakActions.size() > 2 || breakActions.stream()
                    .anyMatch(action -> action.type() != GridPathPlanner.ActionType.BREAK_BLOCK)) {
                throw new IllegalArgumentException("invalid navigation break actions");
            }
            placeActions = placeActions == null ? List.of() : List.copyOf(placeActions);
            if (placeActions.size() > 1 || placeActions.stream()
                    .anyMatch(action -> action.type() != GridPathPlanner.ActionType.PLACE_BLOCK)) {
                throw new IllegalArgumentException("invalid navigation place actions");
            }
        }

        public Observation(GridPathPlanner.Point target, int horizontalDistance,
                           int verticalDelta, boolean loaded, boolean feetPassable,
                           boolean headPassable, boolean supported, boolean water,
                           boolean climbable, boolean door, boolean dropColumnClear,
                           boolean blockingEntity, boolean hazard, boolean sprintAllowed,
                           int riskUnits, String targetBlockId) {
            this(target, horizontalDistance, verticalDelta, loaded, feetPassable, headPassable,
                    supported, water, climbable, door, dropColumnClear, blockingEntity, hazard,
                    sprintAllowed, riskUnits, targetBlockId, List.of(), 0.0D,
                    List.of(), 0.0D, false);
        }
    }
}
