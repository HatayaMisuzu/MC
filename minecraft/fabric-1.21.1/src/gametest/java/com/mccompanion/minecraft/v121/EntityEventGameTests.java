package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.bridge.EntityEventTracker;
import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Representative real-Minecraft proof for player edges and bounded hostile observation. */
public final class EntityEventGameTests implements FabricGameTest {
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "entityInteraction")
    public void arbitraryPlayerBehaviorsTrackLocallyAndEndOnCancelOrDeath(GameTestHelper helper) {
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer target = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "TargetBody").success(), "entity behavior companion create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "entity behavior body missing");
        net.minecraft.core.BlockPos base = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        Vec3 origin = new Vec3(base.getX() + 2.5D, 100.0D, base.getZ() + 2.5D);
        prepareArena(body, owner, target, origin);
        target.setGameMode(GameType.SPECTATOR);
        String companionId = body.getUUID().toString();
        String lease = "fabric-entity-interaction";
        helper.assertTrue(registry.runtimeAcquireLease(companionId, lease, 1L,
                        System.currentTimeMillis() + 120_000L).success(),
                "entity behavior lease acquisition failed");
        target.teleportTo(target.serverLevel(), origin.x + 10.0D, origin.y, origin.z,
                target.getYRot(), target.getXRot());
        helper.assertTrue(start(registry, companionId, lease, "follow-player", "FollowEntity", target,
                        null, null).success(), "non-owner player follow did not start");
        EntityEventTracker.TargetBinding binding = registry.entityEventTarget(companionId);
        helper.assertTrue(binding != null && binding.identity().equals(target.getUUID().toString())
                        && binding.kind() == EntityEventTracker.TargetKind.FOLLOW,
                "follow did not retain the explicit non-owner UUID");
        await(helper, registry, companionId, 160,
                () -> body.distanceToSqr(target) <= 9.0D,
                "body did not follow the moving non-owner player", () -> {
                    target.teleportTo(target.serverLevel(), origin.x - 10.0D, origin.y, origin.z,
                            target.getYRot(), target.getXRot());
                    await(helper, registry, companionId, 180,
                            () -> body.distanceToSqr(target) <= 9.0D,
                            "body did not update its local route after the target turned", () ->
                                    verifyLossReappearanceAndDistanceControl(
                                            helper, registry, body, owner, target, companionId, lease));
                });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "entityEvents")
    public void playerRangeAndHostileEdgesUseRealEntitiesWithoutTickSpam(GameTestHelper helper) {
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "EventBody").success(), "entity event companion create failed");
        CompanionPlayer body = registry.runtimeBody(registry.runtimeSnapshots(false).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElseThrow());
        helper.assertTrue(body != null, "entity event body missing");
        helper.assertTrue(registry.follow(owner).success(), "follow target setup failed");

        EntityEventObservationService observations = new EntityEventObservationService(helper.getLevel().getServer());
        EntityEventTracker tracker = new EntityEventTracker();
        EntityEventTracker.TargetBinding follow = new EntityEventTracker.TargetBinding(
                owner.getUUID().toString(), EntityEventTracker.TargetKind.FOLLOW);
        tracker.observe(observations.snapshot(body, "follow-event", follow, 1, Instant.now()));

        double originalX = owner.getX();
        double originalY = owner.getY();
        double originalZ = owner.getZ();
        owner.teleportTo(owner.serverLevel(), originalX + 20.0D, originalY, originalZ,
                owner.getYRot(), owner.getXRot());
        List<EntityEventTracker.Type> lost = types(tracker.observe(
                observations.snapshot(body, "follow-event", follow, 2, Instant.now())));
        helper.assertTrue(lost.contains(EntityEventTracker.Type.PLAYER_LEFT_RANGE)
                        && lost.contains(EntityEventTracker.Type.FOLLOW_TARGET_LOST),
                "real owner range loss did not produce both player and follow edges: " + lost);

        owner.teleportTo(owner.serverLevel(), originalX, originalY, originalZ,
                owner.getYRot(), owner.getXRot());
        List<EntityEventTracker.Type> returned = types(tracker.observe(
                observations.snapshot(body, "follow-event", follow, 3, Instant.now())));
        helper.assertTrue(returned.contains(EntityEventTracker.Type.PLAYER_ENTERED_RANGE)
                        && returned.contains(EntityEventTracker.Type.TARGET_REAPPEARED),
                "real owner return did not produce enter/reappear edges: " + returned);

        var zombie = EntityType.ZOMBIE.create(body.serverLevel());
        helper.assertTrue(zombie != null, "hostile fixture creation failed");
        zombie.setNoAi(true);
        zombie.moveTo(body.getX() + 3.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        helper.assertTrue(body.serverLevel().addFreshEntity(zombie), "hostile fixture spawn failed");
        List<EntityEventTracker.Event> hostile = tracker.observe(
                observations.snapshot(body, "follow-event", follow, 4, Instant.now()));
        helper.assertTrue(types(hostile).contains(EntityEventTracker.Type.HOSTILE_ENTERED_THREAT_RANGE)
                        && hostile.stream().anyMatch(event -> event.priority() == EntityEventTracker.Priority.CRITICAL
                        && event.target().identity().equals(zombie.getUUID().toString())),
                "real hostile entry did not produce the critical UUID-bound edge");
        helper.assertTrue(tracker.observe(observations.snapshot(
                        body, "follow-event", follow, 5, Instant.now())).isEmpty(),
                "sustained real hostile/player state produced tick spam");

        zombie.discard();
        helper.assertTrue(registry.remove(owner).success(), "entity event fixture cleanup failed");
        helper.getLevel().getServer().getPlayerList().remove(owner);
        helper.succeed();
    }

    private static List<EntityEventTracker.Type> types(List<EntityEventTracker.Event> events) {
        return events.stream().map(EntityEventTracker.Event::type).toList();
    }

    private static void verifyLossReappearanceAndDistanceControl(
            GameTestHelper helper, CompanionRegistry registry, CompanionPlayer body,
            ServerPlayer owner, ServerPlayer target, String companionId, String lease) {
        EntityEventObservationService observations = new EntityEventObservationService(helper.getLevel().getServer());
        EntityEventTracker tracker = new EntityEventTracker();
        EntityEventTracker.TargetBinding binding = registry.entityEventTarget(companionId);
        tracker.observe(observations.snapshot(body, "follow-player", binding, 1, Instant.now()));
        target.teleportTo(target.serverLevel(), body.getX() + 22.0D, body.getY(), body.getZ(),
                target.getYRot(), target.getXRot());
        helper.assertTrue(types(tracker.observe(observations.snapshot(
                        body, "follow-player", binding, 2, Instant.now())))
                        .contains(EntityEventTracker.Type.FOLLOW_TARGET_LOST),
                "explicit follow target leaving range was not observed as lost");
        target.teleportTo(target.serverLevel(), body.getX() + 2.0D, body.getY(), body.getZ(),
                target.getYRot(), target.getXRot());
        helper.assertTrue(types(tracker.observe(observations.snapshot(
                        body, "follow-player", binding, 3, Instant.now())))
                        .contains(EntityEventTracker.Type.TARGET_REAPPEARED),
                "same UUID target reappearance was not observed");

        helper.assertTrue(start(registry, companionId, lease, "keep-distance", "KeepDistanceFromEntity",
                        target, 4.0D, 6.0D).success(),
                "new keep-distance instruction did not supersede follow");
        helper.assertValueEqual(snapshot(registry, companionId).behaviorId(), "keep-distance",
                "new instruction did not replace the old behavior id");
        await(helper, registry, companionId, 120,
                () -> body.distanceToSqr(target) >= 14.0D,
                "real body did not increase an unsafe target distance", () -> {
                    target.teleportTo(target.serverLevel(), body.getX() + 10.0D, body.getY(), body.getZ(),
                            target.getYRot(), target.getXRot());
                    helper.assertTrue(start(registry, companionId, lease, "chase-player", "ChaseEntity",
                                    target, null, null).success(),
                            "chase instruction did not supersede keep-distance");
                    await(helper, registry, companionId, 160,
                            () -> body.distanceToSqr(target) <= 4.0D,
                            "real body did not chase the moving target", () ->
                                    cancelThenVerifyDeath(helper, registry, body, owner, target, companionId, lease));
                });
    }

    private static void cancelThenVerifyDeath(
            GameTestHelper helper, CompanionRegistry registry, CompanionPlayer body,
            ServerPlayer owner, ServerPlayer target, String companionId, String lease) {
        helper.assertTrue(registry.runtimeCancel(companionId, lease, 1L).success(),
                "runtime cancel did not stop chase");
        Vec3 cancelledAt = body.position();
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(snapshot(registry, companionId).behaviorState().equals("IDLE")
                            && body.position().distanceToSqr(cancelledAt) < 0.5D,
                    "cancelled entity behavior kept applying movement input");
            var cow = EntityType.COW.create(body.serverLevel());
            helper.assertTrue(cow != null, "death target creation failed");
            cow.moveTo(body.getX() + 3.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
            helper.assertTrue(body.serverLevel().addFreshEntity(cow), "death target spawn failed");
            helper.assertTrue(registry.runtimeStart(companionId, lease, 1L, "face-cow", "skill",
                            null, null, null, new SkillParameters("FaceEntity", cow.getUUID().toString(),
                            "UUID", "", null, null, null, 60)).success(),
                    "face behavior did not start");
            helper.runAfterDelay(2, () -> {
                cow.setHealth(0.0F);
                await(helper, registry, companionId, 30,
                        () -> snapshot(registry, companionId).behaviorState().equals("IDLE"),
                        "dead explicit target did not terminate the behavior", () -> {
                            helper.assertValueEqual(snapshot(registry, companionId).behaviorObservation().failureCode(),
                                    "TARGET_DEAD", "target death terminal code mismatch");
                            cow.discard();
                            helper.assertTrue(registry.remove(owner).success(), "entity behavior cleanup failed");
                            helper.getLevel().getServer().getPlayerList().remove(owner);
                            helper.getLevel().getServer().getPlayerList().remove(target);
                            helper.succeed();
                        });
            });
        });
    }

    private static CompanionRegistry.RuntimeResult start(
            CompanionRegistry registry, String companionId, String lease, String behaviorId,
            String capability, ServerPlayer target, Double minimum, Double maximum) {
        return registry.runtimeStart(companionId, lease, 1L, behaviorId, "skill", null, null, null,
                new SkillParameters(capability, target.getUUID().toString(), "UUID", "", null,
                        minimum, maximum, 80));
    }

    private static CompanionRegistry.RuntimeSnapshot snapshot(CompanionRegistry registry, String companionId) {
        return registry.runtimeSnapshots(false).stream()
                .filter(value -> value.companionId().equals(companionId)).findFirst().orElseThrow();
    }

    private static void await(GameTestHelper helper, CompanionRegistry registry, String companionId,
                              int ticksRemaining, BooleanSupplier condition, String failure, Runnable completed) {
        CompanionRegistry.RuntimeSnapshot snapshot = snapshot(registry, companionId);
        helper.assertTrue(!snapshot.behaviorState().equals("PAUSED"),
                failure + ": behavior paused with " + snapshot.behaviorObservation());
        if (condition.getAsBoolean()) { completed.run(); return; }
        helper.assertTrue(ticksRemaining > 0, failure + ": body=" + snapshot.x() + ',' + snapshot.y() + ',' + snapshot.z());
        helper.runAfterDelay(1, () -> await(helper, registry, companionId,
                ticksRemaining - 1, condition, failure, completed));
    }

    private static void prepareArena(CompanionPlayer body, ServerPlayer owner, ServerPlayer target, Vec3 origin) {
        owner.teleportTo(owner.serverLevel(), origin.x, origin.y, origin.z + 8.0D,
                owner.getYRot(), owner.getXRot());
        body.teleportTo(body.serverLevel(), origin.x, origin.y, origin.z, body.getYRot(), body.getXRot());
        target.teleportTo(target.serverLevel(), origin.x + 10.0D, origin.y, origin.z,
                target.getYRot(), target.getXRot());
        body.setDeltaMovement(Vec3.ZERO);
        int chunkX = net.minecraft.core.BlockPos.containing(origin).getX() >> 4;
        int chunkZ = net.minecraft.core.BlockPos.containing(origin).getZ() >> 4;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            body.serverLevel().setChunkForced(chunkX + x, chunkZ + z, true);
        }
        for (int x = -16; x <= 24; x++) {
            for (int z = -8; z <= 8; z++) {
                net.minecraft.core.BlockPos floor = net.minecraft.core.BlockPos.containing(origin).offset(x, -1, z);
                body.serverLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 3; y++) {
                    body.serverLevel().setBlockAndUpdate(floor.above(y), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}
