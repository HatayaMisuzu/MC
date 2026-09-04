package com.mccompanion.minecraft.v120;

import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Generated arena/item/obstacle fixtures; all resource collection, movement and deposit use real player actions. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EventReplanForgeGameTests {
    @GameTest(batch = "event_replan", templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 1200000)
    public static void collectReplanAndDeliverOriginalGoal(GameTestHelper helper) { new Fixture(helper).start(); }

    private static final class Fixture {
        final GameTestHelper helper;
        final CompanionRegistry registry;
        final ServerPlayer owner;
        final FakeConnection connection = new FakeConnection();
        final CompanionPlayer body;
        final BlockPos blocked, destination, detour;
        final ItemEntity drop;
        final boolean mobSpawning;
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(180).toNanos();
        boolean changed, collected, approached;
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            var level = helper.getLevel();
            mobSpawning = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
            for (int x = 187; x <= 190; x++) for (int z = 187; z <= 189; z++) level.setChunkForced(x, z, true);
            for (int x = 2995; x <= 3045; x++) for (int z = 2995; z <= 3030; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
                for (int y = 100; y <= 105; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, 106, z), Blocks.STONE.defaultBlockState());
            }
            // Fixture isolation before the owner/body or tested task exists; no ambient threat scenario here.
            level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                    new net.minecraft.world.phys.AABB(2975, 90, 2975, 3065, 120, 3050),
                    entity -> entity instanceof net.minecraft.world.entity.monster.Enemy).forEach(net.minecraft.world.entity.Entity::discard);
            owner = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "ReplanOwner"));
            owner.moveTo(3004.5, 100, 3004.5, 0, 0); // Fixture spawn only, before the tested execution.
            level.getServer().getPlayerList().placeNewPlayer(connection, owner);
            registry = MinecraftAiCompanionForge.integrationRegistryFor(level.getServer());
            helper.assertTrue(registry.create(owner, "ReplanBody").success(), "body creation failed");
            body = registry.liveBodyForOwner(owner.getUUID());
            blocked = body.blockPosition().offset(10, 0, 0);
            detour = body.blockPosition().offset(10, 0, 7);
            destination = body.blockPosition().offset(18, 0, 0);
            level.setBlockAndUpdate(destination, Blocks.CHEST.defaultBlockState());
            drop = new ItemEntity(level, body.getX() + 4, 100, body.getZ(), new ItemStack(Items.DIAMOND));
            drop.setUnlimitedLifetime();
            helper.assertTrue(level.addFreshEntity(drop), "fixture resource spawn failed");
        }
        void start() {
            if (!body.onGround()) {
                helper.assertTrue(System.nanoTime() < deadline, "fixture body did not settle");
                helper.runAfterDelay(1, this::start); return;
            }
            helper.assertTrue(Boolean.getBoolean("mccompanion.replan.e2e"), "this focused test requires the external Runtime harness");
            org.slf4j.LoggerFactory.getLogger("EventReplanE2E").info(
                    "event_replan_ready companion={} blocked={},{},{} detour={},{},{} chest={},{},{}",
                    body.getUUID(), blocked.getX(), blocked.getY(), blocked.getZ(),
                    detour.getX(), detour.getY(), detour.getZ(), destination.getX(), destination.getY(), destination.getZ());
            tick();
        }
        void tick() {
            helper.assertTrue(System.nanoTime() < deadline, "external Runtime did not finish event replan resource delivery");
            helper.assertTrue(body.isAlive(), "body died");
            if (!changed && body.getInventory().countItem(Items.DIAMOND) == 1) {
                collected = true; changed = true;
                // Environmental invalidation fixture. Never edits the companion or its inventory.
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 4; y++)
                    body.serverLevel().setBlockAndUpdate(blocked.offset(x, y, z), Blocks.STONE.defaultBlockState());
                org.slf4j.LoggerFactory.getLogger("EventReplanE2E").info("event_replan_route_invalidated companion={}", body.getUUID());
            }
            if (changed && body.distanceToSqr(detour.getX(), detour.getY(), detour.getZ()) <= 4) approached = true;
            var chest = (ChestBlockEntity) body.serverLevel().getBlockEntity(destination);
            int deposited = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++)
                if (chest.getItem(slot).is(Items.DIAMOND)) deposited += chest.getItem(slot).getCount();
            if (deposited == 1) {
                helper.assertTrue(collected && changed && approached, "original resource goal bypassed collect/invalidation/detour");
                helper.assertTrue(body.getInventory().countItem(Items.DIAMOND) == 0, "diamond was duplicated");
                org.slf4j.LoggerFactory.getLogger("EventReplanE2E").info(
                        "event_replan_delivered companion={} diamonds={} detourVerified=true", body.getUUID(), deposited);
                // Wait for the real Body terminal publication before removing its bridge session.
                finishWhenPublished(); return;
            }
            helper.runAfterDelay(1, this::tick);
        }
        void finishWhenPublished() {
            helper.assertTrue(System.nanoTime() < deadline, "deposit terminal publication missing");
            var state = registry.runtimeSnapshots(true).stream()
                    .filter(s -> s.companionId().equals(body.getUUID().toString())).findFirst().orElseThrow();
            if (!state.behaviorState().equalsIgnoreCase("IDLE") || state.behaviorId() == null
                    || !state.behaviorId().equals(registry.runtimeLastPublishedBehaviorId())) {
                helper.runAfterDelay(1, this::finishWhenPublished); return;
            }
            drop.discard();
            helper.assertTrue(registry.remove(owner).success(), "companion cleanup failed");
            body.serverLevel().getServer().getPlayerList().remove(owner);
            connection.disconnect(Component.literal("Event Replan GameTest complete"));
            body.serverLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING)
                    .set(mobSpawning, body.serverLevel().getServer());
            for (int x = 187; x <= 190; x++) for (int z = 187; z <= 189; z++) body.serverLevel().setChunkForced(x, z, false);
            helper.succeed();
        }
    }
}
