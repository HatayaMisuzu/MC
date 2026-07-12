package com.mccompanion.minecraft.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Headless integration tests that exercise the real ServerPlayer body and fake connection. */
public final class CompanionLifecycleGameTests implements FabricGameTest {
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240)
    public void createMoveStopSleepAndWake(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.setGameMode(GameType.SURVIVAL);
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry != null, "server companion registry was not initialized");

        CompanionRegistry.Result created = registry.create(owner, "TestCompanion");
        helper.assertTrue(created.success(), "create failed: " + created.code() + ": " + created.message());
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "create did not add a live companion body");
        helper.assertTrue(body instanceof ServerPlayer, "companion body is not a ServerPlayer");
        helper.assertValueEqual(body.ownerId(), owner.getUUID(), "owner ACL was not attached to body");
        helper.assertTrue(body.connection != null, "body has no server packet listener");
        helper.assertTrue(body.fakeConnection().discardedPacketCount() > 0L,
                "login did not exercise fake connection packet disposal");
        helper.assertValueEqual(body.fakeConnection().retainedPacketCount(), 0,
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
            long discardedBeforeStop = body.fakeConnection().discardedPacketCount();
            helper.runAfterDelay(12, () -> {
                helper.assertTrue(body.position().distanceToSqr(stoppedAt) < 0.04D,
                        "body kept moving after stop");
                helper.assertTrue(body.fakeConnection().discardedPacketCount() >= discardedBeforeStop,
                        "fake connection diagnostic counter moved backwards");
                helper.assertValueEqual(body.fakeConnection().retainedPacketCount(), 0,
                        "fake connection retained packets during sustained ticking");

                CompanionRegistry.Result slept = registry.despawn(owner);
                helper.assertTrue(slept.success(), "despawn failed: " + slept.code());
                helper.assertTrue(registry.liveBodyForOwner(owner.getUUID()) == null,
                        "despawn retained a live body");
                CompanionRegistry.Result woke = registry.spawn(owner);
                helper.assertTrue(woke.success(), "spawn failed: " + woke.code());
                CompanionPlayer reloaded = registry.liveBodyForOwner(owner.getUUID());
                helper.assertTrue(reloaded != null, "spawn did not restore body");
                helper.assertValueEqual(reloaded.getUUID(), body.getUUID(), "body UUID changed across sleep/wake");
                helper.assertValueEqual(reloaded.fakeConnection().retainedPacketCount(), 0,
                        "replacement fake connection retained packets");

                CompanionRegistry.Result removed = registry.remove(owner);
                helper.assertTrue(removed.success(), "remove failed: " + removed.code());
                helper.succeed();
            });
        });
    }
}
