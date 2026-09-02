package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import com.mccompanion.minecraft.fabric.PrimitiveObservationService;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;

/** Real Minecraft observation + Runtime/Bridge chain. Arena and entities are labeled fixtures. */

public final class WorldModelGameTests implements FabricGameTest {
    @GameTest(batch = "world_model", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1200000)
    public void realWorldFeedsBoundedModel(GameTestHelper helper) {
        new Fixture(helper).start();
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final CompanionRegistry registry;
        final ServerPlayer owner;
        final FakeConnection connection = new FakeConnection();
        final CompanionPlayer body;
        final BlockPos chest;
        final Cow cow;
        final Zombie hostile;
        final ItemEntity drop;
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        boolean changed, sawPath;
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            var level = helper.getLevel();
            level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
            for (int x = 159; x <= 161; x++) for (int z = 159; z <= 161; z++) level.setChunkForced(x, z, true);
            for (int x = 2548; x <= 2582; x++) for (int z = 2548; z <= 2574; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
                for (int y = 100; y <= 104; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, 105, z), Blocks.STONE.defaultBlockState());
            }
            owner = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "WorldModelOwner"), ClientInformation.createDefault());
            owner.moveTo(2560.5, 100, 2560.5, 0, 0); // Fixture spawn, before companion creation.
            level.getServer().getPlayerList().placeNewPlayer(connection, owner, CommonListenerCookie.createInitial(owner.getGameProfile(), false));
            registry = MinecraftAiCompanionFabric.integrationRegistryFor(level.getServer());
            helper.assertTrue(registry.create(owner, "WorldModelBody").success(), "body creation failed");
            body = registry.liveBodyForOwner(owner.getUUID());
            chest = body.blockPosition().offset(0, 0, 3);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            cow = EntityType.COW.create(level);
            cow.moveTo(body.getX() + 3, 100, body.getZ() - 3, 0, 0);
            cow.setNoAi(true); cow.setPersistenceRequired(); helper.assertTrue(level.addFreshEntity(cow), "fixture cow spawn failed");
            hostile = EntityType.ZOMBIE.create(level);
            hostile.moveTo(body.getX() - 9, 100, body.getZ() + 8, 0, 0);
            hostile.setNoAi(true); hostile.setPersistenceRequired(); helper.assertTrue(level.addFreshEntity(hostile), "fixture hostile spawn failed");
            drop = new ItemEntity(level, body.getX() + 2, 100, body.getZ() + 3, new ItemStack(Items.DIAMOND));
            drop.setNeverPickUp(); drop.setUnlimitedLifetime(); helper.assertTrue(level.addFreshEntity(drop), "fixture drop spawn failed");
        }

        void start() {
            String id = body.getUUID().toString();
            helper.assertTrue(hostile.isAlive(), "fixture hostile died before observation");
            var local = PrimitiveObservationService.localWorld(registry, id);
            helper.assertTrue(local.path("entities").size() <= 32, "unbounded body query");
            var observedIds = new java.util.HashSet<String>();
            for (var entity : local.path("entities")) observedIds.add(entity.path("entityId").asText());
            if (!observedIds.containsAll(java.util.List.of(cow.getUUID().toString(), hostile.getUUID().toString(),
                    drop.getUUID().toString())) || !body.onGround()) {
                helper.assertTrue(System.nanoTime() < deadline - java.time.Duration.ofSeconds(75).toNanos(),
                        "fixture entities did not become observable in loaded arena: " + local);
                helper.runAfterDelay(1, this::start); return;
            }
            if (!Boolean.getBoolean("mccompanion.world.e2e")) {
                // Default suite still exercises real observation without requiring an external process.
                cow.hurt(body.damageSources().generic(), 1000);
                helper.assertTrue(!cow.isAlive(), "fixture death failed");
                for (var entity : PrimitiveObservationService.localWorld(registry, id).path("entities"))
                    helper.assertTrue(!entity.path("entityId").asText().equals(cow.getUUID().toString()), "dead entity still current");
                finish(); return;
            }
            org.slf4j.LoggerFactory.getLogger("WorldModelE2E").info(
                    "world_model_ready companion={} cow={} drop={} hostile={} chest={},{},{} target={},100,{} finish={},100,{}",
                    id, cow.getUUID(), drop.getUUID(), hostile.getUUID(), chest.getX(), chest.getY(), chest.getZ(),
                    body.blockPosition().getX() + 6, body.blockPosition().getZ(),
                    body.blockPosition().getX() + 12, body.blockPosition().getZ());
            tick(body.blockPosition().getX() + 6, body.blockPosition().getX() + 12, body.blockPosition().getZ());
        }

        void tick(double targetX, double finishX, double targetZ) {
            helper.assertTrue(System.nanoTime() < deadline, "external Runtime did not complete world-model chain");
            helper.assertTrue(body.isAlive(), "body died during observation chain");
            var path = registry.worldNavigation(body.getUUID().toString()).get("nextSteps");
            if (path instanceof java.util.List<?> steps && !steps.isEmpty()) sawPath = true;
            var state = registry.runtimeSnapshots(true).stream().filter(s -> s.companionId().equals(body.getUUID().toString())).findFirst().orElseThrow();
            helper.assertTrue(!state.behaviorState().equalsIgnoreCase("PAUSED"), "navigation paused: " + state.evidenceSummary());
            // Match the production NavigateTo contract: observed arrival within 1.5 blocks.
            if (!changed && body.distanceToSqr(targetX, 100, targetZ) <= 2.25
                    && state.behaviorId() != null && state.behaviorState().equalsIgnoreCase("IDLE")) {
                changed = true;
                // Fixture-driven environmental change, never used to complete the movement task.
                body.serverLevel().setBlockAndUpdate(chest, Blocks.STONE.defaultBlockState());
                cow.hurt(body.damageSources().generic(), 1000);
                org.slf4j.LoggerFactory.getLogger("WorldModelE2E").info("world_model_changed companion={}", body.getUUID());
            }
            if (changed && body.distanceToSqr(finishX, 100, targetZ) <= 2.25 && state.behaviorState().equalsIgnoreCase("IDLE")
                    && state.behaviorId() != null && state.behaviorId().equals(registry.runtimeLastPublishedBehaviorId())) {
                helper.assertTrue(sawPath, "real Navigation did not expose bounded route steps");
                helper.assertTrue(!cow.isAlive(), "fixture death did not occur");
                helper.assertTrue(body.serverLevel().getBlockState(chest).is(Blocks.STONE), "fixture block replacement missing");
                finish(); return;
            }
            helper.runAfterDelay(1, () -> tick(targetX, finishX, targetZ));
        }

        void finish() {
            hostile.discard(); drop.discard(); cow.discard();
            helper.assertTrue(registry.remove(owner).success(), "companion cleanup failed");
            body.serverLevel().getServer().getPlayerList().remove(owner);
            connection.disconnect(Component.literal("World Model GameTest complete"));
            for (int x = 159; x <= 161; x++) for (int z = 159; z <= 161; z++) body.serverLevel().setChunkForced(x, z, false);
            helper.succeed();
        }
    }
}
