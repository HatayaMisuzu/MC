package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import com.mccompanion.minecraft.fabric.PrimitiveObservationService;
import com.mccompanion.minecraft.fabric.RegistryObservationService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Headless integration tests that exercise the real ServerPlayer body and fake connection. */
public final class CompanionLifecycleGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft_ai_companion_gametest");

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "registry")
    public void liveRegistryAndRecipeQueriesDiscoverUnknownNamespaceContent(GameTestHelper helper) {
        var search = RegistryObservationService.registry(helper.getLevel().getServer(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("tool", "registry.search").put("kind", "ITEM")
                        .put("namespace", "mcac_registry_fixture").put("query", "blue").put("limit", 8));
        helper.assertTrue(search.success(), "unknown namespace Registry search failed: " + search.code());
        helper.assertValueEqual(search.observation().path("totalMatches").asInt(), 2,
                "Registry search did not discover both fixture items");
        helper.assertTrue(java.util.stream.StreamSupport.stream(
                        search.observation().path("entries").spliterator(), false)
                        .anyMatch(value -> value.path("id").asText().equals(
                                RegistryFixtureInitializer.BLUE_ITEM_ID.toString())),
                "Registry search did not return the unknown namespaced item");

        var described = RegistryObservationService.registry(helper.getLevel().getServer(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("tool", "registry.describe").put("kind", "BLOCK")
                        .put("id", RegistryFixtureInitializer.BLUE_BLOCK_ID.toString()));
        helper.assertTrue(described.success(), "unknown namespace Registry describe failed: " + described.code());
        helper.assertValueEqual(described.observation().path("entry").path("id").asText(),
                RegistryFixtureInitializer.BLUE_BLOCK_ID.toString(),
                "Registry describe returned the wrong block");

        var recipes = RegistryObservationService.recipes(helper.getLevel().getServer(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("type", "CRAFTING").put("query", "oak_planks")
                        .put("output", "minecraft:oak_planks").put("limit", 8));
        helper.assertTrue(recipes.success(), "live recipe query failed: " + recipes.code());
        helper.assertTrue(recipes.observation().path("totalMatches").asInt() >= 1,
                "live recipe manager did not expose oak planks");
        helper.assertValueEqual(recipes.observation().path("source").asText(),
                "LIVE_SERVER_RECIPE_MANAGER", "recipe query source was not the live server");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "observation")
    public void livePrimitiveInspectionsReadVisibleWorldInventoryAndEntities(GameTestHelper helper) {
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Observer").success(), "observation test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "observation test created no live body");
        BlockPos blockPosition = body.blockPosition().offset(2, 0, 0);
        body.serverLevel().setBlockAndUpdate(blockPosition, RegistryFixtureInitializer.BLUE_BLOCK.defaultBlockState());
        body.getInventory().add(new ItemStack(RegistryFixtureInitializer.BLUE_ITEM, 3));
        var zombie = EntityType.ZOMBIE.create(body.serverLevel());
        helper.assertTrue(zombie != null, "observation test could not create entity");
        zombie.setNoAi(true);
        zombie.moveTo(body.getX() + 3.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        helper.assertTrue(body.serverLevel().addFreshEntity(zombie),
                "observation test could not add entity");

        var block = PrimitiveObservationService.inspect(registry, body.getUUID().toString(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("tool", "block.inspect")
                        .set("position", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                .put("dimension", body.serverLevel().dimension().location().toString())
                                .put("x", blockPosition.getX()).put("y", blockPosition.getY())
                                .put("z", blockPosition.getZ())));
        helper.assertTrue(block.success(), "live block inspection failed: " + block.code());
        helper.assertValueEqual(block.observation().path("block").asText(),
                RegistryFixtureInitializer.BLUE_BLOCK_ID.toString(),
                "live block inspection did not return unknown namespace state");

        var item = PrimitiveObservationService.inspect(registry, body.getUUID().toString(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("tool", "item.inspect")
                        .put("item", RegistryFixtureInitializer.BLUE_ITEM_ID.toString()));
        helper.assertTrue(item.success(), "live item inspection failed: " + item.code());
        helper.assertValueEqual(item.observation().path("totalCount").asInt(), 3,
                "live item inspection did not read inventory state");

        var entity = PrimitiveObservationService.inspect(registry, body.getUUID().toString(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("tool", "entity.inspect").put("radius", 8).put("limit", 4)
                        .put("type", "minecraft:zombie"));
        helper.assertTrue(entity.success(), "live entity inspection failed: " + entity.code());
        helper.assertValueEqual(entity.observation().path("totalMatches").asInt(), 1,
                "live entity inspection did not return the visible zombie");
        helper.assertValueEqual(entity.observation().path("entities").path(0).path("entityId").asText(),
                zombie.getUUID().toString(), "live entity inspection returned the wrong entity");

        zombie.discard();
        helper.assertTrue(registry.remove(owner).success(), "observation test cleanup failed");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "look")
    public void movementLookUsesBoundedVanillaBodyRotationAndVerifiesDirection(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Looker").success(), "look test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "look test created no live body");
        BlockPos target = body.blockPosition().offset(5, 2, -4);
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-look";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "look lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "look-position", "skill",
                null, null, null, new SkillParameters("LookAt", "", 1, false,
                        body.serverLevel().dimension().location().toString(),
                        target.getX(), target.getY(), target.getZ())).success(), "look skill failed to start");
        awaitBehaviorIdle(helper, registry, companionId, 20, snapshot -> {
            Vec3 expected = Vec3.atCenterOf(target).subtract(body.getEyePosition()).normalize();
            helper.assertTrue(expected.dot(body.getViewVector(1.0F)) >= 0.999D,
                    "body view direction did not match the bounded target");
            helper.assertTrue(snapshot.behaviorObservation() != null,
                    "look action produced no verified observation");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "LOOK_COMPLETE",
                    "look observation code mismatch");
            helper.assertTrue(snapshot.evidenceSummary().contains("VANILLA_ENTITY_LOOK"),
                    "look evidence did not identify the vanilla body-rotation path");
            helper.assertTrue(registry.remove(owner).success(), "look test cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "block_interaction")
    public void blockInteractUsesVisibleReachableVanillaPlayerAction(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "BlockInteractor").success(),
                "block interaction test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "block interaction test created no live body");
        BlockPos target = body.blockPosition().offset(2, 0, 0);
        body.serverLevel().setBlockAndUpdate(target, Blocks.CHEST.defaultBlockState());
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-block-interact";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "block interaction lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "open-visible-chest", "skill",
                null, null, null, new SkillParameters("InteractBlock", "", 1, false,
                        body.serverLevel().dimension().location().toString(),
                        target.getX(), target.getY(), target.getZ(), "", "UP", "MAIN_HAND")).success(),
                "block interaction failed to start");
        awaitBehaviorIdle(helper, registry, companionId, 20, snapshot -> {
            helper.assertTrue(body.containerMenu != body.inventoryMenu,
                    "vanilla block interaction did not open the chest menu");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "INTERACTION_COMPLETE",
                    "block interaction observation code mismatch");
            helper.assertTrue(snapshot.evidenceSummary().contains("VANILLA_SERVER_PLAYER_GAME_MODE"),
                    "block interaction evidence did not identify ServerPlayerGameMode");
            body.closeContainer();
            helper.assertTrue(registry.remove(owner).success(), "block interaction cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "entity_interaction")
    public void entityInteractUsesVisibleReachableVanillaPlayerAction(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "EntityInteractor").success(),
                "entity interaction test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "entity interaction test created no live body");
        var cow = EntityType.COW.create(body.serverLevel());
        helper.assertTrue(cow != null, "entity interaction test could not create cow");
        cow.setNoAi(true);
        cow.moveTo(body.getX() + 3.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        helper.assertTrue(body.serverLevel().addFreshEntity(cow),
                "entity interaction test could not add cow");
        body.getInventory().setItem(0, new ItemStack(Items.WHEAT, 2));
        body.getInventory().selected = 0;
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-entity-interact";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "entity interaction lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "feed-visible-cow", "skill",
                null, null, null, new SkillParameters("InteractEntity", "", 1, false,
                        body.serverLevel().dimension().location().toString(),
                        null, null, null, cow.getUUID().toString(), "UP", "MAIN_HAND")).success(),
                "entity interaction failed to start");
        awaitBehaviorIdle(helper, registry, companionId, 20, snapshot -> {
            helper.assertTrue(cow.isInLove(), "vanilla entity interaction did not feed the cow");
            helper.assertValueEqual(body.getMainHandItem().getCount(), 1,
                    "vanilla entity interaction did not consume one wheat");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "INTERACTION_COMPLETE",
                    "entity interaction observation code mismatch");
            helper.assertTrue(snapshot.evidenceSummary().contains("VANILLA_SERVER_PLAYER_INTERACTION"),
                    "entity interaction evidence did not identify the vanilla player action");
            cow.discard();
            helper.assertTrue(registry.remove(owner).success(), "entity interaction cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    public void exploreAreaScansIncrementallyAndReturnsRankedVerifiedCandidates(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Explorer").success(), "explore test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "explore test created no live body");
        BlockPos origin = body.blockPosition();
        BlockPos near = origin.offset(2, 0, 0);
        BlockPos far = origin.offset(4, 1, 0);
        // Use a block unique to this concurrent GameTest batch so another capability's fixture
        // cannot become a legitimate extra scan candidate when structures are packed nearby.
        body.serverLevel().setBlockAndUpdate(near, Blocks.EMERALD_ORE.defaultBlockState());
        body.serverLevel().setBlockAndUpdate(far, Blocks.EMERALD_ORE.defaultBlockState());
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-explore";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "explore lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "scan-emerald", "skill",
                null, null, null, new SkillParameters("ExploreArea", "minecraft:emerald_ore", 6, false,
                        body.serverLevel().dimension().location().toString(),
                        origin.getX(), origin.getY(), origin.getZ())).success(), "explore skill failed to start");
        helper.assertTrue(registry.runtimeSnapshots(false).stream()
                        .filter(snapshot -> snapshot.companionId().equals(companionId))
                        .findFirst().orElseThrow().behaviorState().equals("RUNNING"),
                "bounded scan completed synchronously instead of incrementally across ticks");
        helper.succeedWhen(() -> {
            var snapshot = registry.runtimeSnapshots(false).stream()
                    .filter(value -> value.companionId().equals(companionId)).findFirst().orElseThrow();
            helper.assertValueEqual(snapshot.behaviorState(), "IDLE", "waiting for bounded scan completion");
            var observation = snapshot.behaviorObservation();
            helper.assertTrue(observation != null, "scan produced no verified observation");
            helper.assertValueEqual(observation.failureCode(), "SCAN_COMPLETE", "scan code mismatch");
            helper.assertValueEqual(observation.available(), 2, "scan did not deduplicate candidates");
            helper.assertValueEqual(observation.candidates().get(0).x(), near.getX(), "nearest candidate was not ranked first");
            helper.assertValueEqual(observation.candidates().get(1).x(), far.getX(), "far candidate was not ranked second");
            helper.assertTrue(observation.candidates().get(0).distanceSquared()
                            < observation.candidates().get(1).distanceSquared(),
                    "candidate distances are not increasing");
            helper.assertTrue(registry.remove(owner).success(), "explore test cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 300, batch = "collection")
    public void collectResourceUsesMovementAndVanillaItemPickup(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Collector").success(), "collect test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "collect test created no live body");
        BlockPos origin = body.blockPosition();
        // Move away from the preceding combat batch. A failed earlier GameTest can leave a player
        // body alive, and vanilla ItemEntity pickup must still be isolated to this collector.
        for (int x = -7; x <= 1; x++) {
            for (int z = -2; z <= 2; z++) {
                body.serverLevel().setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        ItemEntity first = new ItemEntity(body.serverLevel(), body.getX() - 4.0D, body.getY() + 0.25D, body.getZ(),
                new ItemStack(Items.COAL, 2));
        first.setNoPickUpDelay();
        helper.assertTrue(body.serverLevel().addFreshEntity(first),
                "collect test could not create world drops");
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-collect";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "collect lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "collect-coal", "skill",
                null, null, null, new SkillParameters("CollectResource", "minecraft:coal", 2, false)).success(),
                "collect skill failed to start");
        awaitBehaviorIdle(helper, registry, companionId, 240, snapshot -> {
            helper.assertValueEqual(count(body, Items.COAL), 2, "collection did not verify the inventory delta");
            helper.assertTrue(!first.isAlive(), "collected ItemEntity remained in the world");
            helper.assertTrue(snapshot.behaviorObservation() != null, "collection produced no observation");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "COLLECT_COMPLETE",
                    "collection observation code mismatch");
            helper.assertValueEqual(snapshot.behaviorObservation().available(), 2,
                    "collection observation quantity mismatch");
            helper.assertTrue(registry.remove(owner).success(), "collect test cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "safety")
    public void safetyReflexInterruptsTaskAndRetreatsFromNearbyHostile(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Retreater").success(), "retreat test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "retreat test created no live body");
        BlockPos origin = body.blockPosition();
        for (int x = -9; x <= 9; x++) {
            for (int z = -4; z <= 4; z++) {
                body.serverLevel().setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        var zombie = EntityType.ZOMBIE.create(body.serverLevel());
        helper.assertTrue(zombie != null, "retreat test could not create threat");
        zombie.setNoAi(true);
        zombie.moveTo(body.getX() + 2.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        helper.assertTrue(body.serverLevel().addFreshEntity(zombie), "retreat test could not spawn threat");
        Vec3 start = body.position();
        double initialThreatDistance = zombie.distanceToSqr(body);
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-retreat";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "retreat lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "unsafe-travel", "goto",
                body.getX() + 8.0D, body.getY(), body.getZ(), null).success(),
                "retreat test travel failed to start");
        helper.succeedWhen(() -> {
            var snapshot = registry.runtimeSnapshots(false).stream()
                    .filter(value -> value.companionId().equals(companionId)).findFirst().orElseThrow();
            helper.assertValueEqual(snapshot.behaviorState(), "PAUSED",
                    "active task was not interrupted after safety retreat");
            // The reflex measures its three-block displacement from the exact tick on which it
            // preempts travel. Depending on server scheduling, travel may first move toward the
            // threat, so the net delta from this earlier setup position can be smaller.
            helper.assertTrue(body.position().distanceToSqr(start) >= 0.25D,
                    "retreat did not create a verified movement delta");
            helper.assertTrue(zombie.distanceToSqr(body) >= 36.0D
                            && zombie.distanceToSqr(body) > initialThreatDistance,
                    "retreat did not reach the verified six-block hostile clearance");
            helper.assertTrue(snapshot.behaviorObservation() != null, "retreat produced no observation");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "SAFETY_RETREAT_COMPLETE",
                    "retreat observation code mismatch");
            helper.assertTrue(snapshot.evidenceSummary().contains("success=true"),
                    "retreat did not produce successful movement evidence");
            zombie.discard();
            helper.assertTrue(registry.remove(owner).success(), "retreat test cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 300, batch = "combat")
    public void defendOwnerUsesVanillaAttackCooldownAndDefeatsNearbyHostile(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Defender").success(), "defend test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "defend test created no live body");
        BlockPos origin = body.blockPosition();
        for (int x = -2; x <= 5; x++) {
            body.serverLevel().setBlockAndUpdate(origin.offset(x, -1, 0), Blocks.STONE.defaultBlockState());
        }
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        body.getInventory().setItem(0, sword);
        body.getInventory().selected = 0;
        var husk = EntityType.HUSK.create(body.serverLevel());
        helper.assertTrue(husk != null, "defend test could not create threat");
        husk.setNoAi(true);
        husk.setHealth(1.0F);
        husk.moveTo(body.getX() + 2.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        helper.assertTrue(body.serverLevel().addFreshEntity(husk), "defend test could not spawn threat");
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-defend";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "defend lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "defend-owner", "skill",
                null, null, null, new SkillParameters("DefendOwner", "", 1, false)).success(),
                "defend skill failed to start");
        helper.succeedWhen(() -> {
            var snapshot = registry.runtimeSnapshots(false).stream()
                    .filter(value -> value.companionId().equals(companionId)).findFirst().orElseThrow();
            helper.assertValueEqual(snapshot.behaviorState(), "IDLE", "waiting for owner defense completion");
            helper.assertTrue(!husk.isAlive(), "vanilla attack did not defeat the hostile");
            ItemStack usedSword = body.getInventory().getItem(0);
            helper.assertTrue(usedSword.is(Items.IRON_SWORD) && usedSword.getDamageValue() >= 1,
                    "vanilla attack did not consume weapon durability");
            helper.assertTrue(snapshot.behaviorObservation() != null, "defense produced no observation");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "DEFEND_COMPLETE",
                    "defense observation code mismatch");
            helper.assertValueEqual(snapshot.behaviorObservation().itemId(), "THREAT_DEFEATED",
                    "defense did not report the verified threat outcome");
            helper.assertTrue(snapshot.evidenceSummary().contains("success=true"),
                    "defense did not produce successful action evidence");
            helper.assertTrue(registry.remove(owner).success(), "defend test cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "mining")
    public void mineResourceVeinUsesHardnessToolDurabilityDropsAndPickup(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Miner").success(), "mine test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "mine test created no live body");
        BlockPos origin = body.blockPosition().offset(2, 0, 0);
        BlockPos second = origin.offset(1, 0, 0);
        for (int x = -1; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                body.serverLevel().setBlockAndUpdate(body.blockPosition().offset(x, -1, z),
                        Blocks.STONE.defaultBlockState());
            }
        }
        body.serverLevel().setBlockAndUpdate(origin, Blocks.DIAMOND_ORE.defaultBlockState());
        body.serverLevel().setBlockAndUpdate(second, Blocks.DIAMOND_ORE.defaultBlockState());
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        body.getInventory().setItem(0, pickaxe);
        body.getInventory().selected = 0;
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-mine";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 30_000L).success(),
                "mine lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "mine-diamond", "skill",
                null, null, null, new SkillParameters("MineResourceVein", "minecraft:diamond_ore", 2, false,
                        body.serverLevel().dimension().location().toString(),
                        origin.getX(), origin.getY(), origin.getZ())).success(), "mine skill failed to start");
        awaitBehaviorIdle(helper, registry, companionId, 320, snapshot -> {
            helper.assertTrue(!body.serverLevel().getBlockState(origin).is(Blocks.DIAMOND_ORE)
                    && !body.serverLevel().getBlockState(second).is(Blocks.DIAMOND_ORE),
                    "mined ore blocks remained in the world");
            helper.assertValueEqual(count(body, Items.DIAMOND), 2, "vanilla diamond drops were not collected");
            helper.assertTrue(pickaxe.getDamageValue() >= 2, "vanilla tool durability was not consumed");
            helper.assertTrue(snapshot.behaviorObservation() != null, "mining produced no observation");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "MINE_COMPLETE",
                    "mining observation code mismatch");
            helper.assertValueEqual(snapshot.behaviorObservation().available(), 2,
                    "mining observation block count mismatch");
            helper.assertTrue(snapshot.evidenceSummary().contains("VANILLA_SERVER_PLAYER_GAME_MODE"),
                    "mining evidence did not identify the vanilla ServerPlayerGameMode path");
            helper.assertTrue(registry.remove(owner).success(), "mine test cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700)
    public void smeltItemUsesRealFurnaceFuelTimeAndMenuResultPickup(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, "Smelter").success(), "smelt test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "smelt test created no live body");
        BlockPos furnacePos = body.blockPosition().offset(1, 0, 0);
        body.serverLevel().setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
        Container furnace = (Container) body.serverLevel().getBlockEntity(furnacePos);
        body.getInventory().add(new ItemStack(Items.RAW_IRON, 2));
        body.getInventory().add(new ItemStack(Items.COAL, 1));
        String companionId = body.getUUID().toString();
        String leaseId = "gametest-smelt";
        helper.assertTrue(registry.runtimeAcquireLease(
                companionId, leaseId, 1L, System.currentTimeMillis() + 60_000L).success(),
                "smelt lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, 1L, "smelt-iron", "skill",
                null, null, null, new SkillParameters("SmeltItem", "minecraft:iron_ingot", 2, false,
                        body.serverLevel().dimension().location().toString(),
                        furnacePos.getX(), furnacePos.getY(), furnacePos.getZ())).success(),
                "smelt skill failed to start");
        helper.succeedWhen(() -> {
            var snapshot = registry.runtimeSnapshots(false).stream()
                    .filter(value -> value.companionId().equals(companionId)).findFirst().orElseThrow();
            helper.assertValueEqual(snapshot.behaviorState(), "IDLE", "waiting for real furnace completion");
            helper.assertValueEqual(count(body, Items.IRON_INGOT), 2, "furnace results were not picked up");
            helper.assertValueEqual(count(body, Items.RAW_IRON), 0, "furnace did not consume both raw inputs");
            helper.assertTrue(furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty(),
                    "completed furnace retained input or result items");
            helper.assertTrue(snapshot.behaviorObservation() != null, "smelting produced no observation");
            helper.assertValueEqual(snapshot.behaviorObservation().failureCode(), "SMELT_COMPLETE",
                    "smelting observation code mismatch");
            helper.assertValueEqual(snapshot.behaviorObservation().available(), 2,
                    "smelting observation quantity mismatch");
            helper.assertTrue(snapshot.evidenceSummary().contains("success=true"),
                    "smelting did not produce successful action evidence");
            helper.assertTrue(registry.remove(owner).success(), "smelt test cleanup failed");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600)
    public void runtimeSkillsUseVanillaActionsAndVerifyWorldDeltas(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.setGameMode(GameType.SURVIVAL);
        helper.assertTrue(registry.create(owner, "SkillCompanion").success(), "skill test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "skill test created no live body");
        // Stand just outside the owner's collision box so the normal forward toss
        // lands inside the owner's vanilla pickup radius.
        body.moveTo(owner.getX(), owner.getY(), owner.getZ() - 1.25D, 0.0F, 0.0F);
        // Start outside the hotbar to prove DeliverItem uses a real InventoryMenu rearrangement.
        body.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 2));

        String companionId = body.getUUID().toString();
        String leaseId = "gametest-skills";
        long epoch = 1L;
        helper.assertTrue(registry.runtimeAcquireLease(
                        companionId, leaseId, epoch, System.currentTimeMillis() + 30_000L).success(),
                "skill test lease acquisition failed");
        int ownerDiamondsBefore = count(owner, Items.DIAMOND);
        helper.assertTrue(registry.runtimeStart(
                        companionId,
                        leaseId,
                        epoch,
                        "deliver-diamond",
                        "skill",
                        null,
                        null,
                        null,
                        new SkillParameters("DeliverItem", "minecraft:diamond", 2, false)).success(),
                "delivery skill failed to start");

        helper.runAfterDelay(120, () -> {
            helper.assertValueEqual(count(owner, Items.DIAMOND), ownerDiamondsBefore + 2,
                    "delivery did not produce the verified owner inventory delta");
            helper.assertValueEqual(count(body, Items.DIAMOND), 0,
                    "delivery left duplicate diamonds in companion inventory");
            helper.assertTrue(registry.runtimeSnapshots(false).stream()
                            .anyMatch(snapshot -> snapshot.companionId().equals(companionId)
                                    && snapshot.behaviorState().equals("IDLE")
                                    && snapshot.evidenceSummary().contains("success=true")),
                    "delivery did not report an idle state with successful world evidence");

            body.getFoodData().setFoodLevel(6);
            body.getInventory().setItem(0, new ItemStack(Items.BREAD));
            int foodBefore = body.getFoodData().getFoodLevel();
            helper.assertTrue(registry.runtimeStart(
                            companionId,
                            leaseId,
                            epoch,
                            "eat-bread",
                            "skill",
                            null,
                            null,
                            null,
                            new SkillParameters("EatAndRecover", "minecraft:bread", 1, false)).success(),
                    "eating skill failed to start");
            helper.runAfterDelay(80, () -> {
                helper.assertTrue(body.getFoodData().getFoodLevel() > foodBefore,
                        "eating did not change the companion's vanilla food state");
                helper.assertValueEqual(count(body, Items.BREAD), 0,
                        "eating did not consume the selected food stack");
                helper.assertTrue(registry.remove(owner).success(), "skill test cleanup failed");
                helper.succeed();
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "crafting")
    public void storageSkillsUseVanillaContainerMenuAndPreserveItemCounts(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.setGameMode(GameType.SURVIVAL);
        helper.assertTrue(registry.create(owner, "StorageCompanion").success(), "storage test create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "storage test created no live body");
        BlockPos chestPos = body.blockPosition().offset(1, 0, 0);
        body.serverLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        Container chest = (Container) body.serverLevel().getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));

        String companionId = body.getUUID().toString();
        String leaseId = "gametest-storage";
        long epoch = 1L;
        helper.assertTrue(registry.runtimeAcquireLease(
                        companionId, leaseId, epoch, System.currentTimeMillis() + 30_000L).success(),
                "storage test lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, epoch, "withdraw-iron", "skill",
                        null, null, null, new SkillParameters("WithdrawFromStorage", "minecraft:iron_ingot", 3,
                                false, body.serverLevel().dimension().location().toString(),
                                chestPos.getX(), chestPos.getY(), chestPos.getZ())).success(),
                "withdraw skill failed to start");

        helper.runAfterDelay(30, () -> {
            helper.assertValueEqual(count(body, Items.IRON_INGOT), 3,
                    "withdraw did not produce the verified companion inventory delta");
            helper.assertValueEqual(count(chest, Items.IRON_INGOT), 2,
                    "withdraw did not produce the verified container inventory delta");
            helper.assertTrue(registry.runtimeSnapshots(false).stream()
                            .anyMatch(snapshot -> snapshot.companionId().equals(companionId)
                                    && snapshot.behaviorState().equals("IDLE")
                                    && snapshot.evidenceSummary().contains("success=true")),
                    "withdraw did not report successful evidence");

            helper.assertTrue(registry.runtimeStart(companionId, leaseId, epoch, "deposit-iron", "skill",
                            null, null, null, new SkillParameters("DepositToStorage", "minecraft:iron_ingot", 2,
                                    false, body.serverLevel().dimension().location().toString(),
                                    chestPos.getX(), chestPos.getY(), chestPos.getZ())).success(),
                    "deposit skill failed to start");
            helper.runAfterDelay(20, () -> {
                helper.assertValueEqual(count(body, Items.IRON_INGOT), 1,
                        "deposit did not produce the verified companion inventory delta");
                helper.assertValueEqual(count(chest, Items.IRON_INGOT), 4,
                        "deposit did not produce the verified container inventory delta");

                helper.assertTrue(registry.runtimeStart(companionId, leaseId, epoch, "withdraw-too-many", "skill",
                                null, null, null, new SkillParameters("WithdrawFromStorage", "minecraft:iron_ingot", 5,
                                        false, body.serverLevel().dimension().location().toString(),
                                        chestPos.getX(), chestPos.getY(), chestPos.getZ())).success(),
                        "insufficient withdrawal was not accepted for observable execution");
                helper.runAfterDelay(8, () -> {
                    helper.assertValueEqual(count(body, Items.IRON_INGOT), 1,
                            "insufficient withdrawal changed companion inventory");
                    helper.assertValueEqual(count(chest, Items.IRON_INGOT), 4,
                            "insufficient withdrawal changed container inventory");
                    helper.assertTrue(registry.runtimeSnapshots(false).stream()
                                    .anyMatch(snapshot -> snapshot.companionId().equals(companionId)
                                            && snapshot.behaviorState().equals("PAUSED")
                                            && snapshot.evidenceSummary().contains("failure=ITEM_INSUFFICIENT")
                                            && snapshot.behaviorObservation() != null
                                            && snapshot.behaviorObservation().failureCode().equals("ITEM_INSUFFICIENT")
                                            && snapshot.behaviorObservation().requested() == 5
                                            && snapshot.behaviorObservation().available() == 4),
                            "insufficient withdrawal did not expose structured shortage evidence");
                    helper.assertTrue(registry.remove(owner).success(), "storage test cleanup failed");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    public void depositStopsSafelyWhenContainerIsFull(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.setGameMode(GameType.SURVIVAL);
        helper.assertTrue(registry.create(owner, "FullStorageCompanion").success(), "full storage create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "full storage test created no live body");
        body.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT));
        BlockPos chestPos = body.blockPosition().offset(1, 0, 0);
        body.serverLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        Container chest = (Container) body.serverLevel().getBlockEntity(chestPos);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
        }

        String companionId = body.getUUID().toString();
        String leaseId = "gametest-full-storage";
        long epoch = 1L;
        helper.assertTrue(registry.runtimeAcquireLease(
                        companionId, leaseId, epoch, System.currentTimeMillis() + 30_000L).success(),
                "full storage lease acquisition failed");
        int bodyBefore = count(body, Items.IRON_INGOT);
        int chestBefore = count(chest, Items.IRON_INGOT);
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, epoch, "deposit-full", "skill",
                        null, null, null, new SkillParameters("DepositToStorage", "minecraft:iron_ingot", 1,
                                false, body.serverLevel().dimension().location().toString(),
                                chestPos.getX(), chestPos.getY(), chestPos.getZ())).success(),
                "full container deposit was not accepted for observable execution");
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(count(body, Items.IRON_INGOT), bodyBefore,
                    "full container deposit changed companion inventory");
            helper.assertValueEqual(count(chest, Items.IRON_INGOT), chestBefore,
                    "full container deposit changed storage inventory");
            helper.assertTrue(registry.runtimeSnapshots(false).stream()
                            .anyMatch(snapshot -> snapshot.companionId().equals(companionId)
                                    && snapshot.behaviorState().equals("PAUSED")
                                    && snapshot.evidenceSummary().contains("failure=CONTAINER_FULL")),
                    "full container deposit did not report CONTAINER_FULL");
            helper.assertTrue(registry.remove(owner).success(), "full storage cleanup failed");
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void craftingUsesVanillaRecipesAndMenusAndReportsMaterialShortage(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.persistence.seed")
                || Boolean.getBoolean("mccompanion.persistence.verify")
                || Boolean.getBoolean("mccompanion.runtime.e2e")
                || Boolean.getBoolean("mccompanion.stability")) {
            helper.succeed();
            return;
        }
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.setGameMode(GameType.SURVIVAL);
        helper.assertTrue(registry.create(owner, "CraftingCompanion").success(), "crafting create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "crafting test created no live body");
        body.getInventory().add(new ItemStack(Items.OAK_LOG, 2));

        String companionId = body.getUUID().toString();
        String leaseId = "gametest-crafting";
        long epoch = 1L;
        helper.assertTrue(registry.runtimeAcquireLease(
                        companionId, leaseId, epoch, System.currentTimeMillis() + 30_000L).success(),
                "crafting lease acquisition failed");
        helper.assertTrue(registry.runtimeStart(companionId, leaseId, epoch, "craft-planks", "skill",
                        null, null, null, new SkillParameters("CraftItem", "minecraft:oak_planks", 8, false)).success(),
                "2x2 crafting was not accepted for observable execution");
        helper.runAfterDelay(20, () -> {
            helper.assertValueEqual(count(body, Items.OAK_LOG), 0, "2x2 crafting did not consume two logs");
            helper.assertValueEqual(count(body, Items.OAK_PLANKS), 8, "2x2 crafting did not produce eight planks");
            helper.assertTrue(registry.runtimeSnapshots(false).stream()
                            .anyMatch(snapshot -> snapshot.companionId().equals(companionId)
                                    && snapshot.behaviorState().equals("IDLE")
                                    && snapshot.evidenceSummary().contains("success=true")),
                    "2x2 crafting did not report successful evidence");

            body.getInventory().clearContent();
            body.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
            body.getInventory().add(new ItemStack(Items.STICK, 2));
            BlockPos tablePos = body.blockPosition().offset(1, 0, 0);
            body.serverLevel().setBlockAndUpdate(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState());
            helper.assertTrue(registry.runtimeStart(companionId, leaseId, epoch, "craft-pickaxe", "skill",
                            null, null, null, new SkillParameters("CraftItem", "minecraft:iron_pickaxe", 1,
                                    false, body.serverLevel().dimension().location().toString(),
                                    tablePos.getX(), tablePos.getY(), tablePos.getZ())).success(),
                    "crafting-table skill was not accepted for observable execution");
            helper.runAfterDelay(20, () -> {
                helper.assertValueEqual(count(body, Items.IRON_PICKAXE), 1,
                        "crafting table did not produce the iron pickaxe");
                helper.assertValueEqual(count(body, Items.IRON_INGOT), 0,
                        "crafting table did not consume three ingots");
                helper.assertValueEqual(count(body, Items.STICK), 0,
                        "crafting table did not consume two sticks");

                body.getInventory().clearContent();
                helper.assertTrue(registry.runtimeStart(companionId, leaseId, epoch, "craft-missing", "skill",
                                null, null, null, new SkillParameters("CraftItem", "minecraft:iron_pickaxe", 1,
                                        false, body.serverLevel().dimension().location().toString(),
                                        tablePos.getX(), tablePos.getY(), tablePos.getZ())).success(),
                        "material shortage was not accepted for observable execution");
                helper.runAfterDelay(8, () -> {
                    helper.assertTrue(registry.runtimeSnapshots(false).stream()
                                    .anyMatch(snapshot -> snapshot.companionId().equals(companionId)
                                            && snapshot.behaviorState().equals("PAUSED")
                                            && snapshot.evidenceSummary().contains("failure=MATERIALS_INSUFFICIENT")
                                            && snapshot.behaviorObservation() != null
                                            && snapshot.behaviorObservation().failureCode().equals("MATERIALS_INSUFFICIENT")
                                            && snapshot.behaviorObservation().requested() == 1
                                            && snapshot.behaviorObservation().available() == 0),
                            "crafting shortage did not expose structured material evidence");
                    helper.assertTrue(registry.remove(owner).success(), "crafting test cleanup failed");
                    helper.succeed();
                });
            });
        });
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int count(Container container, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void awaitBehaviorIdle(
            GameTestHelper helper,
            CompanionRegistry registry,
            String companionId,
            int ticksRemaining,
            java.util.function.Consumer<CompanionRegistry.RuntimeSnapshot> terminalAssertions) {
        var snapshot = registry.runtimeSnapshots(false).stream()
                .filter(value -> value.companionId().equals(companionId)).findFirst().orElseThrow();
        if (snapshot.behaviorState().equals("RUNNING")) {
            helper.assertTrue(ticksRemaining > 0, "timed out waiting for behavior completion");
            helper.runAfterDelay(1, () -> awaitBehaviorIdle(
                    helper, registry, companionId, ticksRemaining - 1, terminalAssertions));
            return;
        }
        helper.assertValueEqual(snapshot.behaviorState(), "IDLE",
                "behavior reached an unexpected terminal state");
        terminalAssertions.accept(snapshot);
        helper.succeed();
    }

    private static double horizontalDistanceToSqr(Vec3 first, Vec3 second) {
        double x = first.x - second.x;
        double z = first.z - second.z;
        return x * x + z * z;
    }

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

        BlockPos movementOrigin = body.blockPosition();
        owner.moveTo(movementOrigin.getX() - 1.5D, movementOrigin.getY(), movementOrigin.getZ() + 0.5D,
                owner.getYRot(), owner.getXRot());
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
                helper.assertTrue(horizontalDistanceToSqr(body.position(), stoppedAt) < 0.04D,
                        "body kept walking horizontally after stop");
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
                        helper.assertTrue(horizontalDistanceToSqr(body.position(), pausedAt) < 0.04D,
                                "paused companion kept walking horizontally");
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
                        BlockPos shortageChestPos = recovered.blockPosition().offset(1, 0, 0);
                        recovered.serverLevel().setBlockAndUpdate(shortageChestPos, Blocks.CHEST.defaultBlockState());
                        Container shortageChest = (Container) recovered.serverLevel().getBlockEntity(shortageChestPos);
                        shortageChest.setItem(0, new ItemStack(Items.IRON_INGOT, 6));
                        int ownerIronBaseline = count(owner, Items.IRON_INGOT);
                        LOGGER.info("runtime_e2e_ready companion={} chest={},{},{}",
                                recovered.getUUID(), shortageChestPos.getX(), shortageChestPos.getY(), shortageChestPos.getZ());
                        helper.succeedWhen(() -> {
                            helper.assertTrue(registry.runtimeCommandCount() >= 5,
                                    "waiting for Runtime lease/follow/pause/resume/stop commands");
                            helper.assertValueEqual(count(owner, Items.IRON_INGOT) - ownerIronBaseline, 6,
                                    "waiting for partial shortage delivery to reach the owner");
                            helper.assertValueEqual(count(shortageChest, Items.IRON_INGOT), 0,
                                    "partial shortage delivery did not consume exactly the verified chest quantity");
                            helper.assertValueEqual(count(recovered, Items.IRON_INGOT), 0,
                                    "partial shortage delivery left duplicate iron in companion inventory");
                            var deliveredSnapshot = registry.runtimeSnapshots(false).stream()
                                    .filter(snapshot -> snapshot.companionId().equals(recovered.getUUID().toString()))
                                    .findFirst().orElseThrow();
                            helper.assertValueEqual(deliveredSnapshot.behaviorState(), "IDLE",
                                    "waiting for delivery behavior to become terminal");
                            helper.assertValueEqual(registry.runtimeLastPublishedBehaviorId(),
                                    deliveredSnapshot.behaviorId(),
                                    "waiting for RuntimeBridge to publish the terminal delivery observation");
                            LOGGER.info("runtime_e2e_conversation_complete companion={} delivered=6",
                                    recovered.getUUID());
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
