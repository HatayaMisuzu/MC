package com.mccompanion.minecraft.v120;

import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Forge 1.20.1 headless lifecycle coverage using a small vanilla empty structure. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompanionLifecycleForgeGameTests {
    private CompanionLifecycleForgeGameTests() {
    }

    @GameTest(
            templateNamespace = "minecraft",
            template = "bastion/mobs/empty",
            timeoutTicks = 240)
    public static void createMoveStopSleepAndWake(GameTestHelper helper) {
        FakeConnection ownerConnection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "companion-test-owner"));
        Vec3 ownerSpawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, 1.0D));
        owner.moveTo(ownerSpawn.x, ownerSpawn.y, ownerSpawn.z, 0.0F, 0.0F);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(ownerConnection, owner);
        CompanionRegistry registry = MinecraftAiCompanionForge.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry != null, "server companion registry was not initialized");

        CompanionRegistry.Result created = registry.create(owner, "ForgeTestComp");
        helper.assertTrue(created.success(), "create failed: " + created.code() + ": " + created.message());
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "create did not add a live companion body");
        helper.assertTrue(body.ownerId().equals(owner.getUUID()), "owner ACL was not attached to body");
        helper.assertTrue(body.fakeConnection().discardedPacketCount() > 0L,
                "login did not exercise fake connection packet disposal");
        helper.assertTrue(body.fakeConnection().retainedPacketCount() == 0,
                "fake connection retained a packet during login");

        Vec3 before = body.position();
        CompanionRegistry.Result moving = registry.goTo(owner, before.x + 4.0D, before.y, before.z);
        helper.assertTrue(moving.success(), "goto failed: " + moving.code());
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(body.position().distanceToSqr(before) > 0.20D,
                    "body did not move through vanilla player travel");
            CompanionRegistry.Result stopped = registry.stop(owner);
            helper.assertTrue(stopped.success(), "stop failed: " + stopped.code());
            Vec3 stoppedAt = body.position();
            helper.runAfterDelay(12, () -> {
                helper.assertTrue(body.position().distanceToSqr(stoppedAt) < 0.04D,
                        "body kept moving after stop");
                helper.assertTrue(body.fakeConnection().retainedPacketCount() == 0,
                        "fake connection retained packets during sustained ticking");
                helper.assertTrue(body.addItem(new ItemStack(Items.DIAMOND)), "test item add failed");
                helper.assertTrue(registry.despawn(owner).success(), "despawn failed");
                helper.assertTrue(registry.liveBodyForOwner(owner.getUUID()) == null,
                        "despawn retained a live body");
                helper.assertTrue(registry.spawn(owner).success(), "spawn failed");
                CompanionPlayer reloaded = registry.liveBodyForOwner(owner.getUUID());
                helper.assertTrue(reloaded != null, "spawn did not restore body");
                helper.assertTrue(reloaded.getUUID().equals(body.getUUID()), "body UUID changed across sleep/wake");
                helper.assertTrue(reloaded.fakeConnection().retainedPacketCount() == 0,
                        "replacement fake connection retained packets");
                helper.assertTrue(reloaded.getInventory().contains(new ItemStack(Items.DIAMOND)),
                        "inventory did not persist across sleep/wake");
                helper.assertTrue(reloaded.hurt(reloaded.damageSources().fellOutOfWorld(), Float.MAX_VALUE),
                        "lethal vanilla damage was rejected");
                helper.runAfterDelay(4, () -> {
                    helper.assertTrue(registry.liveBodyForOwner(owner.getUUID()) == null,
                            "dead body was not moved to recovery sleep");
                    helper.assertTrue(registry.spawn(owner).success(), "death recovery spawn failed");
                    CompanionPlayer recovered = registry.liveBodyForOwner(owner.getUUID());
                    helper.assertTrue(recovered != null, "death recovery produced no body");
                    helper.assertTrue(recovered.getUUID().equals(body.getUUID()), "UUID changed after death");
                    helper.assertTrue(!recovered.getInventory().contains(new ItemStack(Items.DIAMOND)),
                            "death recovery duplicated dropped inventory");
                    helper.assertTrue(registry.remove(owner).success(), "remove failed");
                    helper.getLevel().getServer().getPlayerList().remove(owner);
                    ownerConnection.disconnect(Component.literal("Forge GameTest complete"));
                    helper.succeed();
                });
            });
        });
    }
}
