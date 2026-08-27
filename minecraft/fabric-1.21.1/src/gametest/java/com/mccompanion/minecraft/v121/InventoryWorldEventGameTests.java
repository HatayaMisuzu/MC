package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.bridge.InventoryWorldEventTracker;
import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import java.time.Instant;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real 1.21.1 inventory and exact task-target observation evidence. */
public final class InventoryWorldEventGameTests implements FabricGameTest {
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100, batch = "inventoryWorldEvents")
    public void inventoryThresholdGoalAndTargetEdgesUseRealState(GameTestHelper helper) {
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "InventoryWorldBody").success(), "companion create failed");
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElseThrow();
        CompanionPlayer body = registry.runtimeBody(companionId);
        helper.assertTrue(body != null, "companion body missing");
        BlockPos target = body.blockPosition().offset(1, 0, 0);
        body.serverLevel().setBlockAndUpdate(target, Blocks.CHEST.defaultBlockState());
        helper.assertTrue(body.serverLevel().getBlockEntity(target) instanceof Container,
                "target chest missing");
        Container chest = (Container) body.serverLevel().getBlockEntity(target);

        SkillParameters parameters = parameters(body, target);
        CompanionRegistry.InventoryWorldEventBinding binding =
                new CompanionRegistry.InventoryWorldEventBinding(companionId, body, "behavior", parameters);
        InventoryWorldEventObservationService observations = new InventoryWorldEventObservationService();
        InventoryWorldEventTracker tracker = new InventoryWorldEventTracker();
        helper.assertTrue(types(tracker.observe(observations.snapshot(binding, 1, Instant.now())))
                        .equals(List.of(InventoryWorldEventTracker.Type.KEY_ITEM_INSUFFICIENT)),
                "initial real inventory shortage edge missing");
        body.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
        helper.assertTrue(types(tracker.observe(observations.snapshot(binding, 2, Instant.now())))
                        .equals(List.of(InventoryWorldEventTracker.Type.KEY_ITEM_ACQUIRED)),
                "real key item acquisition edge missing");
        body.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        helper.assertTrue(types(tracker.observe(observations.snapshot(binding, 3, Instant.now())))
                        .equals(List.of(InventoryWorldEventTracker.Type.RESOURCE_TARGET_REACHED)),
                "real resource target edge missing");

        for (int slot = 1; slot <= 33; slot++) {
            body.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        tracker.observe(observations.snapshot(binding, 4, Instant.now()));
        helper.assertTrue(types(tracker.observe(observations.snapshot(binding, 5, Instant.now())))
                        .equals(List.of(InventoryWorldEventTracker.Type.INVENTORY_NEAR_FULL)),
                "stable near-full edge missing");
        body.getInventory().setItem(34, new ItemStack(Items.COBBLESTONE, 64));
        body.getInventory().setItem(35, new ItemStack(Items.COBBLESTONE, 64));
        tracker.observe(observations.snapshot(binding, 6, Instant.now()));
        helper.assertTrue(types(tracker.observe(observations.snapshot(binding, 7, Instant.now())))
                        .equals(List.of(InventoryWorldEventTracker.Type.INVENTORY_FULL)),
                "stable full edge missing");

        chest.setItem(0, new ItemStack(Items.IRON_INGOT));
        helper.assertTrue(types(tracker.observe(observations.snapshot(binding, 8, Instant.now())))
                        .equals(List.of(InventoryWorldEventTracker.Type.TARGET_CONTAINER_CHANGED)),
                "exact target container change edge missing");
        body.serverLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
        helper.assertTrue(types(tracker.observe(observations.snapshot(binding, 9, Instant.now())))
                        .equals(List.of(InventoryWorldEventTracker.Type.TASK_TARGET_CHANGED)),
                "exact task target replacement edge missing");
        helper.assertTrue(tracker.observe(observations.snapshot(binding, 10, Instant.now())).isEmpty(),
                "sustained inventory/world state produced tick spam");

        helper.assertTrue(registry.remove(owner).success(), "fixture companion cleanup failed");
        helper.getLevel().getServer().getPlayerList().remove(owner);
        helper.succeed();
    }

    private static SkillParameters parameters(CompanionPlayer body, BlockPos target) {
        return new SkillParameters("WithdrawFromStorage", "minecraft:diamond", 2, false,
                body.serverLevel().dimension().location().toString(), target.getX(), target.getY(), target.getZ(),
                "", "UP", "MAIN_HAND", "", null, null, "", null, "");
    }

    private static List<InventoryWorldEventTracker.Type> types(
            List<InventoryWorldEventTracker.Event> events) {
        return events.stream().map(InventoryWorldEventTracker.Event::type).toList();
    }
}
