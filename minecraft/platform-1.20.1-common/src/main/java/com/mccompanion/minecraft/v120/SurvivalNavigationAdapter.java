package com.mccompanion.minecraft.v120;

import com.mccompanion.core.navigation.GridPathPlanner;
import com.mccompanion.core.navigation.SurvivalNavigationPolicy;
import com.mccompanion.minecraft.navigation.MinecraftSurvivalNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Thin 1.20.1 binding to the current feature's shared Minecraft navigation observation. */
final class SurvivalNavigationAdapter {
    GridPathPlanner.Plan plan(CompanionPlayer body, Vec3 target) {
        return plan(body, target, GridPathPlanner.Budget.safeLocomotion());
    }

    GridPathPlanner.Plan plan(CompanionPlayer body, Vec3 target, GridPathPlanner.Budget budget) {
        return MinecraftSurvivalNavigation.plan(body, body.ownerId(), target, budget);
    }

    GridPathPlanner.Plan plan(CompanionPlayer body, Vec3 target, SurvivalNavigationPolicy policy) {
        return MinecraftSurvivalNavigation.plan(body, body.ownerId(), target, policy);
    }

    boolean remainsTraversable(CompanionPlayer body, GridPathPlanner.Point from,
                               GridPathPlanner.Point to) {
        return remainsTraversable(body, from, new GridPathPlanner.RouteStep(
                to, GridPathPlanner.Movement.WALK, java.util.List.of(), 1.0D, 0));
    }

    boolean remainsTraversable(CompanionPlayer body, GridPathPlanner.Point from,
                               GridPathPlanner.RouteStep next) {
        return MinecraftSurvivalNavigation.remainsTraversable(body, body.ownerId(), from, next);
    }

    boolean remainsTraversable(CompanionPlayer body, SurvivalNavigationPolicy policy,
                               GridPathPlanner.Point from, GridPathPlanner.RouteStep next) {
        return MinecraftSurvivalNavigation.remainsTraversable(body, body.ownerId(), policy, from, next);
    }

    boolean openDoorIfNeeded(CompanionPlayer body, GridPathPlanner.Point point) {
        return MinecraftSurvivalNavigation.openDoorIfNeeded(body, point);
    }

    boolean requiresPassageOpening(CompanionPlayer body, GridPathPlanner.Point point) {
        return MinecraftSurvivalNavigation.requiresPassageOpening(body, point);
    }

    static GridPathPlanner.Point point(BlockPos position) {
        return MinecraftSurvivalNavigation.point(position);
    }

    static BlockPos block(GridPathPlanner.Point point) {
        return MinecraftSurvivalNavigation.block(point);
    }

    static Vec3 waypoint(GridPathPlanner.Point point) {
        return MinecraftSurvivalNavigation.waypoint(point);
    }

    float movementYaw(CompanionPlayer body, GridPathPlanner.Point point, Vec3 delta) {
        return MinecraftSurvivalNavigation.movementYaw(body, point, delta);
    }

    boolean canExecuteGapJump(CompanionPlayer body) {
        return MinecraftSurvivalNavigation.canExecuteGapJump(body);
    }

    boolean gapTakeoffReady(CompanionPlayer body, GridPathPlanner.Point landing) {
        return MinecraftSurvivalNavigation.gapTakeoffReady(body, landing);
    }
}
