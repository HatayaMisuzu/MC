package com.mccompanion.core.body.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SmallBlueprintExecutorTest {
    @Test
    void checksAllMaterialsBeforeWorkBuildsBottomUpAndDoesNotRepeatCompletedBlocks() {
        SmallBlueprint plan = plan(List.of(
                block(0, 1, 0, "minecraft:oak_planks", List.of("minecraft:spruce_planks")),
                block(0, 0, 0, "minecraft:cobblestone", List.of())));
        FakeWorld world = new FakeWorld();
        world.blocks.put(new SmallBlueprintExecutor.Position(0, 63, 0), solid("minecraft:stone"));
        world.inventory.put("minecraft:cobblestone", 1);
        world.inventory.put("minecraft:spruce_planks", 1);
        var executor = new SmallBlueprintExecutor();
        var session = new SmallBlueprintExecutor.Session(plan);

        assertEquals(SmallBlueprintExecutor.Status.RUNNING, executor.tick(session, world).status());
        assertEquals("minecraft:cobblestone", world.actions.get(0));
        assertEquals(SmallBlueprintExecutor.Status.RUNNING, executor.tick(session, world).status());
        assertEquals("minecraft:spruce_planks", world.actions.get(1));
        assertEquals(SmallBlueprintExecutor.Status.COMPLETE, executor.tick(session, world).status());
        assertEquals(2, world.actions.size());
    }

    @Test
    void pausesBeforeMutationForInsufficientMaterialsAndReconcilesChangedCompletedPosition() {
        SmallBlueprint plan = plan(List.of(block(0, 0, 0, "minecraft:cobblestone", List.of())));
        FakeWorld world = new FakeWorld();
        world.blocks.put(new SmallBlueprintExecutor.Position(0, 63, 0), solid("minecraft:stone"));
        var executor = new SmallBlueprintExecutor();
        var session = new SmallBlueprintExecutor.Session(plan);
        assertEquals("MATERIALS_INSUFFICIENT", executor.tick(session, world).code());
        assertTrue(world.actions.isEmpty());
        world.inventory.put("minecraft:cobblestone", 2);
        session.requireReconciliation();
        executor.tick(session, world);
        assertEquals(1, world.actions.size());
        world.blocks.remove(new SmallBlueprintExecutor.Position(0, 64, 0));
        session.requireReconciliation();
        executor.tick(session, world);
        assertEquals(2, world.actions.size());
    }

    @Test
    void usesBoundedTemporarySupportAndCleansItAfterFinalVerification() {
        SmallBlueprint plan = new SmallBlueprint(new SmallBlueprint.Anchor("minecraft:overworld", 0, 65, 0),
                new SmallBlueprint.Size(1, 1, 1), List.of(block(0, 0, 0, "minecraft:oak_planks", List.of())),
                new SmallBlueprint.SupportPolicy(List.of("minecraft:cobblestone"), 1, true));
        FakeWorld world = new FakeWorld();
        world.blocks.put(new SmallBlueprintExecutor.Position(0, 63, 0), solid("minecraft:stone"));
        world.inventory.put("minecraft:oak_planks", 1);
        world.inventory.put("minecraft:cobblestone", 1);
        var executor = new SmallBlueprintExecutor();
        var session = new SmallBlueprintExecutor.Session(plan);
        executor.tick(session, world);
        executor.tick(session, world);
        executor.tick(session, world);
        assertEquals(SmallBlueprintExecutor.Status.COMPLETE, executor.tick(session, world).status());
        assertTrue(world.block(new SmallBlueprintExecutor.Position(0, 64, 0)).replaceable());
    }

    @Test
    void occupancyUnreachableAndUnplaceablePauseWithoutFabricatingProgressThenResume() {
        var target = new SmallBlueprintExecutor.Position(0, 64, 0);
        FakeWorld world = new FakeWorld();
        world.blocks.put(target.offset(SmallBlueprintExecutor.Direction.DOWN), solid("minecraft:stone"));
        world.inventory.put("minecraft:cobblestone", 1);
        var session = new SmallBlueprintExecutor.Session(plan(List.of(block(0, 0, 0, "minecraft:cobblestone", List.of()))));
        var executor = new SmallBlueprintExecutor();
        world.occupied = true;
        assertEquals("PLAYER_OCCUPIED", executor.tick(session, world).code());
        world.occupied = false;
        world.blocks.put(target, solid("minecraft:glass"));
        assertEquals("PLACEMENT_TARGET_OCCUPIED", executor.tick(session, world).code());
        world.blocks.remove(target);
        world.preparation = SmallBlueprintExecutor.Preparation.BLOCKED;
        assertEquals("PATH_UNREACHABLE", executor.tick(session, world).code());
        world.preparation = SmallBlueprintExecutor.Preparation.READY;
        world.placementFailure = "PLACEMENT_STATE_UNAVAILABLE";
        assertEquals("PLACEMENT_STATE_UNAVAILABLE", executor.tick(session, world).code());
        assertTrue(session.completed().isEmpty());
        assertTrue(world.actions.isEmpty());
        world.placementFailure = null;
        session.requireReconciliation();
        executor.tick(session, world);
        assertEquals(SmallBlueprintExecutor.Status.COMPLETE, executor.tick(session, world).status());
    }

    @Test
    void supportBudgetIsCumulativeAcrossCleanupAndRestoration() {
        var plan = new SmallBlueprint(new SmallBlueprint.Anchor("minecraft:overworld", 0, 65, 0),
                new SmallBlueprint.Size(3, 1, 1), List.of(
                block(0, 0, 0, "minecraft:oak_planks", List.of()),
                block(2, 0, 0, "minecraft:oak_planks", List.of())),
                new SmallBlueprint.SupportPolicy(List.of("minecraft:dirt"), 1, true));
        FakeWorld world = new FakeWorld();
        world.blocks.put(new SmallBlueprintExecutor.Position(0, 63, 0), solid("minecraft:stone"));
        world.blocks.put(new SmallBlueprintExecutor.Position(2, 63, 0), solid("minecraft:stone"));
        world.inventory.put("minecraft:oak_planks", 2);
        world.inventory.put("minecraft:dirt", 2);
        var executor = new SmallBlueprintExecutor();
        var session = new SmallBlueprintExecutor.Session(plan);
        for (int tick = 0; tick < 3; tick++) executor.tick(session, world);
        assertEquals(1, session.supportsPlaced());
        assertTrue(session.temporarySupports().isEmpty());
        session = new SmallBlueprintExecutor.Session(plan, session.completed(), session.selections(),
                session.temporarySupports(), session.supportsPlaced());
        assertEquals("TEMPORARY_SUPPORT_BUDGET_EXCEEDED", executor.tick(session, world).code());
        assertEquals(1, session.completed().size());
    }

    @Test
    void changedTemporarySupportIsNotDestroyedAndWaitingIsBounded() {
        var plan = new SmallBlueprint(new SmallBlueprint.Anchor("minecraft:overworld", 0, 65, 0),
                new SmallBlueprint.Size(1, 1, 1), List.of(block(0, 0, 0, "minecraft:oak_planks", List.of())),
                new SmallBlueprint.SupportPolicy(List.of("minecraft:dirt"), 1, true));
        FakeWorld world = new FakeWorld();
        world.blocks.put(new SmallBlueprintExecutor.Position(0, 63, 0), solid("minecraft:stone"));
        world.inventory.put("minecraft:oak_planks", 1);
        world.inventory.put("minecraft:dirt", 1);
        var executor = new SmallBlueprintExecutor();
        var session = new SmallBlueprintExecutor.Session(plan);
        executor.tick(session, world);
        executor.tick(session, world);
        world.blocks.put(new SmallBlueprintExecutor.Position(0, 64, 0), solid("minecraft:glass"));
        assertEquals("TEMPORARY_SUPPORT_CHANGED", executor.tick(session, world).code());
        assertEquals("minecraft:glass", world.block(new SmallBlueprintExecutor.Position(0, 64, 0)).id());
        world.blocks.put(new SmallBlueprintExecutor.Position(0, 64, 0), solid("minecraft:dirt"));
        world.preparation = SmallBlueprintExecutor.Preparation.WAIT;
        session.requireReconciliation();
        for (int tick = 0; tick < 1200; tick++) {
            assertEquals(SmallBlueprintExecutor.Status.RUNNING, executor.tick(session, world).status());
        }
        assertEquals("BLUEPRINT_PROGRESS_TIMEOUT", executor.tick(session, world).code());
    }

    private static SmallBlueprint plan(List<SmallBlueprint.Block> blocks) {
        return new SmallBlueprint(new SmallBlueprint.Anchor("minecraft:overworld", 0, 64, 0),
                new SmallBlueprint.Size(1, 2, 1), blocks, SmallBlueprint.SupportPolicy.none());
    }

    private static SmallBlueprint.Block block(int x, int y, int z, String id, List<String> alternatives) {
        return new SmallBlueprint.Block(new SmallBlueprint.Offset(x, y, z), id, Map.of(), alternatives);
    }

    private static SmallBlueprintExecutor.WorldBlock solid(String id) {
        return new SmallBlueprintExecutor.WorldBlock(id, Map.of(), false, true);
    }

    private static final class FakeWorld implements SmallBlueprintExecutor.Environment {
        final Map<SmallBlueprintExecutor.Position, SmallBlueprintExecutor.WorldBlock> blocks = new HashMap<>();
        final Map<String, Integer> inventory = new HashMap<>();
        final java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        boolean occupied;
        SmallBlueprintExecutor.Preparation preparation = SmallBlueprintExecutor.Preparation.READY;
        String placementFailure;
        @Override public SmallBlueprintExecutor.WorldBlock block(SmallBlueprintExecutor.Position position) {
            return blocks.getOrDefault(position,
                    new SmallBlueprintExecutor.WorldBlock("minecraft:air", Map.of(), true, true));
        }
        @Override public boolean validMaterial(String blockId, Map<String, String> state) { return blockId.contains(":"); }
        @Override public int inventoryCount(String blockId) { return inventory.getOrDefault(blockId, 0); }
        @Override public boolean playerOccupies(SmallBlueprintExecutor.Position position) { return occupied; }
        @Override public SmallBlueprintExecutor.Preparation prepare(SmallBlueprintExecutor.Position target,
                                                                    SmallBlueprintExecutor.Position interactionBlock) {
            return preparation;
        }
        @Override public String preparationFailure() { return null; }
        @Override public String place(SmallBlueprintExecutor.Position target,
                                      SmallBlueprintExecutor.Position support,
                                      SmallBlueprintExecutor.Direction face, String blockId,
                                      Map<String, String> requestedState) {
            if (placementFailure != null) return placementFailure;
            if (inventory.getOrDefault(blockId, 0) < 1) return "MATERIALS_INSUFFICIENT";
            inventory.merge(blockId, -1, Integer::sum);
            blocks.put(target, new SmallBlueprintExecutor.WorldBlock(blockId, requestedState, false, true));
            actions.add(blockId);
            return null;
        }
        @Override public String remove(SmallBlueprintExecutor.Position position, String blockId) {
            blocks.remove(position); return null;
        }
    }
}
