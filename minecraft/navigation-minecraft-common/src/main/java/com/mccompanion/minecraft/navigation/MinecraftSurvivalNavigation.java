package com.mccompanion.minecraft.navigation;

import com.mccompanion.core.navigation.GridPathPlanner;
import com.mccompanion.core.navigation.SurvivalMovementPolicy;
import com.mccompanion.core.navigation.SurvivalNavigationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Minecraft observation and vanilla door adapter shared by both supported Loader targets. */
public final class MinecraftSurvivalNavigation {
    private MinecraftSurvivalNavigation() { }

    public static GridPathPlanner.Plan plan(ServerPlayer body, UUID ownerId, Vec3 target,
                                            GridPathPlanner.Budget budget) {
        return plan(body, ownerId, target, SurvivalNavigationPolicy.locomotionOnly(), budget);
    }

    public static GridPathPlanner.Plan plan(ServerPlayer body, UUID ownerId, UUID targetEntityId,
                                            Vec3 target, GridPathPlanner.Budget budget) {
        return plan(body, ignored(ownerId, targetEntityId), target,
                SurvivalNavigationPolicy.locomotionOnly(), budget);
    }

    public static GridPathPlanner.Plan plan(ServerPlayer body, UUID ownerId, Vec3 target,
                                            SurvivalNavigationPolicy policy) {
        return plan(body, ownerId, target, policy, policy.budget());
    }

    public static GridPathPlanner.Plan plan(ServerPlayer body, UUID ownerId, Vec3 target,
                                            SurvivalNavigationPolicy policy,
                                            GridPathPlanner.Budget budget) {
        return plan(body, ignored(ownerId, null), target, policy, budget);
    }

    private static GridPathPlanner.Plan plan(ServerPlayer body, Set<UUID> ignoredEntityIds, Vec3 target,
                                             SurvivalNavigationPolicy policy,
                                             GridPathPlanner.Budget budget) {
        GridPathPlanner.Point start = point(body.blockPosition());
        GridPathPlanner.Point goal = point(BlockPos.containing(target));
        ServerLevel level = body.serverLevel();
        return GridPathPlanner.plan(start, goal, new GridPathPlanner.Environment() {
            @Override public boolean loaded(GridPathPlanner.Point candidate) {
                return level.hasChunkAt(block(candidate));
            }

            @Override public GridPathPlanner.Traversal traversal(
                    GridPathPlanner.Point from, GridPathPlanner.Point to) {
                return classify(level, body, ignoredEntityIds, policy, from, to);
            }
        }, GridPathPlanner.DEFAULT_LIMITS, budget);
    }

    public static boolean remainsTraversable(ServerPlayer body, UUID ownerId,
                                             GridPathPlanner.Point from,
                                             GridPathPlanner.RouteStep next) {
        return remainsTraversable(body, ownerId, SurvivalNavigationPolicy.locomotionOnly(), from, next);
    }

    public static boolean remainsTraversable(ServerPlayer body, UUID ownerId, UUID targetEntityId,
                                             GridPathPlanner.Point from,
                                             GridPathPlanner.RouteStep next) {
        return body.serverLevel().hasChunkAt(block(next.point()))
                && classify(body.serverLevel(), body, ignored(ownerId, targetEntityId),
                SurvivalNavigationPolicy.locomotionOnly(), from, next.point()).passable();
    }

    public static boolean remainsTraversable(ServerPlayer body, UUID ownerId,
                                             SurvivalNavigationPolicy policy,
                                             GridPathPlanner.Point from,
                                             GridPathPlanner.RouteStep next) {
        return body.serverLevel().hasChunkAt(block(next.point()))
                && classify(body.serverLevel(), body, ignored(ownerId, null), policy, from, next.point()).passable();
    }

    public static boolean openDoorIfNeeded(ServerPlayer body, GridPathPlanner.Point point) {
        BlockPos position = block(point);
        BlockState state = body.serverLevel().getBlockState(position);
        if (!isPassage(state)
                || !state.hasProperty(BlockStateProperties.OPEN)
                || state.getValue(BlockStateProperties.OPEN)) return false;
        body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(),
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
        BlockState current = body.serverLevel().getBlockState(position);
        return current.hasProperty(BlockStateProperties.OPEN)
                && current.getValue(BlockStateProperties.OPEN);
    }

    public static boolean requiresPassageOpening(ServerPlayer body, GridPathPlanner.Point point) {
        BlockState state = body.serverLevel().getBlockState(block(point));
        return isPassage(state) && state.hasProperty(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.OPEN);
    }

    public static float movementYaw(ServerPlayer body, GridPathPlanner.Point point, Vec3 delta) {
        BlockState state = body.serverLevel().getBlockState(block(point));
        if (isClimbable(state) && Math.abs(delta.x) + Math.abs(delta.z) < 0.25D) {
            Direction support = climbSupport(body.serverLevel(), block(point), state);
            if (support != null) {
                return (float) Math.toDegrees(Math.atan2(-support.getStepX(), support.getStepZ()));
            }
        }
        return (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
    }

    public static GridPathPlanner.Point point(BlockPos position) {
        return new GridPathPlanner.Point(position.getX(), position.getY(), position.getZ());
    }

    public static BlockPos block(GridPathPlanner.Point point) {
        return new BlockPos(point.x(), point.y(), point.z());
    }

    public static Vec3 waypoint(GridPathPlanner.Point point) {
        return Vec3.atBottomCenterOf(block(point));
    }

    private static GridPathPlanner.Traversal classify(ServerLevel level, ServerPlayer body,
                                                      Set<UUID> ignoredEntityIds, SurvivalNavigationPolicy policy,
                                                      GridPathPlanner.Point from,
                                                      GridPathPlanner.Point to) {
        int vertical = to.y() - from.y();
        int horizontal = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
        BlockPos fromPosition = block(from);
        BlockPos position = block(to);
        if (!level.hasChunkAt(position)) return GridPathPlanner.Traversal.blocked();
        BlockState feet = level.getBlockState(position);
        BlockState head = level.getBlockState(position.above());
        BlockState fromFeet = level.getBlockState(fromPosition);
        boolean water = isWater(feet) || isWater(head);
        boolean climbable = isClimbable(feet) || isClimbable(fromFeet);
        boolean hazard = isHazard(feet) || isHazard(head)
                || isHazard(level.getBlockState(position.below()));
        boolean feetPassable = passableVolume(level, position, feet);
        boolean headPassable = passableVolume(level, position.above(), head);
        boolean supported = hasSupport(level, position, feet);
        AABB occupiedVolume = new AABB(position)
                .expandTowards(0.0D, 1.0D, 0.0D).inflate(0.25D, 0.0D, 0.25D);
        boolean blockingEntity = !level.getEntities(body, occupiedVolume,
                entity -> blockingEntity(entity, ignoredEntityIds)).isEmpty();
        int nearbyEntities = Math.min(4, level.getEntities(body, occupiedVolume.inflate(1.0D),
                entity -> blockingEntity(entity, ignoredEntityIds)).size());
        int nearbyHazards = adjacentHazards(level, position);
        boolean dropClear = vertical >= -1 || clearDropColumn(level, position, from.y(), to.y());
        boolean gapJumpAllowed = horizontal == 2 && vertical == 0 && supported
                && feetPassable && headPassable && !water && !climbable
                && jumpGapClear(level, body, ignoredEntityIds, from, to);
        boolean sprintAllowed = vertical == 0 && (horizontal == 1 || gapJumpAllowed) && body.onGround()
                && body.getFoodData().getFoodLevel() > 6 && body.getHealth() > 6.0F
                && !body.isUsingItem() && !water && !climbable && nearbyHazards == 0
                && nearbyEntities == 0;
        List<GridPathPlanner.WorldAction> breakActions = new ArrayList<>(2);
        double breakCost = 0.0D;
        if (!feetPassable) {
            double cost = MinecraftNavigationActionExecutor.plannedBreakCost(
                    body, position, feet, policy);
            if (Double.isFinite(cost)) {
                breakActions.add(new GridPathPlanner.WorldAction(GridPathPlanner.ActionType.BREAK_BLOCK,
                        to, BuiltInRegistries.BLOCK.getKey(feet.getBlock()).toString()));
                breakCost += cost;
            }
        }
        if (!headPassable) {
            double cost = MinecraftNavigationActionExecutor.plannedBreakCost(
                    body, position.above(), head, policy);
            if (Double.isFinite(cost)) {
                breakActions.add(new GridPathPlanner.WorldAction(GridPathPlanner.ActionType.BREAK_BLOCK,
                        to.offset(0, 1, 0), BuiltInRegistries.BLOCK.getKey(head.getBlock()).toString()));
                breakCost += cost;
            }
        }
        int blockedVolumes = (feetPassable ? 0 : 1) + (headPassable ? 0 : 1);
        if (breakActions.size() != blockedVolumes) {
            breakActions = List.of();
            breakCost = 0.0D;
        }
        List<GridPathPlanner.WorldAction> placeActions = List.of();
        double placeCost = 0.0D;
        if (!supported && horizontal == 1 && (vertical == 0 || vertical == 1)
                && feetPassable && headPassable && !water && !climbable) {
            MinecraftNavigationActionExecutor.PlacementPlan placement =
                    MinecraftNavigationActionExecutor.plannedPlacement(body, position.below(), policy);
            if (placement != null) {
                placeActions = List.of(new GridPathPlanner.WorldAction(
                        GridPathPlanner.ActionType.PLACE_BLOCK,
                        to.offset(0, -1, 0), placement.blockId()));
                placeCost = placement.cost();
            }
        }
        return SurvivalMovementPolicy.classify(new SurvivalMovementPolicy.Observation(
                to, horizontal, vertical, true, feetPassable, headPassable, supported,
                water, climbable, isOpenablePassage(feet), dropClear,
                blockingEntity, hazard, sprintAllowed,
                Math.min(1_000, nearbyHazards * 2 + nearbyEntities),
                BuiltInRegistries.BLOCK.getKey(feet.getBlock()).toString(),
                breakActions, breakCost, placeActions, placeCost, gapJumpAllowed));
    }

    private static boolean blockingEntity(Entity entity, Set<UUID> ignoredEntityIds) {
        return entity.isAlive() && entity.isPickable()
                && !ignoredEntityIds.contains(entity.getUUID());
    }

    private static boolean clearDropColumn(ServerLevel level, BlockPos landing,
                                           int fromY, int toY) {
        for (int y = fromY - 1; y > toY; y--) {
            BlockPos feet = new BlockPos(landing.getX(), y, landing.getZ());
            if (!level.hasChunkAt(feet)) return false;
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(feet.above());
            if (!passableVolume(level, feet, feetState)
                    || !passableVolume(level, feet.above(), headState)
                    || isHazard(feetState) || isHazard(headState)) return false;
        }
        return true;
    }

    private static boolean jumpGapClear(ServerLevel level, ServerPlayer body, Set<UUID> ignoredEntityIds,
                                        GridPathPlanner.Point from, GridPathPlanner.Point to) {
        int dx = Integer.signum(to.x() - from.x());
        int dz = Integer.signum(to.z() - from.z());
        BlockPos middle = block(from.offset(dx, 0, dz));
        BlockPos landing = block(to);
        for (BlockPos occupied : List.of(middle, middle.above(), middle.above(2),
                landing, landing.above())) {
            if (!level.hasChunkAt(occupied)) return false;
            BlockState state = level.getBlockState(occupied);
            if (!passableVolume(level, occupied, state) || isHazard(state)
                    || !state.getFluidState().isEmpty()) return false;
        }
        BlockState middleBelow = level.getBlockState(middle.below());
        if (!middleBelow.getCollisionShape(level, middle.below()).isEmpty()
                || isHazard(middleBelow)) return false;
        AABB arc = new AABB(middle).expandTowards(0.0D, 2.0D, 0.0D)
                .inflate(0.35D, 0.0D, 0.35D);
        return level.getEntities(body, arc, entity -> blockingEntity(entity, ignoredEntityIds)).isEmpty();
    }

    private static Set<UUID> ignored(UUID ownerId, UUID targetEntityId) {
        if (ownerId == null) return targetEntityId == null ? Set.of() : Set.of(targetEntityId);
        if (targetEntityId == null || targetEntityId.equals(ownerId)) return Set.of(ownerId);
        return Set.of(ownerId, targetEntityId);
    }

    public static boolean canExecuteGapJump(ServerPlayer body) {
        return body.onGround()
                ? body.getFoodData().getFoodLevel() > 6 && body.getHealth() > 6.0F
                : body.isSprinting();
    }

    public static boolean gapTakeoffReady(ServerPlayer body, GridPathPlanner.Point landing) {
        if (!body.onGround()) return true;
        Vec3 target = Vec3.atBottomCenterOf(block(landing));
        double dx = target.x - body.getX();
        double dz = target.z - body.getZ();
        return dx * dx + dz * dz <= 1.75D * 1.75D;
    }

    private static int adjacentHazards(ServerLevel level, BlockPos position) {
        int hazards = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = position.relative(direction);
            if (!level.hasChunkAt(neighbor) || isHazard(level.getBlockState(neighbor))) hazards++;
        }
        return hazards;
    }

    private static Direction climbSupport(ServerLevel level, BlockPos position, BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = position.relative(direction);
            if (level.hasChunkAt(neighbor)
                    && !level.getBlockState(neighbor).getCollisionShape(level, neighbor).isEmpty()) {
                return direction;
            }
        }
        return null;
    }

    private static boolean passableVolume(ServerLevel level, BlockPos position, BlockState state) {
        return state.getCollisionShape(level, position).isEmpty()
                || isWater(state) || isClimbable(state) || isOpenablePassage(state);
    }

    private static boolean isPassage(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof TrapDoorBlock;
    }

    private static boolean isOpenablePassage(BlockState state) {
        if (state.getBlock() instanceof DoorBlock door) return door.type().canOpenByHand();
        if (state.getBlock() instanceof TrapDoorBlock) return !state.is(Blocks.IRON_TRAPDOOR);
        return state.getBlock() instanceof FenceGateBlock;
    }

    private static boolean hasSupport(ServerLevel level, BlockPos position, BlockState feet) {
        if (isWater(feet) || isClimbable(feet)) return true;
        BlockPos below = position.below();
        BlockState support = level.getBlockState(below);
        return !support.getCollisionShape(level, below).isEmpty() && !isHazard(support);
    }

    private static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT);
    }

    private static boolean isHazard(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }
}
