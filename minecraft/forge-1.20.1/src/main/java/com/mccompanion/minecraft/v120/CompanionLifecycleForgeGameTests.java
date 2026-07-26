package com.mccompanion.minecraft.v120;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.minecraft.forge.PrimitiveObservationService;
import com.mccompanion.minecraft.forge.RegistryObservationService;
import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge 1.20.1 headless lifecycle coverage using a small vanilla empty structure. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompanionLifecycleForgeGameTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftAiCompanionForge.MOD_ID);

    private CompanionLifecycleForgeGameTests() {
    }

    @GameTest(
            templateNamespace = "minecraft",
            template = "bastion/mobs/empty",
            timeoutTicks = 300000)
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
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(snapshot -> snapshot.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId)
                .findFirst()
                .orElseThrow();
        var registrySearch = RegistryObservationService.registry(
                helper.getLevel().getServer(),
                JSON.createObjectNode()
                        .put("tool", "registry.search")
                        .put("kind", "ITEM")
                        .put("query", "diamond")
                        .put("namespace", "minecraft")
                        .put("limit", 16));
        helper.assertTrue(registrySearch.success(), "live Registry search failed: " + registrySearch.code());
        helper.assertTrue(registrySearch.observation().path("entries").isArray()
                        && !registrySearch.observation().path("entries").isEmpty(),
                "live Registry search returned no diamond entries");
        var recipeQuery = RegistryObservationService.recipes(
                helper.getLevel().getServer(),
                JSON.createObjectNode()
                        .put("type", "ANY")
                        .put("query", "")
                        .put("output", "")
                        .put("limit", 8));
        helper.assertTrue(recipeQuery.success(), "live recipe query failed: " + recipeQuery.code());
        helper.assertTrue(recipeQuery.observation().path("recipes").isArray(),
                "live recipe query did not return a recipe array");
        var itemObservation = PrimitiveObservationService.inspect(
                registry,
                companionId,
                JSON.createObjectNode()
                        .put("tool", "item.inspect")
                        .put("item", "minecraft:diamond"));
        helper.assertTrue(itemObservation.success(), "live item observation failed: " + itemObservation.code());
        helper.assertTrue(itemObservation.observation().path("verified").asBoolean(false),
                "live item observation was not marked verified");
        var inspectedBlock = body.blockPosition().offset(0, 1, 2);
        helper.getLevel().setBlockAndUpdate(inspectedBlock, Blocks.GOLD_BLOCK.defaultBlockState());
        var position = JSON.createObjectNode()
                .put("dimension", body.serverLevel().dimension().location().toString())
                .put("x", inspectedBlock.getX())
                .put("y", inspectedBlock.getY())
                .put("z", inspectedBlock.getZ());
        var blockArguments = JSON.createObjectNode().put("tool", "block.inspect");
        blockArguments.set("position", position);
        var blockObservation =
                PrimitiveObservationService.inspect(registry, companionId, blockArguments);
        helper.assertTrue(blockObservation.success(),
                "live block observation failed: " + blockObservation.code());
        helper.assertTrue(blockObservation.observation().path("block").asText()
                        .equals("minecraft:gold_block"),
                "live block observation returned the wrong block");
        var entityObservation = PrimitiveObservationService.inspect(
                registry,
                companionId,
                JSON.createObjectNode()
                        .put("tool", "entity.inspect")
                        .put("radius", 16.0D)
                        .put("limit", 16));
        helper.assertTrue(entityObservation.success(),
                "live entity observation failed: " + entityObservation.code());
        long expiresAt = System.currentTimeMillis() + 60_000L;
        CompanionRegistry.RuntimeResult acquired =
                registry.runtimeAcquireLease(companionId, "forge-gametest-lease", 1L, expiresAt);
        helper.assertTrue(acquired.success(), "runtime lease acquisition failed: " + acquired.code());
        CompanionRegistry.RuntimeResult runtimeStarted = registry.runtimeStart(
                companionId, "forge-gametest-lease", 1L, "forge-behavior-1", "travel",
                body.getX() + 4.0D, body.getY(), body.getZ());
        helper.assertTrue(runtimeStarted.success(), "runtime start failed: " + runtimeStarted.code());
        helper.assertTrue(!registry.runtimeAcquireLease(
                        companionId, "stale-lease", 1L, System.currentTimeMillis() + 60_000L).success(),
                "stale epoch replaced an active Runtime lease");
        helper.assertTrue(registry.runtimePause(companionId, "forge-gametest-lease", 1L).success(),
                "runtime pause failed");
        helper.assertTrue(registry.runtimeResume(companionId, "forge-gametest-lease", 1L).success(),
                "runtime resume failed");
        helper.assertTrue(registry.runtimeCancel(companionId, "forge-gametest-lease", 1L).success(),
                "runtime cancel failed");
        helper.assertTrue(registry.runtimeReleaseLease(companionId, "forge-gametest-lease", 1L).success(),
                "runtime lease release failed");
        if (Boolean.getBoolean("mccompanion.runtime.e2e")) {
            LOGGER.info("forge_runtime_e2e_ready companion={}", companionId);
            helper.succeedWhen(() -> {
                helper.assertTrue(registry.runtimeCommandCount() >= 5,
                        "waiting for Runtime lease/follow/pause/resume/cancel commands");
                helper.assertTrue(registry.remove(owner).success(), "Runtime E2E cleanup failed");
                helper.getLevel().getServer().getPlayerList().remove(owner);
                ownerConnection.disconnect(Component.literal("Forge Runtime E2E complete"));
            });
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
