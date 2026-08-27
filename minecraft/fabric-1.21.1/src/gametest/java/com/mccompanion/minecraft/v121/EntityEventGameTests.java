package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.bridge.EntityEventTracker;
import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import java.time.Instant;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

/** Representative real-Minecraft proof for player edges and bounded hostile observation. */
public final class EntityEventGameTests implements FabricGameTest {
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
}
