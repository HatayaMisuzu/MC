package com.mccompanion.minecraft.v120;

import com.mccompanion.minecraft.bridge.EntityEventTracker;
import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Representative real-Minecraft proof for current-target death and disappearance. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EntityEventForgeGameTests {
    private EntityEventForgeGameTests() { }

    @GameTest(batch = "entityInteraction", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 700)
    public static void arbitraryPlayerBehaviorsTrackLocallyAndEndOnCancelOrDeath(GameTestHelper helper) {
        FakeConnection ownerConnection = new FakeConnection();
        FakeConnection targetConnection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-target-owner"));
        ServerPlayer target = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-follow-target"));
        net.minecraft.core.BlockPos base = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        Vec3 origin = new Vec3(base.getX() + 2.5D, 100.0D, base.getZ() + 2.5D);
        owner.moveTo(origin.x, origin.y, origin.z + 8.0D, 0.0F, 0.0F);
        target.moveTo(origin.x + 10.0D, origin.y, origin.z, 0.0F, 0.0F);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(ownerConnection, owner);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(targetConnection, target);
        CompanionRegistry registry = MinecraftAiCompanionForge.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry.create(owner, "TargetBody").success(), "entity behavior companion create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "entity behavior body missing");
        prepareArena(body, owner, target, origin);
        target.setGameMode(GameType.SPECTATOR);
        String companionId = body.getUUID().toString();
        String lease = "forge-entity-interaction";
        helper.assertTrue(registry.runtimeAcquireLease(companionId, lease, 1L,
                        System.currentTimeMillis() + 120_000L).success(),
                "entity behavior lease acquisition failed");
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
                                    verifyLossReappearanceAndDistanceControl(helper, registry, body, owner, target,
                                            companionId, lease, ownerConnection, targetConnection));
                });
    }

    @GameTest(batch = "entityEvents", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 200)
    public static void currentTargetDeathAndDisappearanceKeepStableRealIdentity(GameTestHelper helper) {
        FakeConnection ownerConnection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-event-owner"));
        Vec3 spawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, 1.0D));
        owner.moveTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(ownerConnection, owner);
        CompanionRegistry registry = MinecraftAiCompanionForge.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry.create(owner, "EventBody").success(), "entity event companion create failed");
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElseThrow();
        CompanionPlayer body = registry.runtimeBody(companionId);
        helper.assertTrue(body != null, "entity event body missing");

        EntityEventObservationService observations = new EntityEventObservationService(helper.getLevel().getServer());
        EntityEventTracker tracker = new EntityEventTracker();
        var zombie = EntityType.ZOMBIE.create(body.serverLevel());
        helper.assertTrue(zombie != null, "death target creation failed");
        zombie.setNoAi(true);
        zombie.moveTo(body.getX() + 3.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        helper.assertTrue(body.serverLevel().addFreshEntity(zombie), "death target spawn failed");
        EntityEventTracker.TargetBinding zombieTarget = new EntityEventTracker.TargetBinding(
                zombie.getUUID().toString(), EntityEventTracker.TargetKind.CURRENT);
        tracker.observe(observations.snapshot(body, "attack-event", zombieTarget, 1, Instant.now()));
        zombie.setHealth(0.0F);
        List<EntityEventTracker.Event> death = tracker.observe(
                observations.snapshot(body, "attack-event", zombieTarget, 2, Instant.now()));
        helper.assertTrue(death.stream().anyMatch(event ->
                        event.type() == EntityEventTracker.Type.CURRENT_TARGET_DIED
                                && event.priority() == EntityEventTracker.Priority.CRITICAL
                                && event.target().identity().equals(zombie.getUUID().toString())),
                "real target death did not produce a critical UUID-bound edge: " + types(death));

        var cow = EntityType.COW.create(body.serverLevel());
        helper.assertTrue(cow != null, "disappearance target creation failed");
        cow.moveTo(body.getX() + 3.0D, body.getY(), body.getZ() + 2.0D, 0.0F, 0.0F);
        helper.assertTrue(body.serverLevel().addFreshEntity(cow), "disappearance target spawn failed");
        EntityEventTracker.TargetBinding cowTarget = new EntityEventTracker.TargetBinding(
                cow.getUUID().toString(), EntityEventTracker.TargetKind.CURRENT);
        tracker.observe(observations.snapshot(body, "interact-event", cowTarget, 3, Instant.now()));
        cow.discard();
        List<EntityEventTracker.Event> disappeared = tracker.observe(
                observations.snapshot(body, "interact-event", cowTarget, 4, Instant.now()));
        helper.assertTrue(disappeared.stream().anyMatch(event ->
                        event.type() == EntityEventTracker.Type.CURRENT_TARGET_DISAPPEARED
                                && event.target().identity().equals(cow.getUUID().toString())),
                "real target removal did not produce a UUID-bound disappearance edge: " + types(disappeared));

        zombie.discard();
        helper.assertTrue(registry.remove(owner).success(), "entity event fixture cleanup failed");
        helper.getLevel().getServer().getPlayerList().remove(owner);
        ownerConnection.disconnect(Component.literal("Forge entity event GameTest complete"));
        helper.succeed();
    }

    private static List<EntityEventTracker.Type> types(List<EntityEventTracker.Event> events) {
        return events.stream().map(EntityEventTracker.Event::type).toList();
    }

    private static void verifyLossReappearanceAndDistanceControl(
            GameTestHelper helper, CompanionRegistry registry, CompanionPlayer body,
            ServerPlayer owner, ServerPlayer target, String companionId, String lease,
            FakeConnection ownerConnection, FakeConnection targetConnection) {
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
        helper.assertTrue("keep-distance".equals(snapshot(registry, companionId).behaviorId()),
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
                            "real body did not chase the moving target", () -> cancelThenVerifyDeath(
                                    helper, registry, body, owner, target, companionId, lease,
                                    ownerConnection, targetConnection));
                });
    }

    private static void cancelThenVerifyDeath(
            GameTestHelper helper, CompanionRegistry registry, CompanionPlayer body,
            ServerPlayer owner, ServerPlayer target, String companionId, String lease,
            FakeConnection ownerConnection, FakeConnection targetConnection) {
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
                            helper.assertTrue(snapshot(registry, companionId).behaviorObservation() != null
                                            && "TARGET_DEAD".equals(snapshot(registry, companionId)
                                            .behaviorObservation().failureCode()),
                                    "target death terminal code mismatch");
                            cow.discard();
                            helper.assertTrue(registry.remove(owner).success(), "entity behavior cleanup failed");
                            helper.getLevel().getServer().getPlayerList().remove(owner);
                            helper.getLevel().getServer().getPlayerList().remove(target);
                            ownerConnection.disconnect(Component.literal("Forge entity behavior GameTest complete"));
                            targetConnection.disconnect(Component.literal("Forge entity behavior GameTest complete"));
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
        helper.assertTrue(ticksRemaining > 0,
                failure + ": body=" + snapshot.x() + ',' + snapshot.y() + ',' + snapshot.z());
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
