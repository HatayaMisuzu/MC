package com.mccompanion.minecraft.v120;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.minecraft.forge.PrimitiveObservationService;
import com.mccompanion.minecraft.forge.RegistryObservationService;
import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
            batch = "fullBridgeLifecycle",
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
                body.getX() + 4.0D, body.getY(), body.getZ(), null);
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
        helper.assertTrue(
                registry.runtimeAcquireLease(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                System.currentTimeMillis() + 60_000L)
                        .success(),
                "primitive lease acquisition failed");
        BlockPos lookTarget = body.blockPosition().offset(2, 1, 0);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-look-primitive",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "LookAt",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        lookTarget.getX(),
                                        lookTarget.getY(),
                                        lookTarget.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "look primitive failed to start");
        registry.tick();
        Vec3 expectedLook = Vec3.atCenterOf(lookTarget).subtract(body.getEyePosition()).normalize();
        helper.assertTrue(
                expectedLook.dot(body.getViewVector(1.0F)) >= 0.995D,
                "look primitive did not rotate the real ServerPlayer body");
        helper.assertTrue(
                registry.runtimeSnapshots(true).stream()
                        .filter(snapshot -> snapshot.companionId().equals(companionId))
                        .findFirst()
                        .orElseThrow()
                        .evidenceSummary()
                        .contains("VANILLA_ENTITY_LOOK"),
                "look primitive evidence did not record the vanilla rotation path");
        helper.assertTrue(body.addItem(new ItemStack(Items.STONE, 2)), "primitive item add failed");
        int stoneBefore = body.getInventory().countItem(Items.STONE);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-drop-primitive",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "DropItem",
                                        "minecraft:stone",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "drop primitive failed to start");
        registry.tick();
        helper.assertTrue(
                body.getInventory().countItem(Items.STONE) == stoneBefore - 1,
                "drop primitive did not use the real ServerPlayer drop path");
        BlockPos chestPosition = body.blockPosition().offset(1, 0, 0);
        body.serverLevel().setBlockAndUpdate(chestPosition, Blocks.CHEST.defaultBlockState());
        helper.assertTrue(
                body.serverLevel().getBlockEntity(chestPosition) instanceof Container,
                "menu fixture chest did not create a live container");
        Container chest = (Container) body.serverLevel().getBlockEntity(chestPosition);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-open-menu",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "InteractBlock",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        chestPosition.getX(),
                                        chestPosition.getY(),
                                        chestPosition.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "menu-opening primitive failed to start");
        registry.tick();
        helper.assertTrue(body.containerMenu != body.inventoryMenu, "chest menu did not open");
        var menuObservation = PrimitiveObservationService.inspect(
                registry,
                companionId,
                JSON.createObjectNode().put("tool", "menu.inspect"));
        helper.assertTrue(menuObservation.success(), "menu observation failed: " + menuObservation.code());
        String menuToken = menuObservation.observation().path("sessionToken").asText();
        helper.assertTrue(
                menuToken.matches("[A-Za-z0-9_-]{32}"),
                "menu observation did not return a bounded opaque session token");
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-menu-click-pickup",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "MenuAction",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        menuToken,
                                        0,
                                        0,
                                        "CLICK",
                                        null))
                        .success(),
                "menu click pickup primitive failed to start");
        registry.tick();
        helper.assertTrue(
                body.containerMenu.getCarried().is(Items.IRON_INGOT)
                        && body.containerMenu.getCarried().getCount() == 3
                        && chest.getItem(0).isEmpty(),
                "menu click did not move the exact live chest stack onto the vanilla carried slot");
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-menu-click-return",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "MenuAction",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        menuToken,
                                        0,
                                        0,
                                        "CLICK",
                                        null))
                        .success(),
                "menu click return primitive failed to start");
        registry.tick();
        helper.assertTrue(
                body.containerMenu.getCarried().isEmpty()
                        && chest.getItem(0).is(Items.IRON_INGOT)
                        && chest.getItem(0).getCount() == 3,
                "second menu click did not restore the exact live stack");
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-menu-quick-move",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "MenuAction",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        menuToken,
                                        0,
                                        null,
                                        "QUICK_MOVE",
                                        null))
                        .success(),
                "menu quick-move primitive failed to start");
        registry.tick();
        helper.assertTrue(
                body.getInventory().countItem(Items.IRON_INGOT) == 3,
                "menu quick-move did not transfer the live chest stack");
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-menu-close",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "MenuAction",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        menuToken,
                                        null,
                                        null,
                                        "CLOSE",
                                        null))
                        .success(),
                "menu close primitive failed to start");
        registry.tick();
        helper.assertTrue(body.containerMenu == body.inventoryMenu, "menu close did not restore inventory menu");
        helper.assertTrue(
                !MenuSessionTracker.validate(body, menuToken).valid(),
                "closed menu session token remained valid");
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-storage-deposit",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "DepositToStorage",
                                        "minecraft:iron_ingot",
                                        2,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        chestPosition.getX(),
                                        chestPosition.getY(),
                                        chestPosition.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "storage deposit failed to start");
        for (int tick = 0; tick < 4; tick++) registry.tick();
        helper.assertTrue(chest.getItem(0).getCount() == 2, "deposit did not move two exact items");
        helper.assertTrue(
                body.getInventory().countItem(Items.IRON_INGOT) == 1,
                "deposit inventory delta was incorrect");
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-storage-withdraw",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "WithdrawFromStorage",
                                        "minecraft:iron_ingot",
                                        2,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        chestPosition.getX(),
                                        chestPosition.getY(),
                                        chestPosition.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "storage withdrawal failed to start");
        for (int tick = 0; tick < 4; tick++) registry.tick();
        helper.assertTrue(chest.getItem(0).isEmpty(), "withdraw did not remove exact chest items");
        helper.assertTrue(
                body.getInventory().countItem(Items.IRON_INGOT) == 3,
                "withdraw inventory delta was incorrect");
        int ownerIronBefore = owner.getInventory().countItem(Items.IRON_INGOT);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-deliver",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "DeliverItem",
                                        "minecraft:iron_ingot",
                                        2,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "delivery failed to start");
        for (int tick = 0; tick < 4; tick++) registry.tick();
        helper.assertTrue(
                owner.getInventory().countItem(Items.IRON_INGOT) == ownerIronBefore + 2,
                "delivery did not use the real item pickup path");
        body.serverLevel().setBlockAndUpdate(chestPosition, Blocks.AIR.defaultBlockState());
        body.serverLevel().setBlockAndUpdate(chestPosition, Blocks.DIRT.defaultBlockState());
        int dirtBefore = body.getInventory().countItem(Items.DIRT);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-break-block",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "MineResourceVein",
                                        "minecraft:dirt",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        chestPosition.getX(),
                                        chestPosition.getY(),
                                        chestPosition.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "bounded mining failed to start");
        for (int tick = 0; tick < 40; tick++) registry.tick();
        helper.assertTrue(
                body.serverLevel().getBlockState(chestPosition).isAir(),
                "bounded mining did not break the exact verified block");
        helper.assertTrue(
                body.getInventory().countItem(Items.DIRT) > dirtBefore,
                "bounded mining did not collect its vanilla drop");
        int coalBefore = body.getInventory().countItem(Items.COAL);
        ItemEntity coalDrop = new ItemEntity(
                body.serverLevel(),
                body.getX() + 1.0D,
                body.getY(),
                body.getZ(),
                new ItemStack(Items.COAL, 2));
        body.serverLevel().addFreshEntity(coalDrop);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-collect",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "CollectResource",
                                        "minecraft:coal",
                                        2,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "bounded collection failed to start");
        for (int tick = 0; tick < 5; tick++) registry.tick();
        helper.assertTrue(
                body.getInventory().countItem(Items.COAL) == coalBefore + 2,
                "bounded collection did not use ItemEntity pickup");
        body.getFoodData().setFoodLevel(10);
        helper.assertTrue(body.addItem(new ItemStack(Items.APPLE)), "food fixture add failed");
        int applesBefore = body.getInventory().countItem(Items.APPLE);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-eat",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "EatAndRecover",
                                        "minecraft:apple",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "eat-and-recover failed to start");
        for (int tick = 0; tick < 40; tick++) registry.tick();
        helper.assertTrue(
                body.getInventory().countItem(Items.APPLE) == applesBefore - 1,
                "eat-and-recover did not consume food through vanilla use");
        helper.assertTrue(
                body.getFoodData().getFoodLevel() > 10,
                "eat-and-recover did not restore food");
        helper.assertTrue(body.addItem(new ItemStack(Items.IRON_SWORD)), "defense weapon fixture add failed");
        for (int slot = 0; slot < 9; slot++) {
            if (body.getInventory().getItem(slot).is(Items.IRON_SWORD)) {
                body.getInventory().selected = slot;
                break;
            }
        }
        Zombie threat = EntityType.ZOMBIE.create(body.serverLevel());
        helper.assertTrue(threat != null, "defense fixture entity creation failed");
        threat.moveTo(body.getX() + 1.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        threat.setHealth(1.0F);
        threat.setTarget(owner);
        body.serverLevel().addFreshEntity(threat);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-defend-owner",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "DefendOwner",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "defend-owner failed to start");
        for (int tick = 0; tick < 20; tick++) registry.tick();
        helper.assertTrue(!threat.isAlive(), "defend-owner did not defeat the bounded hostile");
        BlockPos retreatOrigin = moveToSealedArena(helper, owner, body, 17);
        for (int x = -2; x <= 10; x++) {
            for (int z = -2; z <= 2; z++) {
                body.serverLevel().setBlockAndUpdate(
                        retreatOrigin.offset(x, -1, z),
                        Blocks.STONE.defaultBlockState());
                body.serverLevel().setBlockAndUpdate(
                        retreatOrigin.offset(x, 0, z),
                        Blocks.AIR.defaultBlockState());
                body.serverLevel().setBlockAndUpdate(
                        retreatOrigin.offset(x, 1, z),
                        Blocks.AIR.defaultBlockState());
            }
        }
        Zombie retreatThreat = EntityType.ZOMBIE.create(body.serverLevel());
        helper.assertTrue(retreatThreat != null, "retreat fixture entity creation failed");
        retreatThreat.moveTo(body.getX() - 1.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        retreatThreat.setNoAi(true);
        body.serverLevel().addFreshEntity(retreatThreat);
        Vec3 retreatStart = body.position();
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-primitive-lease",
                                2L,
                                "forge-retreat",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "RetreatFromDanger",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        retreatThreat.getUUID().toString(),
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "retreat-from-danger failed to start");
        awaitRetreatAcceptance(
                helper,
                registry,
                owner,
                ownerConnection,
                body,
                companionId,
                retreatStart,
                retreatThreat,
                body.serverLevel().getServer().getTickCount() + 480);
    }

    private static void awaitRetreatAcceptance(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            FakeConnection ownerConnection,
            CompanionPlayer body,
            String companionId,
            Vec3 retreatStart,
            Zombie retreatThreat,
            int deadlineTick) {
        if (body.position().distanceToSqr(retreatStart) >= 9.0D
                && body.distanceToSqr(retreatThreat) >= 36.0D) {
            continueAfterRetreat(
                    helper,
                    registry,
                    owner,
                    ownerConnection,
                    body,
                    companionId,
                    retreatStart,
                    retreatThreat);
            return;
        }
        helper.assertTrue(
                body.serverLevel().getServer().getTickCount() <= deadlineTick,
                "retreat did not reach the bounded displacement and clearance before timeout: start="
                        + retreatStart
                        + " end="
                        + body.position()
                        + " threat="
                        + retreatThreat.position()
                        + " status="
                        + registry.runtimeSnapshots(true));
        helper.runAfterDelay(
                1,
                () -> awaitRetreatAcceptance(
                        helper,
                        registry,
                        owner,
                        ownerConnection,
                        body,
                        companionId,
                        retreatStart,
                        retreatThreat,
                        deadlineTick));
    }

    @GameTest(
            batch = "craftAndSmelt",
            templateNamespace = "minecraft",
            template = "bastion/mobs/empty",
            timeoutTicks = 300000)
    public static void craftAndSmeltThroughVanillaMenus(GameTestHelper helper) {
        FakeConnection ownerConnection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-craft-smelt-owner"));
        Vec3 ownerSpawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, 1.0D));
        owner.moveTo(ownerSpawn.x, ownerSpawn.y, ownerSpawn.z, 0.0F, 0.0F);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(ownerConnection, owner);
        CompanionRegistry registry =
                MinecraftAiCompanionForge.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry != null, "craft/smelt registry was not initialized");
        helper.assertTrue(registry.create(owner, "ForgeCrafter").success(), "craft/smelt create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "craft/smelt body was not spawned");
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(snapshot -> snapshot.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId)
                .findFirst()
                .orElseThrow();
        helper.assertTrue(
                registry.runtimeAcquireLease(
                                companionId,
                                "forge-craft-smelt-lease",
                                1L,
                                System.currentTimeMillis() + 300_000L)
                        .success(),
                "craft/smelt lease acquisition failed");
        BlockPos scanNear = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos scanFar = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlockAndUpdate(scanNear, Blocks.GOLD_ORE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(scanFar, Blocks.GOLD_ORE.defaultBlockState());
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-craft-smelt-lease",
                                1L,
                                "forge-scan",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "ExploreArea",
                                        "minecraft:gold_ore",
                                        3,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "world scan failed to start");
        for (int tick = 0; tick < 5; tick++) registry.tick();
        CompanionRegistry.BehaviorObservation scanObservation =
                registry.runtimeSnapshots(false).stream()
                        .filter(snapshot -> snapshot.companionId().equals(companionId))
                        .map(CompanionRegistry.RuntimeSnapshot::behaviorObservation)
                        .findFirst()
                        .orElseThrow();
        helper.assertTrue(
                scanObservation.failureCode().equals("SCAN_COMPLETE")
                        && scanObservation.candidates().size() >= 2,
                "world scan did not return the live gold-ore candidates");
        helper.assertTrue(
                scanObservation.candidates().get(0).distanceSquared()
                        <= scanObservation.candidates().get(1).distanceSquared(),
                "world scan candidates were not distance-ranked");
        if (!Boolean.getBoolean("mccompanion.runtime.e2e")) {
            assertPlacementUseEntityAttackAndVein(
                    helper,
                    registry,
                    body,
                    companionId,
                    "forge-craft-smelt-lease",
                    1L);
        }
        helper.assertTrue(body.addItem(new ItemStack(Items.OAK_LOG)), "craft input fixture add failed");
        helper.assertTrue(body.addItem(new ItemStack(Items.RAW_IRON)), "smelt input fixture add failed");
        helper.assertTrue(body.addItem(new ItemStack(Items.COAL)), "smelt fuel fixture add failed");
        int logsBefore = body.getInventory().countItem(Items.OAK_LOG);
        int planksBefore = body.getInventory().countItem(Items.OAK_PLANKS);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-craft-smelt-lease",
                                1L,
                                "forge-craft",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "CraftItem",
                                        "minecraft:oak_planks",
                                        4,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "craft failed to start");
        for (int tick = 0; tick < 5; tick++) registry.tick();
        helper.assertTrue(
                body.getInventory().countItem(Items.OAK_PLANKS) == planksBefore + 4,
                "craft did not produce four planks through the vanilla inventory menu");
        helper.assertTrue(
                body.getInventory().countItem(Items.OAK_LOG) == logsBefore - 1,
                "craft did not consume one log through the vanilla recipe");
        helper.assertTrue(
                body.addItem(new ItemStack(Items.OAK_PLANKS, 8)),
                "three-by-three craft input fixture add failed");
        BlockPos craftingTable = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(craftingTable, Blocks.CRAFTING_TABLE.defaultBlockState());
        int chestBefore = body.getInventory().countItem(Items.CHEST);
        int tablePlanksBefore = body.getInventory().countItem(Items.OAK_PLANKS);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-craft-smelt-lease",
                                1L,
                                "forge-craft-table",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "CraftItem",
                                        "minecraft:chest",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        craftingTable.getX(),
                                        craftingTable.getY(),
                                        craftingTable.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "three-by-three craft failed to start");
        for (int tick = 0; tick < 5; tick++) registry.tick();
        helper.assertTrue(
                body.getInventory().countItem(Items.CHEST) == chestBefore + 1,
                "three-by-three craft did not produce a chest");
        helper.assertTrue(
                body.getInventory().countItem(Items.OAK_PLANKS) == tablePlanksBefore - 8,
                "three-by-three craft did not consume eight planks");
        BlockPos furnace = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
        int rawIronBefore = body.getInventory().countItem(Items.RAW_IRON);
        int ingotsBefore = body.getInventory().countItem(Items.IRON_INGOT);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-craft-smelt-lease",
                                1L,
                                "forge-smelt",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "SmeltItem",
                                        "minecraft:iron_ingot",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        furnace.getX(),
                                        furnace.getY(),
                                        furnace.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "smelt failed to start");
        registry.tick();
        helper.runAfterDelay(200, () -> helper.succeedWhen(() -> {
            helper.assertTrue(
                    body.getInventory().countItem(Items.IRON_INGOT) == ingotsBefore + 1,
                    "smelt did not retrieve the real furnace result");
            helper.assertTrue(
                    body.getInventory().countItem(Items.RAW_IRON) == rawIronBefore - 1,
                    "smelt did not consume the real furnace input");
            helper.assertTrue(
                    body.serverLevel().getBlockEntity(furnace) instanceof Container furnaceContainer
                            && furnaceContainer.getItem(0).isEmpty()
                            && furnaceContainer.getItem(2).isEmpty(),
                    "smelt left input or result in the furnace");
            helper.assertTrue(
                    registry.runtimeReleaseLease(
                                    companionId,
                                    "forge-craft-smelt-lease",
                                    1L)
                            .success(),
                    "craft/smelt lease release failed");
            helper.assertTrue(registry.remove(owner).success(), "craft/smelt cleanup failed");
            helper.getLevel().getServer().getPlayerList().remove(owner);
            ownerConnection.disconnect(Component.literal("Forge craft/smelt GameTest complete"));
            helper.succeed();
        }));
    }

    @GameTest(
            batch = "runtimeReconnect",
            templateNamespace = "minecraft",
            template = "bastion/mobs/empty",
            timeoutTicks = 300000)
    public static void runtimeReconnectPreservesIdentityAndEpoch(GameTestHelper helper) {
        CompanionEntry persistedProbe =
                new CompanionEntry(UUID.randomUUID(), UUID.randomUUID(), "ForgeRestart");
        persistedProbe.mode = CompanionEntry.Mode.SKILL;
        persistedProbe.resumeMode = CompanionEntry.Mode.SKILL;
        persistedProbe.runtimeEpoch = 7L;
        persistedProbe.runtimeBehaviorId = "forge-restart-behavior";
        persistedProbe.runtimeBehaviorRevision = 4L;
        CompanionEntry restoredProbe = CompanionEntry.load(persistedProbe.save());
        helper.assertTrue(
                restoredProbe.mode == CompanionEntry.Mode.SKILL
                        && restoredProbe.resumeMode == CompanionEntry.Mode.SKILL
                        && restoredProbe.runtimeEpoch == 7L
                        && "forge-restart-behavior".equals(restoredProbe.runtimeBehaviorId)
                        && restoredProbe.runtimeBehaviorRevision == 4L,
                "runtime recovery metadata did not survive the saved-data NBT round trip");

        FakeConnection ownerConnection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-reconnect-owner"));
        Vec3 ownerSpawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, 1.0D));
        owner.moveTo(ownerSpawn.x, ownerSpawn.y, ownerSpawn.z, 0.0F, 0.0F);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(ownerConnection, owner);
        CompanionRegistry registry =
                MinecraftAiCompanionForge.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry != null, "reconnect registry was not initialized");
        helper.assertTrue(registry.create(owner, "ForgeReconnect").success(), "reconnect create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, "reconnect body was not spawned");
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(snapshot -> snapshot.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId)
                .findFirst()
                .orElseThrow();
        helper.assertTrue(
                registry.runtimeAcquireLease(
                                companionId,
                                "forge-reconnect-lease-1",
                                1L,
                                System.currentTimeMillis() + 60_000L)
                        .success(),
                "initial reconnect lease failed");
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                "forge-reconnect-lease-1",
                                1L,
                                "forge-reconnect-behavior",
                                "travel",
                                body.getX() + 8.0D,
                                body.getY(),
                                body.getZ(),
                                null)
                        .success(),
                "reconnect behavior failed to start");
        registry.runtimeDisconnected();
        CompanionRegistry.RuntimeSnapshot disconnected = registry.runtimeSnapshots(false).stream()
                .filter(snapshot -> snapshot.companionId().equals(companionId))
                .findFirst()
                .orElseThrow();
        helper.assertTrue(
                disconnected.behaviorState().equals("PAUSED")
                        && "forge-reconnect-behavior".equals(disconnected.behaviorId())
                        && disconnected.controlEpoch() == 1L,
                "disconnect did not retain the paused behavior identity and epoch");
        helper.assertTrue(
                !registry.runtimeAcquireLease(
                                companionId,
                                "forge-replayed-lease",
                                1L,
                                System.currentTimeMillis() + 60_000L)
                        .success(),
                "disconnect allowed a replayed control epoch");
        CompanionRegistry.RuntimeResult reacquired = registry.runtimeAcquireLease(
                companionId,
                "forge-reconnect-lease-2",
                2L,
                System.currentTimeMillis() + 60_000L);
        helper.assertTrue(
                reacquired.success()
                        && "forge-reconnect-behavior".equals(reacquired.behaviorId())
                        && reacquired.state().equals("PAUSED"),
                "higher-epoch reconnect did not reconcile the paused behavior");
        CompanionRegistry.RuntimeResult resumed = registry.runtimeResume(
                companionId,
                "forge-reconnect-lease-2",
                2L);
        helper.assertTrue(
                resumed.success()
                        && "forge-reconnect-behavior".equals(resumed.behaviorId())
                        && resumed.state().equals("RUNNING"),
                "reconciled movement did not resume");
        helper.assertTrue(
                registry.runtimeReleaseLease(
                                companionId,
                                "forge-reconnect-lease-2",
                                2L)
                        .success(),
                "reconnect lease release failed");
        helper.assertTrue(registry.remove(owner).success(), "reconnect cleanup failed");
        helper.getLevel().getServer().getPlayerList().remove(owner);
        ownerConnection.disconnect(Component.literal("Forge reconnect GameTest complete"));
        helper.succeed();
    }

    private static void assertPlacementUseEntityAttackAndVein(
            GameTestHelper helper,
            CompanionRegistry registry,
            CompanionPlayer body,
            String companionId,
            String leaseId,
            long epoch) {
        BlockPos placementTarget = body.blockPosition().offset(1, 0, 0);
        BlockPos placementSupport = placementTarget.below();
        body.serverLevel().setBlockAndUpdate(placementSupport, Blocks.STONE.defaultBlockState());
        body.serverLevel().setBlockAndUpdate(placementTarget, Blocks.AIR.defaultBlockState());
        helper.assertTrue(body.addItem(new ItemStack(Items.COBBLESTONE)), "placement fixture add failed");
        int cobblestoneBefore = body.getInventory().countItem(Items.COBBLESTONE);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                leaseId,
                                epoch,
                                "forge-place",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "PlaceBlock",
                                        "minecraft:cobblestone",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        placementTarget.getX(),
                                        placementTarget.getY(),
                                        placementTarget.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "placement primitive failed to start");
        registry.tick();
        helper.assertTrue(
                body.serverLevel().getBlockState(placementTarget).is(Blocks.COBBLESTONE),
                "placement primitive did not create the exact live block");
        helper.assertTrue(
                body.getInventory().countItem(Items.COBBLESTONE) == cobblestoneBefore - 1,
                "placement primitive did not consume the vanilla item");

        helper.assertTrue(body.addItem(new ItemStack(Items.SNOWBALL, 2)), "item-use fixture add failed");
        int snowballsBefore = body.getInventory().countItem(Items.SNOWBALL);
        int projectilesBefore = body.serverLevel()
                .getEntitiesOfClass(
                        Snowball.class,
                        body.getBoundingBox().inflate(8.0D),
                        projectile -> projectile.getOwner() == body)
                .size();
        body.setXRot(-30.0F);
        body.xRotO = -30.0F;
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                leaseId,
                                epoch,
                                "forge-use",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "UseItem",
                                        "minecraft:snowball",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "item-use primitive failed to start");
        registry.tick();
        helper.assertTrue(
                body.getInventory().countItem(Items.SNOWBALL) == snowballsBefore - 1,
                "item-use primitive did not consume one snowball");
        var ownedProjectiles = body.serverLevel().getEntitiesOfClass(
                Snowball.class,
                body.getBoundingBox().inflate(8.0D),
                projectile -> projectile.getOwner() == body);
        helper.assertTrue(
                ownedProjectiles.size() > projectilesBefore,
                "item-use primitive did not create a vanilla projectile");
        ownedProjectiles.forEach(Snowball::discard);

        Cow cow = EntityType.COW.create(body.serverLevel());
        helper.assertTrue(cow != null, "entity-interaction fixture creation failed");
        cow.moveTo(body.getX() + 1.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        body.serverLevel().addFreshEntity(cow);
        helper.assertTrue(body.addItem(new ItemStack(Items.WHEAT)), "entity-interaction item add failed");
        for (int slot = 0; slot < 9; slot++) {
            if (body.getInventory().getItem(slot).is(Items.WHEAT)) {
                body.getInventory().selected = slot;
                break;
            }
        }
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                leaseId,
                                epoch,
                                "forge-interact-entity",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "InteractEntity",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        cow.getUUID().toString(),
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "entity-interaction primitive failed to start");
        registry.tick();
        helper.assertTrue(cow.isInLove(), "entity interaction did not invoke the vanilla cow interaction");
        cow.discard();

        Zombie attacked = EntityType.ZOMBIE.create(body.serverLevel());
        helper.assertTrue(attacked != null, "attack fixture creation failed");
        attacked.moveTo(body.getX() + 1.0D, body.getY(), body.getZ(), 0.0F, 0.0F);
        attacked.setHealth(0.1F);
        body.serverLevel().addFreshEntity(attacked);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                leaseId,
                                epoch,
                                "forge-attack-entity",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "AttackEntity",
                                        "",
                                        1,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        null,
                                        null,
                                        null,
                                        attacked.getUUID().toString(),
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "attack primitive failed to start");
        registry.tick();
        helper.assertTrue(!attacked.isAlive(), "attack primitive did not damage the exact live target");

        body.serverLevel().setBlockAndUpdate(placementTarget, Blocks.AIR.defaultBlockState());
        BlockPos veinOrigin = body.blockPosition().offset(2, 0, 0);
        BlockPos veinSecond = veinOrigin.above();
        body.serverLevel().setBlockAndUpdate(veinOrigin.below(), Blocks.STONE.defaultBlockState());
        body.serverLevel().setBlockAndUpdate(veinOrigin, Blocks.DIRT.defaultBlockState());
        body.serverLevel().setBlockAndUpdate(veinSecond, Blocks.DIRT.defaultBlockState());
        int dirtBefore = body.getInventory().countItem(Items.DIRT);
        helper.assertTrue(
                registry.runtimeStart(
                                companionId,
                                leaseId,
                                epoch,
                                "forge-mine-vein",
                                "skill",
                                null,
                                null,
                                null,
                                new SkillParameters(
                                        "MineResourceVein",
                                        "minecraft:dirt",
                                        2,
                                        false,
                                        body.serverLevel().dimension().location().toString(),
                                        veinOrigin.getX(),
                                        veinOrigin.getY(),
                                        veinOrigin.getZ(),
                                        "",
                                        "UP",
                                        "MAIN_HAND",
                                        "",
                                        null,
                                        null,
                                        "",
                                        null))
                        .success(),
                "multi-block vein primitive failed to start");
        for (int tick = 0; tick < 80; tick++) registry.tick();
        helper.assertTrue(
                body.serverLevel().getBlockState(veinOrigin).isAir()
                        && body.serverLevel().getBlockState(veinSecond).isAir(),
                "multi-block vein primitive did not break both connected blocks");
        helper.assertTrue(
                body.getInventory().countItem(Items.DIRT) >= dirtBefore + 2,
                "multi-block vein primitive did not claim both vanilla drops");

    }

    private static void continueAfterRetreat(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            FakeConnection ownerConnection,
            CompanionPlayer body,
            String companionId,
            Vec3 retreatStart,
            Zombie retreatThreat) {
        helper.assertTrue(
                body.position().distanceToSqr(retreatStart) >= 9.0D,
                "retreat did not move the body at least three blocks: start="
                        + retreatStart
                        + " end="
                        + body.position()
                        + " threat="
                        + retreatThreat.position()
                        + " status="
                        + registry.runtimeSnapshots(true));
        helper.assertTrue(
                body.distanceToSqr(retreatThreat) >= 36.0D,
                "retreat did not establish a six-block safety margin");
        retreatThreat.discard();
        helper.assertTrue(
                registry.runtimeReleaseLease(companionId, "forge-primitive-lease", 2L).success(),
                "primitive lease release failed");
        // The primitive assertions above advance the packetless body directly. Reset only the
        // test fixture's residual vanilla physics before the independent movement assertion.
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0.0F;
        if (Boolean.getBoolean("mccompanion.runtime.e2e")) {
            long runtimeCommandBaseline = registry.runtimeCommandCount();
            LOGGER.info("forge_runtime_e2e_ready companion={}", companionId);
            helper.succeedWhen(() -> {
                CompanionRegistry.RuntimeSnapshot runtimeSnapshot = registry.runtimeSnapshots(true).stream()
                        .filter(snapshot -> snapshot.companionId().equals(companionId))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(
                        registry.runtimeCommandCount() >= runtimeCommandBaseline + 6
                                && runtimeSnapshot.behaviorId() == null
                                && runtimeSnapshot.behaviorState().equals("IDLE"),
                        "waiting for Runtime start/pause/resume/cancel lifecycle");
                CompanionCommands.TextRequestResult playerRequest =
                        MinecraftAiCompanionForge.integrationSubmitPlayerText(
                                owner,
                                "report current status");
                helper.assertTrue(
                        playerRequest.accepted(),
                        "authenticated player request was not accepted: " + playerRequest.message());
                MinecraftAiCompanionForge.integrationSubmitOwnerBlockActivity(
                        owner,
                        owner.blockPosition(),
                        "BLOCK_USE");
                LOGGER.info("forge_runtime_e2e_player_and_owner_activity_sent companion={}", companionId);
                helper.assertTrue(registry.remove(owner).success(), "Runtime E2E cleanup failed");
                helper.getLevel().getServer().getPlayerList().remove(owner);
                ownerConnection.disconnect(Component.literal("Forge Runtime E2E complete"));
            });
            return;
        }

        helper.runAfterDelay(220, () -> {
            // The navigation arena extends far beyond the tiny vanilla empty template.
            // Move both test participants to a separate Z corridor before building it so
            // Forge's adjacent GameTest templates cannot overlap later aquatic fixtures.
            Vec3 isolatedNavigationSpawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, 33.0D));
            owner.moveTo(
                    isolatedNavigationSpawn.x,
                    isolatedNavigationSpawn.y,
                    isolatedNavigationSpawn.z,
                    0.0F,
                    0.0F);
            body.moveTo(
                    isolatedNavigationSpawn.x,
                    isolatedNavigationSpawn.y,
                    isolatedNavigationSpawn.z,
                    0.0F,
                    0.0F);
            body.setDeltaMovement(Vec3.ZERO);
            BlockPos pathOrigin = body.blockPosition();
            for (int x = -1; x <= 15; x++) {
                for (int z = -4; z <= 4; z++) {
                    body.serverLevel().setBlockAndUpdate(
                            pathOrigin.offset(x, -1, z),
                            Blocks.STONE.defaultBlockState());
                    for (int y = 0; y <= 2; y++) {
                        body.serverLevel().setBlockAndUpdate(
                                pathOrigin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
            for (int x = -1; x <= 15; x++) {
                for (int z : new int[] {-3, 3}) {
                    for (int y = 0; y <= 2; y++) {
                        body.serverLevel().setBlockAndUpdate(
                                pathOrigin.offset(x, y, z),
                                Blocks.COBBLESTONE.defaultBlockState());
                    }
                }
                for (int z = -3; z <= 3; z++) {
                    body.serverLevel().setBlockAndUpdate(
                            pathOrigin.offset(x, 3, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            for (int x : new int[] {-1, 15}) {
                for (int z = -3; z <= 3; z++) {
                    for (int y = 0; y <= 2; y++) {
                        body.serverLevel().setBlockAndUpdate(
                                pathOrigin.offset(x, y, z),
                                Blocks.COBBLESTONE.defaultBlockState());
                    }
                }
            }
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 2; y++) {
                    body.serverLevel().setBlockAndUpdate(
                            pathOrigin.offset(3, y, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            var lowerDoor = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                    .setValue(BlockStateProperties.OPEN, false);
            body.serverLevel().setBlockAndUpdate(pathOrigin.offset(3, 0, 0), lowerDoor);
            body.serverLevel().setBlockAndUpdate(
                    pathOrigin.offset(3, 1, 0),
                    lowerDoor.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
            body.serverLevel().setBlockAndUpdate(pathOrigin.offset(3, 2, 0), Blocks.AIR.defaultBlockState());
            var blocker = EntityType.PIG.create(body.serverLevel());
            helper.assertTrue(blocker != null, "navigation blocker could not be created");
            blocker.setNoAi(true);
            blocker.moveTo(Vec3.atBottomCenterOf(pathOrigin.offset(8, 0, 0)));
            helper.assertTrue(body.serverLevel().addFreshEntity(blocker),
                    "navigation blocker could not be spawned");
            Vec3 blockerPosition = blocker.position();
            Vec3 target = Vec3.atBottomCenterOf(pathOrigin.offset(10, 0, 0));
            CompanionRegistry.Result moving = registry.goTo(owner, target.x, target.y, target.z);
            helper.assertTrue(moving.success(), "global navigation failed to start: " + moving.code());
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(registry.pause(owner).success(), "mid-route navigation pause failed");
                Vec3 pausedAt = body.position();
                helper.runAfterDelay(7, () -> {
                    helper.assertTrue(
                            body.position().distanceToSqr(pausedAt) < 0.04D,
                            "paused global navigation kept moving");
                    helper.assertTrue(registry.resume(owner).success(),
                            "mid-route navigation resume failed");
                });
            });
            insertDynamicObstacleAfterDoorOpens(
                    helper,
                    body,
                    pathOrigin.offset(3, 0, 0),
                    pathOrigin.offset(6, 0, 0),
                    240);
            helper.runAfterDelay(480, () -> {
                helper.assertTrue(
                        body.position().distanceToSqr(target) <= 2.25D,
                        "global navigation did not route around the dynamic walls: position="
                                + body.position()
                                + " target="
                                + target
                                + " door="
                                + body.serverLevel().getBlockState(pathOrigin.offset(3, 0, 0))
                                + " status="
                                + registry.status(owner));
                helper.assertTrue(
                        body.serverLevel().getBlockState(pathOrigin.offset(3, 0, 0))
                                        .is(Blocks.OAK_DOOR)
                                && body.serverLevel().getBlockState(pathOrigin.offset(3, 0, 0))
                                        .getValue(BlockStateProperties.OPEN)
                                && body.serverLevel().getBlockState(pathOrigin.offset(6, 0, 0))
                                        .is(Blocks.COBBLESTONE),
                        "navigation did not open the door or modified the dynamic obstacle");
                helper.assertTrue(
                        blocker.isAlive() && blocker.position().distanceToSqr(blockerPosition) < 0.04D,
                        "navigation displaced or harmed the observed entity blocker");
                blocker.discard();

                BlockPos verticalOrigin = moveToSealedArena(helper, owner, body, 65);
                for (int x = -1; x <= 12; x++) {
                    for (int z = -1; z <= 1; z++) {
                        for (int y = -1; y <= 3; y++) {
                            body.serverLevel().setBlockAndUpdate(
                                    verticalOrigin.offset(x, y, z),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                for (int x = -1; x <= 1; x++) {
                    body.serverLevel().setBlockAndUpdate(
                            verticalOrigin.offset(x, -1, 0),
                            Blocks.STONE.defaultBlockState());
                }
                var stair = Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
                body.serverLevel().setBlockAndUpdate(verticalOrigin.offset(2, -1, 0), stair);
                for (int x = 3; x <= 12; x++) {
                    body.serverLevel().setBlockAndUpdate(
                            verticalOrigin.offset(x, 0, 0),
                            Blocks.STONE.defaultBlockState());
                }
                Vec3 verticalTarget = Vec3.atBottomCenterOf(verticalOrigin.offset(6, 1, 0));
                CompanionRegistry.Result verticalRun =
                        registry.goTo(owner, verticalTarget.x, verticalTarget.y, verticalTarget.z);
                helper.assertTrue(
                        verticalRun.success(),
                        "vertical navigation failed to start: " + verticalRun.code());
                helper.runAfterDelay(140, () -> {
                    helper.assertTrue(
                            body.position().distanceToSqr(verticalTarget) <= 2.25D,
                            "Forge navigation did not reach the raised platform through vanilla movement");
                    helper.assertTrue(
                            body.serverLevel().getBlockState(verticalOrigin.offset(2, -1, 0))
                                            .is(Blocks.OAK_STAIRS)
                                    && body.serverLevel().getBlockState(verticalOrigin.offset(3, 0, 0))
                                            .is(Blocks.STONE),
                            "Forge vertical navigation mutated the stair or platform");
                    runAquaticAndClimbingAcceptance(
                            helper,
                            registry,
                            owner,
                            body,
                            ownerConnection);
                });
            });
        });
    }

    private static void runAquaticAndClimbingAcceptance(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            CompanionPlayer body,
            FakeConnection ownerConnection) {
        BlockPos swimOrigin = moveToSealedArena(helper, owner, body, 97);
        for (int x = -1; x <= 8; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 4; y++) {
                    body.serverLevel().setBlockAndUpdate(
                            swimOrigin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = -1; x <= 8; x++) {
            body.serverLevel().setBlockAndUpdate(
                    swimOrigin.offset(x, -1, 0),
                    Blocks.STONE.defaultBlockState());
        }
        for (int x = 3; x <= 7; x++) {
            for (int y = 0; y <= 3; y++) {
                body.serverLevel().setBlockAndUpdate(
                        swimOrigin.offset(x, y, 0),
                        Blocks.WATER.defaultBlockState());
                body.serverLevel().setBlockAndUpdate(
                        swimOrigin.offset(x, y, -1),
                        Blocks.GLASS.defaultBlockState());
                body.serverLevel().setBlockAndUpdate(
                        swimOrigin.offset(x, y, 1),
                        Blocks.GLASS.defaultBlockState());
            }
        }
        for (int y = 0; y <= 3; y++) {
            body.serverLevel().setBlockAndUpdate(
                    swimOrigin.offset(8, y, 0),
                    Blocks.GLASS.defaultBlockState());
        }
        Vec3 swimTarget = Vec3.atBottomCenterOf(swimOrigin.offset(6, 2, 0));
        CompanionRegistry.Result swimRun =
                registry.goTo(owner, swimTarget.x, swimTarget.y, swimTarget.z);
        helper.assertTrue(swimRun.success(), "Forge swim navigation failed to start: " + swimRun.code());
        awaitNavigationPosition(helper, body, swimTarget, 240, () -> {
            helper.assertTrue(
                    body.serverLevel().getFluidState(swimOrigin.offset(6, 2, 0))
                            .is(net.minecraft.tags.FluidTags.WATER),
                    "Forge swim navigation mutated the target water volume");
            runClimbingAndLifecycleAcceptance(
                    helper,
                    registry,
                    owner,
                    body,
                    ownerConnection);
        });
    }

    private static void runClimbingAndLifecycleAcceptance(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            CompanionPlayer body,
            FakeConnection ownerConnection) {
        BlockPos climbOrigin = moveToSealedArena(helper, owner, body, 129);
        // Clear beyond the prior swim volume so delayed water updates cannot
        // make the ladder approach nondeterministically unreachable.
        for (int x = -5; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -1; y <= 5; y++) {
                    body.serverLevel().setBlockAndUpdate(
                            climbOrigin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = -1; x <= 2; x++) {
            body.serverLevel().setBlockAndUpdate(
                    climbOrigin.offset(x, -1, 0),
                    Blocks.STONE.defaultBlockState());
        }
        for (int y = 0; y <= 4; y++) {
            body.serverLevel().setBlockAndUpdate(
                    climbOrigin.offset(3, y, 0),
                    Blocks.STONE.defaultBlockState());
        }
        var ladder = Blocks.LADDER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        for (int y = 0; y <= 1; y++) {
            body.serverLevel().setBlockAndUpdate(climbOrigin.offset(2, y, 0), ladder);
        }
        var vine = Blocks.VINE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.VineBlock.EAST, true);
        for (int y = 2; y <= 3; y++) {
            body.serverLevel().setBlockAndUpdate(climbOrigin.offset(2, y, 0), vine);
        }
        Vec3 climbTarget = Vec3.atBottomCenterOf(climbOrigin.offset(2, 3, 0));
        CompanionRegistry.Result climbRun =
                registry.goTo(owner, climbTarget.x, climbTarget.y, climbTarget.z);
        helper.assertTrue(climbRun.success(), "Forge climb navigation failed to start: " + climbRun.code());
        awaitNavigationPosition(helper, body, climbTarget, 240, () -> {
            helper.assertTrue(
                    body.serverLevel().getBlockState(climbOrigin.offset(2, 1, 0)).is(Blocks.LADDER)
                            && body.serverLevel().getBlockState(climbOrigin.offset(2, 3, 0)).is(Blocks.VINE),
                    "Forge climb navigation mutated the ladder or vine");
            runHazardAndMovingTargetAcceptance(helper, registry, owner, body, ownerConnection);
        });
    }

    private static void runHazardAndMovingTargetAcceptance(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            CompanionPlayer body,
            FakeConnection ownerConnection) {
        // Earlier navigation stages intentionally accumulate horizontal and vertical movement.
        // Start hazard/follow in a second sealed corridor so that chain cannot leave the first
        // arena and admit generated-world fluids or adjacent GameTest fixtures.
        BlockPos origin = moveToSealedArena(helper, owner, body, 161);
        for (int x = -1; x <= 13; x++) {
            for (int z = -2; z <= 2; z++) {
                body.serverLevel().setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 2; y++) {
                    body.serverLevel().setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = 2; x <= 3; x++) {
            body.serverLevel().setBlockAndUpdate(
                    origin.offset(x, -1, 0),
                    Blocks.MAGMA_BLOCK.defaultBlockState());
        }
        float healthBefore = body.getHealth();
        Vec3 safeTarget = Vec3.atBottomCenterOf(origin.offset(5, 0, 0));
        CompanionRegistry.Result hazardRun =
                registry.goTo(owner, safeTarget.x, safeTarget.y, safeTarget.z);
        helper.assertTrue(hazardRun.success(),
                "Forge hazard-aware navigation failed to start: " + hazardRun.code());
        awaitNavigationPosition(helper, body, safeTarget, 260, () -> {
            helper.assertTrue(body.getHealth() == healthBefore,
                    "Forge hazard-aware navigation damaged the companion: before=" + healthBefore
                            + " after=" + body.getHealth()
                            + " position=" + body.position()
                            + " source=" + (body.getLastDamageSource() == null
                                    ? "none"
                                    : body.getLastDamageSource().getMsgId())
                            + " feetBlock=" + body.serverLevel().getBlockState(body.blockPosition())
                            + " headBlock=" + body.serverLevel().getBlockState(body.blockPosition().above())
                            + " eyeBlock=" + body.serverLevel().getBlockState(
                                    BlockPos.containing(body.getEyePosition()))
                            + " origin=" + origin
                            + " status=" + registry.status(owner));
            for (int x = 2; x <= 3; x++) {
                helper.assertTrue(body.serverLevel().getBlockState(origin.offset(x, -1, 0))
                                .is(Blocks.MAGMA_BLOCK),
                        "Forge hazard-aware navigation mutated the dangerous fixture");
            }
            Vec3 firstOwnerTarget = Vec3.atBottomCenterOf(origin.offset(9, 0, 0));
            owner.moveTo(firstOwnerTarget.x, firstOwnerTarget.y, firstOwnerTarget.z, 0.0F, 0.0F);
            helper.assertTrue(registry.follow(owner).success(),
                    "Forge moving-target follow failed to start");
            awaitFollowPosition(helper, body, firstOwnerTarget, 220, () -> {
                Vec3 secondOwnerTarget = Vec3.atBottomCenterOf(origin.offset(11, 0, 0));
                owner.moveTo(secondOwnerTarget.x, secondOwnerTarget.y, secondOwnerTarget.z, 0.0F, 0.0F);
                helper.runAfterDelay(8, () -> {
                    helper.assertTrue(body.position().distanceToSqr(firstOwnerTarget) > 0.20D,
                            "Forge navigation did not react to the moved follow target");
                    helper.assertTrue(registry.stop(owner).success(),
                            "Forge explicit owner stop did not cancel navigation");
                    Vec3 stoppedAt = body.position();
                    helper.runAfterDelay(12, () -> {
                        helper.assertTrue(body.position().distanceToSqr(stoppedAt) < 0.04D,
                                "Forge companion kept moving after explicit owner stop");
                        helper.assertTrue(registry.status(owner).contains("mode=IDLE"),
                                "Forge explicit owner stop did not leave navigation IDLE");
                        runNavigationCombinationAcceptance(
                                helper, registry, owner, body, ownerConnection);
                    });
                });
            });
        });
    }

    private static void runNavigationCombinationAcceptance(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            CompanionPlayer body,
            FakeConnection ownerConnection) {
        // Forge GameTest retires non-player entity fixtures outside its managed structure.
        // Return only this entity-backed combination to the test origin; Forge batches are serial.
        Vec3 managedSpawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, 1.0D));
        owner.moveTo(managedSpawn.x, managedSpawn.y, managedSpawn.z, 0.0F, 0.0F);
        body.moveTo(managedSpawn.x, managedSpawn.y, managedSpawn.z, 0.0F, 0.0F);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0.0F;
        body.setAirSupply(body.getMaxAirSupply());
        body.clearFire();
        BlockPos work = body.blockPosition();
        for (int x = -5; x <= 5; x++) {
            for (int z = -3; z <= 3; z++) {
                body.serverLevel().setBlockAndUpdate(work.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                body.serverLevel().setBlockAndUpdate(work.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                body.serverLevel().setBlockAndUpdate(work.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            }
        }
        String companionId = registry.runtimeSnapshots(false).stream()
                .filter(snapshot -> snapshot.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId)
                .findFirst()
                .orElseThrow();
        String lease = "forge-navigation-combination";
        helper.assertTrue(registry.runtimeAcquireLease(
                        companionId, lease, 3L, System.currentTimeMillis() + 30_000L).success(),
                "Forge navigation combination lease acquisition failed");

        int coalBefore = body.getInventory().countItem(Items.COAL);
        ItemEntity coal = new ItemEntity(body.serverLevel(),
                work.getX() + 2.5D, work.getY(), work.getZ() + 0.5D,
                new ItemStack(Items.COAL));
        body.serverLevel().addFreshEntity(coal);
        helper.assertTrue(registry.runtimeStart(
                        companionId, lease, 3L, "forge-navigation-combo-collect", "skill",
                        null, null, null,
                        new SkillParameters("CollectResource", "minecraft:coal", 1, false,
                                body.serverLevel().dimension().location().toString(),
                                null, null, null, "", "UP", "MAIN_HAND",
                                "", null, null, "", null))
                        .success(),
                "Forge post-navigation collection failed to start");
        awaitRuntimeBehaviorIdle(helper, registry, companionId, 180, () -> {
            helper.assertTrue(body.getInventory().countItem(Items.COAL) == coalBefore + 1,
                    "Forge post-navigation collection inventory delta was incorrect");

            BlockPos dirt = body.blockPosition().offset(2, 0, 1);
            body.serverLevel().setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            int dirtBefore = body.getInventory().countItem(Items.DIRT);
            helper.assertTrue(registry.runtimeStart(
                            companionId, lease, 3L, "forge-navigation-combo-mine", "skill",
                            null, null, null,
                            new SkillParameters("MineResourceVein", "minecraft:dirt", 1, false,
                                    body.serverLevel().dimension().location().toString(),
                                    dirt.getX(), dirt.getY(), dirt.getZ(), "", "UP", "MAIN_HAND",
                                    "", null, null, "", null))
                            .success(),
                    "Forge post-navigation mining failed to start");
            awaitRuntimeBehaviorIdle(helper, registry, companionId, 180, () -> {
                helper.assertTrue(body.serverLevel().getBlockState(dirt).isAir(),
                        "Forge post-navigation mining did not change the exact world target");
                helper.assertTrue(body.getInventory().countItem(Items.DIRT) == dirtBefore + 1,
                        "Forge post-navigation mining inventory delta was incorrect");

                BlockPos chestPos = body.blockPosition().offset(1, 0, -1);
                body.serverLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
                Container chest = (Container) body.serverLevel().getBlockEntity(chestPos);
                chest.setItem(0, new ItemStack(Items.IRON_INGOT));
                int ironBefore = body.getInventory().countItem(Items.IRON_INGOT);
                helper.assertTrue(registry.runtimeStart(
                                companionId, lease, 3L, "forge-navigation-combo-container", "skill",
                                null, null, null,
                                new SkillParameters("WithdrawFromStorage", "minecraft:iron_ingot", 1, false,
                                        body.serverLevel().dimension().location().toString(),
                                        chestPos.getX(), chestPos.getY(), chestPos.getZ(), "", "UP", "MAIN_HAND",
                                        "", null, null, "", null))
                                .success(),
                        "Forge post-navigation container withdrawal failed to start");
                awaitRuntimeBehaviorIdle(helper, registry, companionId, 20, () -> {
                    helper.assertTrue(body.getInventory().countItem(Items.IRON_INGOT) == ironBefore + 1,
                            "Forge post-navigation container inventory delta was incorrect");
                    helper.assertTrue(chest.isEmpty(),
                            "Forge post-navigation container source delta was incorrect");
                    helper.assertTrue(registry.runtimeReleaseLease(companionId, lease, 3L).success(),
                            "Forge navigation combination lease release failed");
                    runNavigationBoundaryAcceptance(helper, registry, owner, body, ownerConnection);
                });
            });
        });
    }

    private static void runNavigationBoundaryAcceptance(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            CompanionPlayer body,
            FakeConnection ownerConnection) {
        CompanionRegistry.Result invalid =
                registry.goTo(owner, Double.NaN, body.getY(), body.getZ());
        helper.assertTrue(!invalid.success() && "INVALID_TARGET".equals(invalid.code()),
                "Forge non-finite target was not rejected honestly: " + invalid.code());
        helper.assertTrue(registry.status(owner).contains("mode=IDLE"),
                "Forge invalid target changed the active navigation mode");

        BlockPos unloaded = findUnloadedNavigationTarget(body);
        helper.assertTrue(unloaded != null,
                "Forge GameTest could not find an unloaded target within the navigation bound");
        Vec3 target = Vec3.atBottomCenterOf(unloaded);
        helper.assertTrue(registry.goTo(owner, target.x, target.y, target.z).success(),
                "Forge unloaded-boundary navigation failed to start");
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(!body.serverLevel().hasChunkAt(unloaded),
                    "Forge navigation unexpectedly loaded its target chunk");
            String status = registry.status(owner);
            helper.assertTrue(status.contains("mode=PAUSED")
                            && status.contains("failure=TARGET_CHUNK_UNLOADED"),
                    "Forge unloaded target did not pause with honest evidence: " + status);
            helper.assertTrue(registry.stop(owner).success(),
                    "Forge unloaded-boundary reset failed");
            finishLifecycleAcceptance(helper, registry, owner, body, ownerConnection);
        });
    }

    private static BlockPos findUnloadedNavigationTarget(CompanionPlayer body) {
        BlockPos origin = body.blockPosition();
        for (int distance = 192; distance >= 16; distance -= 16) {
            BlockPos[] candidates = {
                    origin.offset(distance, 0, 0),
                    origin.offset(-distance, 0, 0),
                    origin.offset(0, 0, distance),
                    origin.offset(0, 0, -distance)
            };
            for (BlockPos candidate : candidates) {
                if (!body.serverLevel().hasChunkAt(candidate)) return candidate;
            }
        }
        return null;
    }

    private static BlockPos moveToSealedArena(
            GameTestHelper helper,
            ServerPlayer owner,
            CompanionPlayer body,
            int relativeZ) {
        Vec3 spawn = helper.absoluteVec(new Vec3(1.0D, 1.0D, relativeZ));
        owner.moveTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        body.moveTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0.0F;
        body.setAirSupply(body.getMaxAirSupply());
        body.clearFire();
        BlockPos origin = body.blockPosition();
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                body.serverLevel().setChunkForced(chunkX + offsetX, chunkZ + offsetZ, true);
            }
        }
        for (int x = -6; x <= 16; x++) {
            for (int z = -4; z <= 4; z++) {
                body.serverLevel().setBlockAndUpdate(
                        origin.offset(x, -2, z), Blocks.COBBLESTONE.defaultBlockState());
                for (int y = -1; y <= 5; y++) {
                    body.serverLevel().setBlockAndUpdate(
                            origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                body.serverLevel().setBlockAndUpdate(
                        origin.offset(x, 6, z), Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        for (int x = -6; x <= 16; x++) {
            for (int z : new int[] {-4, 4}) {
                for (int y = -1; y <= 5; y++) {
                    body.serverLevel().setBlockAndUpdate(
                            origin.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState());
                }
            }
        }
        for (int x : new int[] {-6, 16}) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -1; y <= 5; y++) {
                    body.serverLevel().setBlockAndUpdate(
                            origin.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState());
                }
            }
        }
        return origin;
    }

    private static void awaitRuntimeBehaviorIdle(
            GameTestHelper helper,
            CompanionRegistry registry,
            String companionId,
            int ticksRemaining,
            Runnable completed) {
        var snapshot = registry.runtimeSnapshots(true).stream()
                .filter(value -> value.companionId().equals(companionId))
                .findFirst()
                .orElseThrow();
        if (snapshot.behaviorState().equals("IDLE")) {
            completed.run();
            return;
        }
        helper.assertTrue(snapshot.behaviorState().equals("RUNNING"),
                "Forge behavior stopped before completion: state=" + snapshot.behaviorState()
                        + " failure=" + (snapshot.behaviorObservation() == null
                                ? "none"
                                : snapshot.behaviorObservation().failureCode())
                        + " evidence=" + snapshot.evidenceSummary());
        helper.assertTrue(ticksRemaining > 0,
                "Forge behavior did not complete within its original tick budget: behavior="
                        + snapshot.behaviorId() + " evidence=" + snapshot.evidenceSummary());
        helper.runAfterDelay(1, () -> awaitRuntimeBehaviorIdle(
                helper, registry, companionId, ticksRemaining - 1, completed));
    }

    private static void awaitNavigationPosition(
            GameTestHelper helper,
            CompanionPlayer body,
            Vec3 target,
            int ticksRemaining,
            Runnable reached) {
        if (body.position().distanceToSqr(target) <= 2.25D) {
            reached.run();
            return;
        }
        helper.assertTrue(
                ticksRemaining > 0,
                "navigation did not reach the observed target: position="
                        + body.position()
                        + " target="
                        + target);
        helper.runAfterDelay(
                1,
                () -> awaitNavigationPosition(
                        helper,
                        body,
                        target,
                        ticksRemaining - 1,
                        reached));
    }

    private static void insertDynamicObstacleAfterDoorOpens(
            GameTestHelper helper,
            CompanionPlayer body,
            BlockPos door,
            BlockPos obstacle,
            int ticksRemaining) {
        helper.runAfterDelay(1, () -> {
            var state = body.serverLevel().getBlockState(door);
            if (state.is(Blocks.OAK_DOOR)
                    && state.hasProperty(BlockStateProperties.OPEN)
                    && state.getValue(BlockStateProperties.OPEN)) {
                for (int y = 0; y <= 2; y++) {
                    body.serverLevel().setBlockAndUpdate(
                            obstacle.above(y),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
                return;
            }
            helper.assertTrue(
                    ticksRemaining > 1,
                    "navigation did not open the door before dynamic obstacle injection");
            insertDynamicObstacleAfterDoorOpens(
                    helper, body, door, obstacle, ticksRemaining - 1);
        });
    }

    private static void awaitFollowPosition(
            GameTestHelper helper,
            CompanionPlayer body,
            Vec3 target,
            int ticksRemaining,
            Runnable reached) {
        if (body.position().distanceToSqr(target) <= 9.0D) {
            reached.run();
            return;
        }
        helper.assertTrue(
                ticksRemaining > 0,
                "follow did not reach moving target: position="
                        + body.position()
                        + " target="
                        + target);
        helper.runAfterDelay(
                1,
                () -> awaitFollowPosition(
                        helper,
                        body,
                        target,
                        ticksRemaining - 1,
                        reached));
    }

    private static void finishLifecycleAcceptance(
            GameTestHelper helper,
            CompanionRegistry registry,
            ServerPlayer owner,
            CompanionPlayer body,
            FakeConnection ownerConnection) {
        BlockPos stopOrigin = body.blockPosition();
        for (int x = -6; x <= 0; x++) {
            body.serverLevel().setBlockAndUpdate(
                    stopOrigin.offset(x, -1, 0),
                    Blocks.STONE.defaultBlockState());
            body.serverLevel().setBlockAndUpdate(stopOrigin.offset(x, 0, 0), Blocks.AIR.defaultBlockState());
            body.serverLevel().setBlockAndUpdate(stopOrigin.offset(x, 1, 0), Blocks.AIR.defaultBlockState());
        }
        Vec3 stopStart = body.position();
        CompanionRegistry.Result stopRun =
                registry.goTo(owner, stopStart.x - 4.0D, stopStart.y, stopStart.z);
        helper.assertTrue(stopRun.success(), "stop regression goto failed: " + stopRun.code());
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(
                    body.position().distanceToSqr(stopStart) > 0.20D,
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
                helper.assertTrue(reloaded.getUUID().equals(body.getUUID()),
                        "body UUID changed across sleep/wake");
                helper.assertTrue(reloaded.fakeConnection().retainedPacketCount() == 0,
                        "replacement fake connection retained packets");
                helper.assertTrue(reloaded.getInventory().contains(new ItemStack(Items.DIAMOND)),
                        "inventory did not persist across sleep/wake");
                helper.assertTrue(
                        reloaded.hurt(reloaded.damageSources().fellOutOfWorld(), Float.MAX_VALUE),
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
