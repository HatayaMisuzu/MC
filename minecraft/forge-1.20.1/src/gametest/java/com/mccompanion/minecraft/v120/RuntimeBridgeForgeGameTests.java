package com.mccompanion.minecraft.v120;

import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** REAL_MINECRAFT_GAMETEST for the authenticated Runtime/Forge control boundary. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuntimeBridgeForgeGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftAiCompanionForge.MOD_ID);

    private RuntimeBridgeForgeGameTests() {
    }

    @GameTest(batch = "runtimeBridge", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1200)
    public static void authenticatedRuntimeControlsOneLiveBody(GameTestHelper helper) {
        FakeConnection ownerConnection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-runtime-owner"));
        Vec3 spawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, 1.0D));
        owner.moveTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(ownerConnection, owner);
        CompanionRegistry registry = MinecraftAiCompanionForge.integrationRegistryFor(
                helper.getLevel().getServer());
        helper.assertTrue(registry != null, "Runtime bridge registry was not initialized");
        helper.assertTrue(registry.create(owner, "ForgeRuntime").success(),
                "Runtime bridge companion creation failed");
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(snapshot -> snapshot.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId)
                .findFirst().orElseThrow();
        long commandBaseline = registry.runtimeCommandCount();
        LOGGER.info("forge_runtime_e2e_ready companion={}", companionId);
        helper.succeedWhen(() -> {
            CompanionRegistry.RuntimeSnapshot snapshot = registry.runtimeSnapshots(true).stream()
                    .filter(value -> value.companionId().equals(companionId))
                    .findFirst().orElseThrow();
            helper.assertTrue(registry.runtimeCommandCount() >= commandBaseline + 6
                            && snapshot.behaviorId() == null
                            && snapshot.behaviorState().equals("IDLE"),
                    "waiting for Runtime start/pause/resume/cancel lifecycle");
            CompanionCommands.TextRequestResult request =
                    MinecraftAiCompanionForge.integrationSubmitPlayerText(owner, "report current status");
            helper.assertTrue(request.accepted(),
                    "authenticated player request was not accepted: " + request.message());
            MinecraftAiCompanionForge.integrationSubmitOwnerBlockActivity(
                    owner, owner.blockPosition(), "BLOCK_USE");
            LOGGER.info("forge_runtime_e2e_player_and_owner_activity_sent companion={}", companionId);
            helper.assertTrue(registry.remove(owner).success(), "Runtime E2E cleanup failed");
            helper.getLevel().getServer().getPlayerList().remove(owner);
            ownerConnection.disconnect(Component.literal("Forge Runtime E2E complete"));
        });
    }
}
