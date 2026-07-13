package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Headless integration tests that exercise the real ServerPlayer body and fake connection. */
public final class CompanionLifecycleGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft_ai_companion_gametest");
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
    public void blockedGotoStopsWithBoundedStuckFailure(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "BlockedCompanion").success(), "blocked test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "blocked test created no live body");
        BlockPos origin = body.blockPosition();
        for (int y = -1; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || z != 0 || y == -1 || y == 2) {
                        body.serverLevel().setBlockAndUpdate(origin.offset(x, y, z), Blocks.BEDROCK.defaultBlockState());
                    }
                }
            }
        }
        helper.assertTrue(registry.goTo(owner, origin.getX() + 8.0D, origin.getY(), origin.getZ()).success(),
                "blocked goto failed to start");
        helper.runAfterDelay(450, () -> {
            String status = registry.status(owner);
            helper.assertTrue(status.contains("mode=PAUSED"), "blocked goto did not enter safe PAUSED state: " + status);
            helper.assertTrue(status.contains("failure=STUCK"), "blocked goto did not expose STUCK evidence: " + status);
            helper.assertValueEqual(body.fakeConnection().retainedPacketCount(), 0,
                    "blocked goto retained packets in fake connection");
            helper.assertTrue(registry.remove(owner).success(), "blocked test cleanup failed");
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100000)
    public void createMoveStopSleepAndWake(GameTestHelper helper) {
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry != null, "server companion registry was not initialized");
        if (Boolean.getBoolean("mccompanion.persistence.verify")) {
            var snapshots = registry.runtimeSnapshots(false);
            helper.assertValueEqual(snapshots.size(), 1, "restart did not recover exactly one companion record");
            var snapshot = snapshots.get(0);
            CompanionPlayer restored = registry.liveBodyForOwner(java.util.UUID.fromString(snapshot.ownerId()));
            helper.assertTrue(restored != null, "restart did not automatically restore the live body");
            helper.assertValueEqual(restored.getUUID().toString(), snapshot.companionId(),
                    "restart changed companion UUID");
            helper.assertTrue(restored.getInventory().contains(new ItemStack(Items.DIAMOND)),
                    "restart lost persisted companion inventory");
            helper.assertTrue(registry.removeByOwnerId(java.util.UUID.fromString(snapshot.ownerId())).success(),
                    "restart verification cleanup failed");
            helper.succeed();
            return;
        }

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.setGameMode(GameType.SURVIVAL);

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
        if (Boolean.getBoolean("mccompanion.stability")) {
            long discardedAtStart = body.fakeConnection().discardedPacketCount();
            owner.moveTo(owner.getX(), owner.getY(), owner.getZ() + 20.0D, owner.getYRot(), owner.getXRot());
            helper.assertTrue(registry.follow(owner).success(), "stability follow failed to start");
            helper.runAfterDelay(2400, () -> {
                helper.assertTrue(registry.liveBodyForOwner(owner.getUUID()) == body,
                        "companion body was lost during the two-minute stability run");
                helper.assertTrue(body.fakeConnection().discardedPacketCount() >= discardedAtStart,
                        "fake connection diagnostic counter moved backwards during stability run");
                helper.assertValueEqual(body.fakeConnection().retainedPacketCount(), 0,
                        "fake connection retained packets during the two-minute stability run");
                helper.assertTrue(registry.stop(owner).success(), "stability stop failed");
                helper.assertTrue(registry.remove(owner).success(), "stability cleanup failed");
                helper.succeed();
            });
            return;
        }
        if (Boolean.getBoolean("mccompanion.persistence.seed")) {
            helper.assertTrue(body.addItem(new ItemStack(Items.DIAMOND)),
                    "restart seed item could not enter inventory");
            helper.succeed();
            return;
        }

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

                owner.moveTo(owner.getX(), owner.getY(), owner.getZ() + 6.0D, owner.getYRot(), owner.getXRot());
                double followDistanceBefore = body.distanceToSqr(owner);
                helper.assertTrue(registry.follow(owner).success(), "follow failed to start");
                helper.runAfterDelay(40, () -> {
                    helper.assertTrue(body.distanceToSqr(owner) < followDistanceBefore,
                            "follow did not continuously close distance to owner");
                    helper.assertTrue(registry.pause(owner).success(), "pause failed");
                    Vec3 pausedAt = body.position();
                    owner.moveTo(owner.getX(), owner.getY(), owner.getZ() + 4.0D, owner.getYRot(), owner.getXRot());
                    helper.runAfterDelay(12, () -> {
                        helper.assertTrue(body.position().distanceToSqr(pausedAt) < 0.04D,
                                "paused companion kept moving");
                        helper.assertTrue(registry.resume(owner).success(), "resume failed");
                        helper.runAfterDelay(40, () -> {
                            helper.assertTrue(body.position().distanceToSqr(pausedAt) > 0.20D,
                                    "resumed follow did not restore movement");
                            helper.assertTrue(registry.stop(owner).success(), "stop after resume failed");

                helper.assertTrue(body.addItem(new ItemStack(Items.DIAMOND)),
                        "test item could not enter companion inventory through player addItem");
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
                helper.assertTrue(reloaded.getInventory().contains(new ItemStack(Items.DIAMOND)),
                        "inventory did not persist across sleep/wake");
                helper.assertTrue(reloaded.hurt(reloaded.damageSources().genericKill(), Float.MAX_VALUE),
                        "vanilla damage path did not accept lethal damage");
                helper.runAfterDelay(4, () -> {
                    helper.assertTrue(registry.liveBodyForOwner(owner.getUUID()) == null,
                            "dead body was not moved to recovery sleep");
                    helper.assertTrue(registry.spawn(owner).success(), "death recovery spawn failed");
                    CompanionPlayer recovered = registry.liveBodyForOwner(owner.getUUID());
                    helper.assertTrue(recovered != null, "death recovery produced no live body");
                    helper.assertValueEqual(recovered.getUUID(), body.getUUID(),
                            "body UUID changed during death recovery");
                    helper.assertTrue(!recovered.getInventory().contains(new ItemStack(Items.DIAMOND)),
                            "death recovery duplicated the dropped inventory item");

                    if (Boolean.getBoolean("mccompanion.runtime.e2e")) {
                        LOGGER.info("runtime_e2e_ready companion={}", recovered.getUUID());
                        helper.succeedWhen(() -> {
                            helper.assertTrue(registry.runtimeCommandCount() >= 5,
                                    "waiting for Runtime lease/follow/pause/resume/stop commands");
                            CompanionRegistry.Result removed = registry.remove(owner);
                            helper.assertTrue(removed.success(), "remove failed: " + removed.code());
                        });
                    } else {
                        CompanionRegistry.Result removed = registry.remove(owner);
                        helper.assertTrue(removed.success(), "remove failed: " + removed.code());
                        helper.succeed();
                    }
                });
                        });
                    });
                });
            });
        });
    }
}
