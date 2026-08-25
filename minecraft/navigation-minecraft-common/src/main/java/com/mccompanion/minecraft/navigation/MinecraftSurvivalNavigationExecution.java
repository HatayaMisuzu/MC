package com.mccompanion.minecraft.navigation;

import com.mccompanion.core.navigation.GridPathPlanner;
import com.mccompanion.core.navigation.NavigationActionController;
import com.mccompanion.core.navigation.RouteExecutionController;
import com.mccompanion.core.navigation.SurvivalNavigationPolicy;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Shared route/action lifecycle for one explicitly authorized survival-navigation task. */
public final class MinecraftSurvivalNavigationExecution {
    private final SurvivalNavigationPolicy policy;
    private final RouteExecutionController.Session route;
    private final MinecraftNavigationActionExecutor actions;

    public MinecraftSurvivalNavigationExecution(
            int startedTick, String worldKey, SurvivalNavigationPolicy policy) {
        this.policy = policy;
        this.route = new RouteExecutionController.Session(startedTick, worldKey);
        this.actions = new MinecraftNavigationActionExecutor(policy);
    }

    public enum Status { MOVE, ACTION, ARRIVED, BLOCKED }

    public record Result(Status status, String code, float yaw, boolean jump, boolean sprint,
                         boolean replanned, int replanCount, int brokenBlocks,
                         List<GridPathPlanner.WorldAction> destroyed, int placedBlocks,
                         List<GridPathPlanner.WorldAction> placed, boolean mutationOccurred) {
        public Result {
            code = code == null ? "" : code;
            destroyed = destroyed == null ? List.of() : List.copyOf(destroyed);
            placed = placed == null ? List.of() : List.copyOf(placed);
        }
    }

    public Result tick(ServerPlayer body, UUID ownerId, Vec3 target, double arrivalDistanceSquared,
                       int tick, Runnable markGameModeAction, Runnable markMenuAction,
                       IntConsumer selectHotbarSlot) {
        RouteExecutionController.Position currentPosition = position(body.position());
        RouteExecutionController.Position targetPosition = position(target);
        GridPathPlanner.Point currentCell = MinecraftSurvivalNavigation.point(body.blockPosition());
        GridPathPlanner.Point targetCell = MinecraftSurvivalNavigation.point(BlockPos.containing(target));
        int remainingBreaks = Math.max(0, policy.maxBreakBlocks() - actions.brokenBlocks());
        int remainingPlacements = Math.max(0, policy.maxPlaceBlocks() - actions.placedBlocks());
        remainingPlacements = Math.min(remainingPlacements,
                MinecraftNavigationActionExecutor.availablePlacementBlocks(body, policy));
        GridPathPlanner.Budget remainingBudget = new GridPathPlanner.Budget(
                remainingBreaks, remainingPlacements, policy.maxRiskUnits(), 256);
        RouteExecutionController.Result routeResult = route.tick(new RouteExecutionController.Input(
                        tick, body.serverLevel().dimension().location().toString(), currentPosition,
                        currentCell, targetPosition, targetCell, arrivalDistanceSquared, remainingBudget),
                new RouteExecutionController.Environment() {
                    @Override public GridPathPlanner.Plan plan(
                            RouteExecutionController.Position destination, GridPathPlanner.Budget budget) {
                        return MinecraftSurvivalNavigation.plan(body, ownerId,
                                new Vec3(destination.x(), destination.y(), destination.z()), policy, budget);
                    }

                    @Override public boolean remainsTraversable(
                            GridPathPlanner.Point from, GridPathPlanner.RouteStep next) {
                        return MinecraftSurvivalNavigation.remainsTraversable(body, ownerId, policy, from, next);
                    }
                });
        if (routeResult.status() == RouteExecutionController.Status.BLOCKED) {
            return result(Status.BLOCKED, routeResult.code(), 0.0F, false, false,
                    routeResult, false);
        }
        if (routeResult.status() == RouteExecutionController.Status.PAUSED) {
            return result(Status.ACTION, "PAUSED", 0.0F, false, false, routeResult, false);
        }
        if (routeResult.status() == RouteExecutionController.Status.ARRIVED) {
            return result(Status.ARRIVED, "ARRIVED", 0.0F, false, false, routeResult, false);
        }
        if (routeResult.direct()) {
            Vec3 delta = vector(routeResult.delta());
            return result(Status.MOVE, routeResult.code(), movementYaw(delta),
                    delta.y > 0.6D || body.horizontalCollision, false, routeResult, false);
        }

        GridPathPlanner.RouteStep step = routeResult.step();
        boolean hasWorldAction = step.actions().stream().anyMatch(action ->
                action.type() == GridPathPlanner.ActionType.BREAK_BLOCK
                        || action.type() == GridPathPlanner.ActionType.PLACE_BLOCK);
        if (hasWorldAction) {
            NavigationActionController.Result action = actions.tick(body, step, markGameModeAction,
                    markMenuAction, selectHotbarSlot);
            if (action.status() == NavigationActionController.Status.BLOCKED) {
                return result(Status.BLOCKED, action.code(), 0.0F, false, false,
                        routeResult, action.mutationOccurred());
            }
            if (action.status() == NavigationActionController.Status.REPLAN) {
                if (!route.invalidateForReplan()) {
                    return result(Status.BLOCKED, "STUCK", 0.0F, false, false,
                            routeResult, false);
                }
                return result(Status.ACTION, "ACTION_REPLAN", 0.0F, false, false,
                        routeResult, false);
            }
            if (action.status() == NavigationActionController.Status.RUNNING) {
                route.holdForAction();
                return result(Status.ACTION, action.code(), 0.0F, false, false,
                        routeResult, action.mutationOccurred());
            }
        }

        if (MinecraftSurvivalNavigation.requiresPassageOpening(body, step.point())) {
            if (!MinecraftSurvivalNavigation.openDoorIfNeeded(body, step.point())) {
                return result(Status.BLOCKED, "PASSAGE_INTERACTION_FAILED", 0.0F,
                        false, false, routeResult, false);
            }
            markGameModeAction.run();
        }
        Vec3 delta = vector(routeResult.delta());
        float yaw = MinecraftSurvivalNavigation.movementYaw(body, step.point(), delta);
        boolean gapJump = step.movement() == GridPathPlanner.Movement.JUMP_GAP;
        if (gapJump && !MinecraftSurvivalNavigation.canExecuteGapJump(body)) {
            return result(Status.BLOCKED, "GAP_JUMP_UNSAFE", 0.0F, false, false,
                    routeResult, false);
        }
        return result(Status.MOVE, routeResult.code(), yaw,
                step.point().y() > body.blockPosition().getY() || body.horizontalCollision
                        || gapJump && MinecraftSurvivalNavigation.gapTakeoffReady(body, step.point()),
                step.movement() == GridPathPlanner.Movement.SPRINT
                        || gapJump,
                routeResult, false);
    }

    public void pause(int tick) { route.pause(tick); }

    public void resume(int tick) { route.resume(tick); }

    public int brokenBlocks() { return actions.brokenBlocks(); }

    public List<GridPathPlanner.WorldAction> destroyed() { return actions.destroyed(); }

    public int placedBlocks() { return actions.placedBlocks(); }

    public List<GridPathPlanner.WorldAction> placed() { return actions.placed(); }

    private Result result(Status status, String code, float yaw, boolean jump, boolean sprint,
                          RouteExecutionController.Result routeResult, boolean mutationOccurred) {
        return new Result(status, code, yaw, jump, sprint, routeResult.replanned(),
                routeResult.replanCount(), actions.brokenBlocks(), actions.destroyed(),
                actions.placedBlocks(), actions.placed(), mutationOccurred);
    }

    private static RouteExecutionController.Position position(Vec3 value) {
        return new RouteExecutionController.Position(value.x, value.y, value.z);
    }

    private static Vec3 vector(RouteExecutionController.Position value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static float movementYaw(Vec3 delta) {
        return (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
    }

}
