package com.mccompanion.minecraft.v120;

import com.mccompanion.minecraft.bridge.InventoryWorldEventTracker;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Reads the real 1.20.1 player inventory and only the active task's exact block target. */
public final class InventoryWorldEventObservationService {
    private static final int PLAYER_MAIN_SLOTS = 36;
    private static final int MAX_CONTAINER_SLOTS = 256;

    public InventoryWorldEventTracker.Snapshot snapshot(
            CompanionRegistry.InventoryWorldEventBinding binding, long tick, Instant observedAt) {
        java.util.Objects.requireNonNull(binding, "binding");
        CompanionPlayer body = binding.body();
        Map<String, Integer> inventory = new TreeMap<>();
        int freeSlots = 0;
        for (int slot = 0; slot < PLAYER_MAIN_SLOTS; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (stack.isEmpty()) freeSlots++;
            else inventory.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), Integer::sum);
        }
        SkillParameters parameters = binding.parameters();
        InventoryWorldEventTracker.ResourceGoal goal = parameters == null || parameters.itemId().isBlank()
                ? null : new InventoryWorldEventTracker.ResourceGoal(
                        parameters.itemId(), parameters.quantity());
        String dimension = body.serverLevel().dimension().location().toString();
        long dayTime = Math.floorMod(body.serverLevel().getDayTime(), 24_000L);
        InventoryWorldEventTracker.TimeOfDay timeOfDay = dayTime < 12_000L
                ? InventoryWorldEventTracker.TimeOfDay.DAY : InventoryWorldEventTracker.TimeOfDay.NIGHT;
        InventoryWorldEventTracker.Weather weather = body.serverLevel().isThundering()
                ? InventoryWorldEventTracker.Weather.THUNDER
                : body.serverLevel().isRaining()
                ? InventoryWorldEventTracker.Weather.RAIN : InventoryWorldEventTracker.Weather.CLEAR;
        return new InventoryWorldEventTracker.Snapshot(binding.companionId(), binding.behaviorId(),
                tick, observedAt, PLAYER_MAIN_SLOTS, freeSlots, inventory, goal, dimension,
                timeOfDay, weather, target(body, parameters));
    }

    private static InventoryWorldEventTracker.Target target(
            CompanionPlayer body, SkillParameters parameters) {
        if (parameters == null || !parameters.hasBlockTarget()) return null;
        String identity = parameters.dimension() + ':' + parameters.x() + ','
                + parameters.y() + ',' + parameters.z();
        BlockPos position = new BlockPos(parameters.x(), parameters.y(), parameters.z());
        if (!body.serverLevel().dimension().location().toString().equals(parameters.dimension())
                || !body.serverLevel().hasChunkAt(position)) {
            return new InventoryWorldEventTracker.Target(identity,
                    InventoryWorldEventTracker.TargetKind.BLOCK, false,
                    null, "UNAVAILABLE", null, "");
        }
        var state = body.serverLevel().getBlockState(position);
        var blockEntity = body.serverLevel().getBlockEntity(position);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String blockFingerprint = blockId + '|' + state;
        if (!(blockEntity instanceof Container container)) {
            return new InventoryWorldEventTracker.Target(identity,
                    InventoryWorldEventTracker.TargetKind.BLOCK, true,
                    blockId, blockFingerprint, null, "");
        }
        String containerType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
        return new InventoryWorldEventTracker.Target(identity,
                InventoryWorldEventTracker.TargetKind.CONTAINER, true,
                blockId, blockFingerprint, containerType, containerFingerprint(container));
    }

    private static String containerFingerprint(Container container) {
        StringBuilder value = new StringBuilder().append("size=").append(container.getContainerSize());
        int limit = Math.min(container.getContainerSize(), MAX_CONTAINER_SLOTS);
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            value.append('|').append(slot).append('=')
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(':')
                    .append(stack.getCount()).append(':')
                    .append(stack.getTag() == null ? 0 : stack.getTag().hashCode());
        }
        return value.toString();
    }
}
