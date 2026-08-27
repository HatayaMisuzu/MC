package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.bridge.SurvivalEventTracker;
import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import java.time.Instant;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Real Minecraft 1.21.1 evidence for survival edges, no-spam state, death and recovery. */
public final class SurvivalEventGameTests implements FabricGameTest {
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "survivalEvents")
    public void damageFireDeathAndRespawnUseRealBodyStateWithoutSpam(GameTestHelper helper) {
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "SurvivalBody").success(), "survival companion create failed");
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElseThrow();
        CompanionPlayer body = registry.runtimeBody(companionId);
        helper.assertTrue(body != null, "survival body missing");

        SurvivalEventObservationService observations = new SurvivalEventObservationService();
        SurvivalEventTracker tracker = new SurvivalEventTracker();
        tracker.observe(observations.snapshot(binding(registry, companionId), 1, Instant.now()));
        body.invulnerableTime = 0;
        helper.assertTrue(body.hurt(body.damageSources().genericKill(), 15.0F),
                "real nonlethal bypass damage was rejected");
        List<SurvivalEventTracker.Type> damaged = types(tracker.observe(
                observations.snapshot(binding(registry, companionId), 2, Instant.now())));
        helper.assertTrue(damaged.equals(List.of(
                        SurvivalEventTracker.Type.DAMAGE, SurvivalEventTracker.Type.LOW_HEALTH)),
                "real damage/low-health edges were not distinct: " + damaged);

        body.setRemainingFireTicks(100);
        List<SurvivalEventTracker.Type> fire = types(tracker.observe(
                observations.snapshot(binding(registry, companionId), 3, Instant.now())));
        helper.assertTrue(fire.equals(List.of(SurvivalEventTracker.Type.FIRE)),
                "real fire state did not produce one critical entry edge: " + fire);
        helper.assertTrue(tracker.observe(observations.snapshot(
                binding(registry, companionId), 4, Instant.now())).isEmpty(),
                "sustained real fire/low-health state produced tick spam");

        body.clearFire();
        body.setHealth(body.getMaxHealth());
        Vec3 deathOrigin = body.position();
        helper.assertTrue(body.hurt(body.damageSources().genericKill(), Float.MAX_VALUE),
                "real lethal damage was rejected");
        helper.runAfterDelay(4, () -> {
            List<SurvivalEventTracker.Type> death = types(tracker.observe(
                    observations.snapshot(binding(registry, companionId), 5, Instant.now())));
            helper.assertTrue(death.equals(List.of(SurvivalEventTracker.Type.DEATH)),
                    "death emitted missing or derived duplicate critical edges: " + death);
            helper.assertTrue(registry.spawn(owner).success(), "death recovery spawn failed");
            CompanionPlayer recovered = registry.runtimeBody(companionId);
            helper.assertTrue(recovered != null && recovered.getUUID().equals(body.getUUID()),
                    "death recovery changed companion identity");
            List<SurvivalEventTracker.Type> respawn = types(tracker.observe(
                    observations.snapshot(binding(registry, companionId), 6, Instant.now())));
            helper.assertTrue(respawn.equals(List.of(SurvivalEventTracker.Type.RESPAWN)),
                    "dead-to-active transition did not produce exactly one respawn: " + respawn);

            recovered.serverLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(deathOrigin, deathOrigin).inflate(4.0D)).forEach(ItemEntity::discard);
            helper.assertTrue(registry.remove(owner).success(), "survival fixture cleanup failed");
            helper.getLevel().getServer().getPlayerList().remove(owner);
            helper.succeed();
        });
    }

    private static CompanionRegistry.SurvivalEventBinding binding(
            CompanionRegistry registry, String companionId) {
        return registry.survivalEventBindings().stream()
                .filter(value -> value.companionId().equals(companionId)).findFirst().orElseThrow();
    }

    private static List<SurvivalEventTracker.Type> types(List<SurvivalEventTracker.Event> events) {
        return events.stream().map(SurvivalEventTracker.Event::type).toList();
    }
}
