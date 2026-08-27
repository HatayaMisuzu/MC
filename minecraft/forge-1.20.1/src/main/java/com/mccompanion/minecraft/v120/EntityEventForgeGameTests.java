package com.mccompanion.minecraft.v120;

import com.mccompanion.minecraft.bridge.EntityEventTracker;
import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Representative real-Minecraft proof for current-target death and disappearance. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EntityEventForgeGameTests {
    private EntityEventForgeGameTests() { }

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
}
