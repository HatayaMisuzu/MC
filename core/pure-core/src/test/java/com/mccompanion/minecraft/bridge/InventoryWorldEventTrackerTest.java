package com.mccompanion.minecraft.bridge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryWorldEventTrackerTest {
    @Test
    void confirmsCapacityEdgesAndDoesNotSpamSustainedState() {
        InventoryWorldEventTracker tracker = new InventoryWorldEventTracker();
        assertTrue(tracker.observe(snapshot(1, 3, Map.of(), null, day(), null)).isEmpty());
        assertTrue(tracker.observe(snapshot(2, 2, Map.of(), null, day(), null)).isEmpty());
        assertEquals(List.of(InventoryWorldEventTracker.Type.INVENTORY_NEAR_FULL),
                types(tracker.observe(snapshot(3, 2, Map.of(), null, day(), null))));
        assertTrue(tracker.observe(snapshot(4, 2, Map.of(), null, day(), null)).isEmpty());
        assertTrue(tracker.observe(snapshot(5, 0, Map.of(), null, day(), null)).isEmpty());
        assertEquals(List.of(InventoryWorldEventTracker.Type.INVENTORY_FULL),
                types(tracker.observe(snapshot(6, 0, Map.of(), null, day(), null))));
        assertTrue(tracker.observe(snapshot(7, 0, Map.of(), null, day(), null)).isEmpty());
    }

    @Test
    void resourceGoalUsesBindingAndCountEdges() {
        InventoryWorldEventTracker tracker = new InventoryWorldEventTracker();
        var goal = new InventoryWorldEventTracker.ResourceGoal("minecraft:diamond", 2);
        assertEquals(List.of(InventoryWorldEventTracker.Type.KEY_ITEM_INSUFFICIENT),
                types(tracker.observe(snapshot(1, 10, Map.of(), goal, day(), null))));
        assertEquals(List.of(InventoryWorldEventTracker.Type.KEY_ITEM_ACQUIRED),
                types(tracker.observe(snapshot(2, 10, Map.of("minecraft:diamond", 1), goal, day(), null))));
        assertEquals(List.of(InventoryWorldEventTracker.Type.RESOURCE_TARGET_REACHED),
                types(tracker.observe(snapshot(3, 10, Map.of("minecraft:diamond", 2), goal, day(), null))));
        assertEquals(List.of(InventoryWorldEventTracker.Type.KEY_ITEM_INSUFFICIENT),
                types(tracker.observe(snapshot(4, 10, Map.of("minecraft:diamond", 1), goal, day(), null))));
    }

    @Test
    void alreadySatisfiedNewBindingStillReportsTheObservedTarget() {
        InventoryWorldEventTracker tracker = new InventoryWorldEventTracker();
        var goal = new InventoryWorldEventTracker.ResourceGoal("minecraft:diamond", 2);
        assertEquals(List.of(InventoryWorldEventTracker.Type.RESOURCE_TARGET_REACHED),
                types(tracker.observe(snapshot(1, 10, Map.of("minecraft:diamond", 2), goal, day(), null))));
    }

    @Test
    void emitsOnlyBoundedWorldAndCurrentTargetChanges() {
        InventoryWorldEventTracker tracker = new InventoryWorldEventTracker();
        var chest = target(InventoryWorldEventTracker.TargetKind.CONTAINER,
                "minecraft:chest", "chest[facing=north]", "slot0=stone:1");
        assertTrue(tracker.observe(snapshot(1, 10, Map.of(), null, day(), chest)).isEmpty());
        assertEquals(List.of(InventoryWorldEventTracker.Type.DAY_NIGHT_CHANGED,
                        InventoryWorldEventTracker.Type.WEATHER_CHANGED,
                        InventoryWorldEventTracker.Type.TARGET_CONTAINER_CHANGED),
                types(tracker.observe(snapshot(2, 10, Map.of(), null,
                        new World("minecraft:overworld", InventoryWorldEventTracker.TimeOfDay.NIGHT,
                                InventoryWorldEventTracker.Weather.RAIN),
                        target(InventoryWorldEventTracker.TargetKind.CONTAINER,
                                "minecraft:chest", "chest[facing=north]", "slot0=stone:2")))));
        assertEquals(List.of(InventoryWorldEventTracker.Type.DIMENSION_CHANGED,
                        InventoryWorldEventTracker.Type.TASK_TARGET_CHANGED),
                types(tracker.observe(snapshot(3, 10, Map.of(), null,
                        new World("minecraft:the_nether", InventoryWorldEventTracker.TimeOfDay.NIGHT,
                                InventoryWorldEventTracker.Weather.RAIN), null))));
    }

    private static InventoryWorldEventTracker.Snapshot snapshot(
            long tick, int freeSlots, Map<String, Integer> inventory,
            InventoryWorldEventTracker.ResourceGoal goal, World world,
            InventoryWorldEventTracker.Target target) {
        return new InventoryWorldEventTracker.Snapshot("companion", "behavior", tick, Instant.EPOCH,
                36, freeSlots, inventory, goal, world.dimension, world.time, world.weather, target);
    }

    private static World day() {
        return new World("minecraft:overworld", InventoryWorldEventTracker.TimeOfDay.DAY,
                InventoryWorldEventTracker.Weather.CLEAR);
    }

    private static InventoryWorldEventTracker.Target target(
            InventoryWorldEventTracker.TargetKind kind, String blockId,
            String blockFingerprint, String containerFingerprint) {
        return new InventoryWorldEventTracker.Target("minecraft:overworld:1,2,3", kind, true,
                blockId, blockFingerprint, kind == InventoryWorldEventTracker.TargetKind.CONTAINER
                ? "ChestBlockEntity" : null, containerFingerprint);
    }

    private static List<InventoryWorldEventTracker.Type> types(
            List<InventoryWorldEventTracker.Event> events) {
        return events.stream().map(InventoryWorldEventTracker.Event::type).toList();
    }

    private record World(String dimension, InventoryWorldEventTracker.TimeOfDay time,
                         InventoryWorldEventTracker.Weather weather) { }
}
