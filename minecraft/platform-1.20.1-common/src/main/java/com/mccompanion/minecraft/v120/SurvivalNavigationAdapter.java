package com.mccompanion.minecraft.v120;

import com.mccompanion.core.navigation.GridPathPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Thin 1.20.1 collision/safety adapter for the deterministic grid planner. */
final class SurvivalNavigationAdapter {
    GridPathPlanner.Plan plan(CompanionPlayer body, Vec3 target) {
        GridPathPlanner.Point start = point(body.blockPosition());
        GridPathPlanner.Point goal = point(BlockPos.containing(target));
        ServerLevel level = body.serverLevel();
        return GridPathPlanner.plan(start, goal, new GridPathPlanner.Environment() {
            @Override
            public boolean loaded(GridPathPlanner.Point candidate) {
                return level.hasChunkAt(block(candidate));
            }

            @Override
            public GridPathPlanner.Traversal traversal(
                    GridPathPlanner.Point from,
                    GridPathPlanner.Point to) {
                return classify(level, body, from, to);
            }
        });
    }

    boolean remainsTraversable(CompanionPlayer body, GridPathPlanner.Point from, GridPathPlanner.Point to) {
        return body.serverLevel().hasChunkAt(block(to))
                && classify(body.serverLevel(), body, from, to).passable();
    }

    boolean openDoorIfNeeded(CompanionPlayer body, GridPathPlanner.Point point) {
        BlockPos position = block(point);
        BlockState state = body.serverLevel().getBlockState(position);
        if (!(state.getBlock() instanceof DoorBlock)
                || !state.hasProperty(BlockStateProperties.OPEN)
                || state.getValue(BlockStateProperties.OPEN)) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
        body.gameMode.useItemOn(
                body,
                body.serverLevel(),
                body.getMainHandItem(),
                InteractionHand.MAIN_HAND,
                hit);
        return true;
    }

    static GridPathPlanner.Point point(BlockPos position) {
        return new GridPathPlanner.Point(position.getX(), position.getY(), position.getZ());
    }

    static BlockPos block(GridPathPlanner.Point point) {
        return new BlockPos(point.x(), point.y(), point.z());
    }

    static Vec3 waypoint(GridPathPlanner.Point point) {
        return Vec3.atBottomCenterOf(block(point));
    }

    float movementYaw(CompanionPlayer body, GridPathPlanner.Point point, Vec3 delta) {
        BlockState state = body.serverLevel().getBlockState(block(point));
        if (isClimbable(state)
                && Math.abs(delta.x) + Math.abs(delta.z) < 0.25D
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction support = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            return (float) Math.toDegrees(Math.atan2(-support.getStepX(), support.getStepZ()));
        }
        return (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
    }

    private static GridPathPlanner.Traversal classify(
            ServerLevel level,
            CompanionPlayer body,
            GridPathPlanner.Point from,
            GridPathPlanner.Point to) {
        int vertical = to.y() - from.y();
        int horizontal = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
        if (Math.abs(vertical) > 1 || horizontal > 1 || horizontal == 0 && vertical == 0) {
            return GridPathPlanner.Traversal.blocked();
        }
        BlockPos fromPosition = block(from);
        BlockPos position = block(to);
        BlockState feet = level.getBlockState(position);
        BlockState head = level.getBlockState(position.above());
        BlockState fromFeet = level.getBlockState(fromPosition);
        if (isHazard(feet) || isHazard(head) || isHazard(level.getBlockState(position.below()))) {
            return GridPathPlanner.Traversal.blocked();
        }
        AABB occupiedVolume = new AABB(position).expandTowards(0.0D, 1.0D, 0.0D);
        if (!level.getEntities(
                        body,
                        occupiedVolume,
                        entity -> entity.isAlive()
                                && entity.isPickable()
                                && !entity.getUUID().equals(body.ownerId()))
                .isEmpty()) {
            return GridPathPlanner.Traversal.blocked();
        }
        boolean water = isWater(feet) || isWater(head);
        boolean climb = isClimbable(feet) || isClimbable(fromFeet);
        if (horizontal == 0 && !water && !climb) return GridPathPlanner.Traversal.blocked();
        if (!passableVolume(level, position, feet)
                || !passableVolume(level, position.above(), head)
                || !hasSupport(level, position, feet)) {
            return GridPathPlanner.Traversal.blocked();
        }
        double cost = 1.0D + Math.abs(vertical) * 0.5D;
        if (water) cost += 4.0D;
        if (climb) cost += 2.0D;
        if (feet.getBlock() instanceof DoorBlock) cost += 1.0D;
        return GridPathPlanner.Traversal.passable(cost);
    }

    private static boolean passableVolume(ServerLevel level, BlockPos position, BlockState state) {
        return state.getCollisionShape(level, position).isEmpty()
                || isWater(state)
                || isClimbable(state)
                || state.getBlock() instanceof DoorBlock;
    }

    private static boolean hasSupport(ServerLevel level, BlockPos position, BlockState feet) {
        if (isWater(feet) || isClimbable(feet)) return true;
        BlockPos belowPosition = position.below();
        BlockState below = level.getBlockState(belowPosition);
        return !below.getCollisionShape(level, belowPosition).isEmpty()
                && !isHazard(below);
    }

    private static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE);
    }

    private static boolean isHazard(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }
}
