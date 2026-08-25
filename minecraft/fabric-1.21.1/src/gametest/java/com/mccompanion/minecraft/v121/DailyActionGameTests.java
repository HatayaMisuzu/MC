package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/**
 * Real Fabric 1.21.1 acceptance coverage for the shared daily-action runtime.
 *
 * <p>Every action is submitted through the same lease/start path used by Runtime.  The test
 * never calls DailyActionAdapter: the assertions inspect the live ServerPlayer body and the
 * terminal RuntimeSnapshot, so a capability allow-list or a no-op cannot satisfy these tests.
 */
public final class DailyActionGameTests implements FabricGameTest {
    private static final String DIMENSION = "minecraft:overworld";

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500, batch = "daily_equipment_main")
    public void equipmentEquipUnequipBestToolWeaponAndDamagedFailure(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-equipment");
        f.body.getInventory().add(new ItemStack(Items.IRON_HELMET));
        f.body.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        f.body.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        BlockPos stone = f.body.blockPosition().offset(1, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        ItemStack broken = new ItemStack(Items.DIAMOND_PICKAXE);
        broken.setDamageValue(broken.getMaxDamage() - 1);
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "equipment-equip",
                        params("EquipItem", "minecraft:iron_helmet", "HEAD", "EQUIP", null), 80,
                        snapshot -> helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                                "helmet was not equipped by vanilla inventory menu")),
                new Step(() -> { ItemStack worn = f.body.getItemBySlot(EquipmentSlot.HEAD);
                    worn.setDamageValue(worn.getMaxDamage() - 1); }, "equipment-worn-damaged",
                        params("EquipItem", "minecraft:iron_helmet", "HEAD", "EQUIP", null), 80,
                        snapshot -> helper.assertTrue("EQUIPMENT_UNUSABLE".equals(
                                        snapshot.behaviorObservation().failureCode()),
                                "already worn damaged armor was incorrectly accepted")),
                new Step(() -> {}, "equipment-unequip",
                        params("EquipItem", "", "HEAD", "UNEQUIP", null), 80,
                        snapshot -> helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                                "helmet was not unequipped")),
                new Step(() -> {}, "equipment-best-tool",
                        params("EquipItem", "minecraft:stone", "MAIN_HAND", "BEST_TOOL", stone), 120,
                        snapshot -> helper.assertTrue(f.body.getMainHandItem().is(Items.IRON_PICKAXE),
                                "best tool was not selected")),
                new Step(() -> f.body.getInventory().add(broken), "equipment-damaged",
                        params("EquipItem", "minecraft:diamond_pickaxe", "MAIN_HAND", "EQUIP", null), 80,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "EQUIPMENT_UNUSABLE".equals(snapshot.behaviorObservation().failureCode()),
                                "damaged equipment did not produce an honest failure")),
                new Step(() -> {}, "equipment-best-weapon",
                        params("EquipItem", "", "MAIN_HAND", "BEST_WEAPON_MELEE", null), 80,
                        snapshot -> helper.assertTrue(f.body.getMainHandItem().is(Items.DIAMOND_SWORD),
                                "best melee weapon was not selected"))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500, batch = "daily_equipment_hands")
    public void equipmentMainAndOffhandControlsRejectMissingItem(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-equipment-offhand");
        f.body.getInventory().add(new ItemStack(Items.IRON_SWORD));
        f.body.getInventory().add(new ItemStack(Items.SHIELD));
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "equipment-main-hand",
                        params("EquipItem", "minecraft:iron_sword", "MAIN_HAND", "EQUIP", null), 100,
                        snapshot -> helper.assertTrue(f.body.getMainHandItem().is(Items.IRON_SWORD),
                                "main-hand item was not equipped")),
                new Step(() -> {}, "equipment-offhand",
                        params("EquipItem", "minecraft:shield", "OFF_HAND", "EQUIP", null), 100,
                        snapshot -> helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD),
                                "offhand item was not equipped")),
                new Step(() -> {}, "equipment-offhand-unequip",
                        params("EquipItem", "", "OFF_HAND", "UNEQUIP", null), 100,
                        snapshot -> { helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(),
                                "offhand item was not unequipped"); helper.assertTrue(
                                f.body.getInventory().countItem(Items.SHIELD) > 0,
                                "unequipped offhand item was not returned to inventory"); }),
                new Step(() -> {}, "equipment-auto-shield",
                        params("EquipItem", "minecraft:shield", "AUTO", "EQUIP", null), 100,
                        snapshot -> helper.assertTrue(
                                f.body.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD),
                                "AUTO equipment did not place a shield in the offhand")),
                new Step(() -> f.body.getInventory().clearContent(), "equipment-missing-offhand",
                        params("EquipItem", "minecraft:shield", "OFF_HAND", "EQUIP", null), 100,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "ITEM_NOT_FOUND".equals(snapshot.behaviorObservation().failureCode()),
                                "missing offhand item was not rejected honestly: "
                                        + snapshot.behaviorObservation()))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "daily_sleep_cancel")
    public void nearbyBedNavigationCanBeCancelledWithoutLeavingTheBodyAsleep(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-sleep-cancel");
        f.body.moveTo(512.5D, 80.0D, 512.5D, 0.0F, 0.0F);
        f.owner.moveTo(512.5D, 80.0D, 510.5D, 0.0F, 0.0F);
        BlockPos start = f.body.blockPosition();
        BlockPos bed = start.offset(5, 0, 0);
        for (int x = -2; x <= 7; x++) for (int z = -2; z <= 2; z++) {
            f.body.serverLevel().setBlockAndUpdate(
                    start.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y <= 2; y++) {
                f.body.serverLevel().setBlockAndUpdate(start.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        f.body.serverLevel().setDayTime(13000L);
        placeBed(f, bed, false);
        var approachPlan = new SurvivalNavigationAdapter().plan(
                f.body, Vec3.atBottomCenterOf(bed.west()));
        helper.assertTrue(approachPlan.status()
                        == com.mccompanion.core.navigation.GridPathPlanner.Status.READY,
                "nearby-bed fixture has no real walkable approach: " + approachPlan.status());
        SkillParameters nearby = new SkillParameters("SleepAtBed", "", 8, false, DIMENSION,
                null, null, null, "", "UP", "MAIN_HAND", "", null, null, "SLEEP", 0, "");
        start(f, "sleep-nearby-cancel", nearby);
        awaitSleepingWhileRunning(helper, f, 300, () -> {
            helper.assertTrue(f.body.blockPosition().distSqr(start) > 1.0D,
                    "nearby-bed sleep did not navigate the body toward the selected bed");
            CompanionRegistry.RuntimeResult cancelled = f.registry.runtimeCancel(f.id, f.lease, 1L);
            helper.assertTrue(cancelled.success() && "CANCELLED".equals(cancelled.state()),
                    "sleep cancellation failed: " + cancelled.code());
            helper.runAfterDelay(1, () -> {
                CompanionRegistry.RuntimeSnapshot terminal = snapshot(f);
                helper.assertTrue(!f.body.isSleeping(),
                        "cancelled sleep left the real ServerPlayer asleep");
                helper.assertValueEqual(terminal.behaviorState(), "IDLE",
                        "cancelled sleep did not return to IDLE");
                finish(helper, f);
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500, batch = "daily_sleep_main")
    public void sleepWakeAndOccupiedBedFailure(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-sleep");
        BlockPos bed = f.body.blockPosition().offset(1, 0, 0);
        f.body.serverLevel().setDayTime(13000L);
        placeBed(f, bed, false);
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "sleep", params("SleepAtBed", "", "", "SLEEP", bed), 100,
                        snapshot -> helper.assertTrue(f.body.isSleeping(), "body did not sleep in a real bed")),
                new Step(() -> {}, "wake", params("SleepAtBed", "", "", "WAKE", null), 80,
                        snapshot -> helper.assertTrue(!f.body.isSleeping(), "body did not wake")),
                new Step(() -> placeBed(f, bed, true), "occupied-bed",
                        params("SleepAtBed", "", "", "SLEEP", bed), 80,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "BED_OCCUPIED".equals(snapshot.behaviorObservation().failureCode()),
                                "occupied bed did not fail honestly"))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "daily_sleep_errors")
    public void sleepRejectsNearbyMonsterAndWrongDimension(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-sleep-errors");
        BlockPos bed = f.body.blockPosition().offset(1, 0, 0);
        f.body.serverLevel().setDayTime(13000L);
        placeBed(f, bed, false);
        Zombie zombie = EntityType.ZOMBIE.create(f.body.serverLevel());
        helper.assertTrue(zombie != null, "sleep monster fixture failed");
        zombie.setNoAi(true);
        // Keep the hostile inside the bed's vanilla NOT_SAFE search volume but outside
        // the three-block emergency-retreat radius, so this test reaches the sleep rule.
        zombie.setPos(f.body.getX(), f.body.getY(), f.body.getZ() + 4);
        f.body.serverLevel().addFreshEntity(zombie);
        start(f, "sleep-monster", params("SleepAtBed", "", "", "SLEEP", bed));
        await(helper, f, 120, unsafe -> {
            zombie.discard();
            helper.assertTrue("BED_NOT_SAFE".equals(unsafe.behaviorObservation().failureCode()),
                    "nearby monster did not reject sleep honestly: " + unsafe.behaviorObservation());
            SkillParameters wrongWorld = new SkillParameters("SleepAtBed", "", 4, false,
                    "minecraft:the_nether", bed.getX(), bed.getY(), bed.getZ(), "",
                    "UP", "MAIN_HAND", "", null, null, "SLEEP", 0, "");
            start(f, "sleep-wrong-world", wrongWorld);
            await(helper, f, 80, changed -> {
                helper.assertTrue("WORLD_CHANGED".equals(changed.behaviorObservation().failureCode()),
                        "wrong-dimension bed did not stop with WORLD_CHANGED: "
                                + changed.behaviorObservation());
                finish(helper, f);
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "daily_bucket_main")
    public void bucketFillAndEmptyVerifyWorldAndInventory(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-bucket");
        BlockPos source = f.body.blockPosition().offset(1, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        f.body.getInventory().add(new ItemStack(Items.BUCKET));
        BlockPos target = source.offset(1, 0, 0);
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "bucket-fill",
                        params("UseWaterBucket", "minecraft:bucket", "", "FILL", source), 120,
                        snapshot -> { helper.assertTrue(f.body.getInventory().countItem(Items.WATER_BUCKET) > 0,
                                "fill did not produce a water bucket"); helper.assertTrue(
                                f.body.serverLevel().getFluidState(source).isEmpty(),
                                "fill did not consume the source water");
                            helper.assertTrue("true".equals(snapshot.bucket().get("bucketTargetChanged")),
                                    "terminal bucket snapshot omitted the verified target delta"); }),
                new Step(() -> { f.body.serverLevel().setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
                    f.body.serverLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()); },
                        "bucket-empty", params("UseWaterBucket", "minecraft:water_bucket", "", "EMPTY", target), 120,
                        snapshot -> { helper.assertTrue(f.body.serverLevel().getFluidState(target)
                                        .is(net.minecraft.tags.FluidTags.WATER), "empty did not place water");
                            helper.assertTrue(f.body.getInventory().countItem(Items.BUCKET) > 0,
                                    "empty did not return an empty bucket");
                            helper.assertTrue("EMPTY".equals(snapshot.bucket().get("action")),
                                    "terminal bucket snapshot did not retain the empty action"); })
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "daily_bucket_errors")
    public void bucketRejectsMissingContainersAndInvalidSourceOrTarget(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-bucket-negative");
        BlockPos source = f.body.blockPosition().offset(1, 0, 0);
        BlockPos target = source.offset(1, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "bucket-missing-empty",
                        params("UseWaterBucket", "minecraft:bucket", "", "FILL", source), 120,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "BUCKET_MISSING".equals(snapshot.behaviorObservation().failureCode()),
                                "fill without an empty bucket was not rejected honestly: "
                                        + snapshot.behaviorObservation())),
                new Step(() -> { f.body.getInventory().clearContent();
                    f.body.serverLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()); },
                        "bucket-missing-water",
                        params("UseWaterBucket", "minecraft:water_bucket", "", "EMPTY", target), 120,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "WATER_BUCKET_MISSING".equals(snapshot.behaviorObservation().failureCode()),
                                "empty without a water bucket was not rejected honestly: "
                                        + snapshot.behaviorObservation())),
                new Step(() -> { f.body.getInventory().clearContent();
                    f.body.serverLevel().setBlockAndUpdate(source, Blocks.AIR.defaultBlockState());
                    f.body.getInventory().add(new ItemStack(Items.BUCKET)); }, "bucket-invalid-source",
                        params("UseWaterBucket", "minecraft:bucket", "", "FILL", source), 120,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "WATER_SOURCE_INVALID".equals(snapshot.behaviorObservation().failureCode()),
                                "fill from an invalid source was not rejected honestly: "
                                        + snapshot.behaviorObservation())),
                new Step(() -> { f.body.getInventory().clearContent();
                    f.body.serverLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                    f.body.getInventory().add(new ItemStack(Items.WATER_BUCKET)); }, "bucket-invalid-target",
                        params("UseWaterBucket", "minecraft:water_bucket", "", "EMPTY", target), 120,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "WATER_TARGET_INVALID".equals(snapshot.behaviorObservation().failureCode()),
                                "empty into an invalid target was not rejected honestly: "
                                        + snapshot.behaviorObservation()))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "daily_vehicle_main")
    public void boatAndMinecartMountTravelDismount(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-vehicle");
        Boat boat = EntityType.BOAT.create(f.body.serverLevel());
        helper.assertTrue(boat != null, "boat fixture could not be created");
        BlockPos waterOrigin = f.body.blockPosition().offset(1, 0, 0);
        for (int x = 0; x <= 4; x++) for (int z = -1; z <= 1; z++) {
            f.body.serverLevel().setBlockAndUpdate(waterOrigin.offset(x, 0, z), Blocks.WATER.defaultBlockState());
        }
        // A boat's eyes must be above the water surface before vanilla permits passengers.
        boat.setPos(f.body.getX() + 1, f.body.getY() + 1, f.body.getZ());
        f.body.serverLevel().addFreshEntity(boat);
        BlockPos destination = waterOrigin.offset(3, 0, 0);
        Minecart cart = EntityType.MINECART.create(f.body.serverLevel());
        helper.assertTrue(cart != null, "minecart fixture could not be created");
        String boatType = BuiltInRegistries.ENTITY_TYPE.getKey(boat.getType()).toString();
        String cartType = BuiltInRegistries.ENTITY_TYPE.getKey(cart.getType()).toString();
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "boat-mount", params("UseVehicle", boatType,
                        boat.getUUID().toString(), "MOUNT", null), 160,
                        snapshot -> helper.assertTrue(f.body.getVehicle() == boat, "body did not mount boat")),
                new Step(() -> {}, "boat-travel", params("UseVehicle", boatType,
                        boat.getUUID().toString(), "TRAVEL", destination), 260,
                        snapshot -> helper.assertTrue(boat.blockPosition().distSqr(destination) < 16,
                                "boat travel did not reach destination")),
                new Step(() -> {}, "boat-dismount", params("UseVehicle", boatType,
                        boat.getUUID().toString(), "DISMOUNT", null), 80,
                        snapshot -> helper.assertTrue(f.body.getVehicle() == null, "body did not dismount boat")),
                new Step(() -> {
                    BlockPos rail = f.body.blockPosition().offset(1, 0, 0);
                    f.body.serverLevel().setBlockAndUpdate(rail.below(), Blocks.STONE.defaultBlockState());
                    f.body.serverLevel().setBlockAndUpdate(rail, Blocks.RAIL.defaultBlockState());
                    cart.setPos(rail.getX() + .5D, rail.getY() + .1D, rail.getZ() + .5D);
                    f.body.serverLevel().addFreshEntity(cart);
                }, "cart-mount", params("UseVehicle", cartType,
                        cart.getUUID().toString(), "MOUNT", null), 160,
                        snapshot -> helper.assertTrue(f.body.getVehicle() == cart, "body did not mount minecart")),
                new Step(() -> {}, "cart-dismount", params("UseVehicle", cartType,
                        cart.getUUID().toString(), "DISMOUNT", null), 80,
                        snapshot -> helper.assertTrue(f.body.getVehicle() == null, "body did not dismount minecart"))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "daily_vehicle_lost")
    public void destroyedBoatDuringTravelStopsSafely(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-vehicle-lost");
        BlockPos water = f.body.blockPosition().offset(1, 0, 0);
        for (int x = 0; x <= 4; x++) for (int z = -1; z <= 1; z++) {
            f.body.serverLevel().setBlockAndUpdate(water.offset(x, 0, z), Blocks.WATER.defaultBlockState());
        }
        Boat boat = EntityType.BOAT.create(f.body.serverLevel());
        helper.assertTrue(boat != null, "lost-boat fixture failed");
        boat.setPos(f.body.getX() + 1, f.body.getY() + 1, f.body.getZ());
        f.body.serverLevel().addFreshEntity(boat);
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(boat.getType()).toString();
        start(f, "lost-boat-mount", params("UseVehicle", type,
                boat.getUUID().toString(), "MOUNT", null));
        await(helper, f, 100, mounted -> {
            helper.assertTrue(f.body.getVehicle() == boat, "lost-boat fixture did not mount");
            BlockPos destination = water.offset(4, 0, 0);
            start(f, "lost-boat-travel", params("UseVehicle", type,
                    boat.getUUID().toString(), "TRAVEL", destination));
            awaitRunningPhase(helper, f, "TRAVEL_VEHICLE", 100, () -> {
                boat.discard();
                await(helper, f, 100, lost -> {
                    helper.assertTrue("VEHICLE_LOST".equals(lost.behaviorObservation().failureCode())
                                    && f.body.getVehicle() == null,
                            "destroyed boat did not stop safely: " + lost.behaviorObservation());
                    finish(helper, f);
                });
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "daily_farm")
    public void matureCropHarvestPickupAndReplant(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-farm");
        BlockPos crop = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
        f.body.serverLevel().setBlockAndUpdate(
                crop.below().offset(0, 0, 3), Blocks.WATER.defaultBlockState());
        f.body.serverLevel().setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, 7));
        f.body.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 2));
        int wheatBefore = f.body.getInventory().countItem(Items.WHEAT);
        start(f, "farm", params("FarmCrop", "minecraft:wheat", "", "HARVEST_REPLANT", crop));
        await(helper, f, 240, snapshot -> {
            helper.assertTrue(f.body.serverLevel().getBlockState(crop).getBlock() == Blocks.WHEAT,
                    "crop was not replanted");
            helper.assertTrue(f.body.serverLevel().getBlockState(crop).getValue(CropBlock.AGE) == 0,
                    "replanted crop was not reset to age zero");
            helper.assertTrue(f.body.getInventory().countItem(Items.WHEAT) > wheatBefore,
                    "harvest result was not picked up");
            helper.assertTrue("true".equals(snapshot.crop().get("cropReplanted"))
                            && Integer.parseInt(snapshot.crop().getOrDefault("cropSeedConsumed", "0")) > 0,
                    "terminal crop snapshot omitted replant/seed evidence");
            finish(helper, f);
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500, batch = "daily_breed_main")
    public void twoCowsFeedAndVerifyBaby(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-breed");
        Cow first = cow(f, 1); Cow second = cow(f, 2);
        // Put the food in a deterministic hotbar slot so the shared observation sees it
        // before the first FEED command (Inventory.add may choose an implementation slot).
        f.body.getInventory().setItem(0, new ItemStack(Items.WHEAT, 2));
        helper.assertTrue(first.isFood(f.body.getInventory().getItem(0))
                        && second.isFood(f.body.getInventory().getItem(0)),
                "cow fixture does not accept the supplied wheat");
        start(f, "breed", params2("BreedAnimals", "", first.getUUID().toString(), second.getUUID().toString(), "BREED", null));
        await(helper, f, 240, snapshot -> { helper.assertTrue(
                f.body.serverLevel().getEntitiesOfClass(Cow.class, first.getBoundingBox().inflate(4), Animal::isBaby).size() > 0,
                "cow breeding did not create a baby");
            helper.assertTrue(Integer.parseInt(snapshot.breed().getOrDefault("babyCount", "0")) > 0
                            && "2".equals(snapshot.breed().get("breedFoodConsumed")),
                    "terminal breed snapshot omitted baby/food evidence");
            finish(helper, f); });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500, batch = "daily_breed_food")
    public void breedingRejectsInsufficientFood(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-breed-food");
        Cow first = cow(f, 1);
        Cow second = cow(f, 2);
        // One wheat makes both targets food-compatible but cannot satisfy the two-feed contract.
        f.body.getInventory().setItem(0, new ItemStack(Items.WHEAT));
        start(f, "breed-food-shortage", params2("BreedAnimals", "", first.getUUID().toString(),
                second.getUUID().toString(), "BREED", null));
        await(helper, f, 240, snapshot -> {
            helper.assertTrue(snapshot.behaviorObservation() != null
                            && "BREEDING_FOOD_MISSING".equals(snapshot.behaviorObservation().failureCode()),
                    "insufficient breeding food was not rejected honestly: " + snapshot.behaviorObservation());
            helper.assertTrue(f.body.serverLevel().getEntitiesOfClass(Cow.class,
                    f.body.getBoundingBox().inflate(4), Animal::isBaby).isEmpty(),
                    "insufficient food unexpectedly produced a baby");
            finish(helper, f);
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "daily_breed_lost")
    public void breedingRejectsRemovedTargetDuringAction(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-breed-target-loss");
        Cow first = cow(f, 1);
        Cow second = cow(f, 2);
        f.body.getInventory().setItem(0, new ItemStack(Items.WHEAT, 2));
        start(f, "breed-target-loss", params2("BreedAnimals", "", first.getUUID().toString(),
                second.getUUID().toString(), "BREED", null));
        helper.runAfterDelay(2, () -> {
            first.discard();
            await(helper, f, 240, snapshot -> {
                helper.assertTrue(snapshot.behaviorObservation() != null
                                && "FIRST_ANIMAL_LOST".equals(snapshot.behaviorObservation().failureCode()),
                        "removed breeding target was not rejected honestly: "
                                + snapshot.behaviorObservation());
                finish(helper, f);
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "daily_trade_main")
    public void villagerTradeUsesExplicitOfferAndReportsResources(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-trade");
        f.body.serverLevel().setDayTime(1000L);
        Villager villager = EntityType.VILLAGER.create(f.body.serverLevel());
        helper.assertTrue(villager != null, "villager fixture could not be created");
        villager.setPos(f.body.getX() + 1, f.body.getY(), f.body.getZ());
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getOffers().clear();
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD),
                java.util.Optional.empty(), new ItemStack(Items.BREAD), 8, 1, 0.05F));
        f.body.serverLevel().addFreshEntity(villager);
        f.body.getInventory().add(new ItemStack(Items.EMERALD));
        int before = f.body.getInventory().countItem(Items.BREAD);
        start(f, "trade", new SkillParameters("TradeWithVillager", "", 1, false, DIMENSION,
                null, null, null, villager.getUUID().toString(), "UP", "MAIN_HAND", "",
                0, null, "TRADE", 0, ""));
        await(helper, f, 240, snapshot -> { helper.assertTrue(f.body.getInventory().countItem(Items.BREAD) > before,
                "villager offer was not traded; code=" + snapshot.behaviorObservation().failureCode()
                        + " phase=" + snapshot.behaviorObservation().details().getOrDefault("phase", "")
                        + " menu=" + f.body.containerMenu.getClass().getSimpleName()
                        + " villagerDistance=" + f.body.distanceToSqr(villager));
            helper.assertTrue("minecraft:bread".equals(snapshot.trade().get("tradeOutput"))
                            && "1".equals(snapshot.trade().get("tradeOutputReceived")),
                    "terminal trade snapshot omitted the verified offer result");
            helper.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                    "completed trade left the merchant menu open");
            finish(helper, f); });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "daily_trade_errors")
    public void villagerTradeRejectsMissingResourcesAndClosedMenu(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-trade-errors");
        f.body.serverLevel().setDayTime(1000L);
        Villager villager = EntityType.VILLAGER.create(f.body.serverLevel());
        helper.assertTrue(villager != null, "trade error fixture failed");
        villager.setNoAi(true);
        villager.setPos(f.body.getX() + 1, f.body.getY(), f.body.getZ());
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getOffers().clear();
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD),
                java.util.Optional.empty(), new ItemStack(Items.BREAD), 8, 1, 0.05F));
        f.body.serverLevel().addFreshEntity(villager);
        SkillParameters trade = new SkillParameters("TradeWithVillager", "", 1, false, DIMENSION,
                null, null, null, villager.getUUID().toString(), "UP", "MAIN_HAND", "",
                0, null, "TRADE", 0, "");
        start(f, "trade-missing-resource", trade);
        await(helper, f, 120, missing -> {
            helper.assertTrue("TRADE_RESOURCES_INSUFFICIENT".equals(
                            missing.behaviorObservation().failureCode()),
                    "missing trade input was not rejected: " + missing.behaviorObservation());
            f.body.getInventory().add(new ItemStack(Items.EMERALD));
            start(f, "trade-menu-closed", trade);
            awaitRunningPhase(helper, f, "SELECT_TRADE", 120, () -> {
                f.body.closeContainer();
                await(helper, f, 100, closed -> {
                    helper.assertTrue("TRADE_MENU_INVALIDATED".equals(
                                    closed.behaviorObservation().failureCode()),
                            "closed trade menu was not invalidated: " + closed.behaviorObservation());
                    finish(helper, f);
                });
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "daily_enchant_main")
    public void enchantUsesTableXpLapisAndChangesItem(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-enchant");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.ENCHANTING_TABLE.defaultBlockState());
        f.body.setExperienceLevels(30);
        f.body.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 3));
        f.body.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        start(f, "enchant", new SkillParameters("EnchantItem", "minecraft:diamond_sword", 1, false,
                DIMENSION, station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND",
                "", 0, null, "ENCHANT", 0, ""));
        await(helper, f, 240, snapshot -> { ItemStack sword = f.body.getInventory().items.stream()
                    .filter(stack -> stack.is(Items.DIAMOND_SWORD)).findFirst().orElse(ItemStack.EMPTY);
            helper.assertTrue(sword.isEnchanted() && f.body.getInventory().countItem(Items.LAPIS_LAZULI) < 3,
                    "enchant did not consume lapis or apply to item");
            helper.assertTrue(Integer.parseInt(snapshot.enchant().getOrDefault("enchantLapisSpent", "0")) > 0,
                    "terminal enchant snapshot omitted lapis consumption");
            helper.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                    "completed enchant left the enchanting menu open");
            finish(helper, f); });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "daily_enchant_errors")
    public void enchantingRejectsMissingExperienceAndLapis(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-enchant-resources");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.ENCHANTING_TABLE.defaultBlockState());
        f.body.setExperienceLevels(0);
        f.body.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        f.body.getInventory().add(new ItemStack(Items.LAPIS_LAZULI));
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "enchant-missing-xp",
                        new SkillParameters("EnchantItem", "minecraft:diamond_sword", 1, false,
                                DIMENSION, station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND",
                                "", 0, null, "ENCHANT", 0, ""), 140,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "EXPERIENCE_INSUFFICIENT".equals(snapshot.behaviorObservation().failureCode()),
                                "enchant without experience was not rejected honestly: "
                                        + snapshot.behaviorObservation())),
                new Step(() -> { f.body.setExperienceLevels(30); f.body.getInventory().clearContent();
                    f.body.getInventory().add(new ItemStack(Items.DIAMOND_SWORD)); }, "enchant-missing-lapis",
                        new SkillParameters("EnchantItem", "minecraft:diamond_sword", 1, false,
                                DIMENSION, station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND",
                                "", 0, null, "ENCHANT", 0, ""), 140,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "LAPIS_INSUFFICIENT".equals(snapshot.behaviorObservation().failureCode()),
                                "enchant without lapis was not rejected honestly: "
                                        + snapshot.behaviorObservation()))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 650, batch = "daily_enchant_identity")
    public void enchantingStopsIfTheBoundStationIsReplaced(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-enchant-identity");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.ENCHANTING_TABLE.defaultBlockState());
        f.body.setExperienceLevels(30);
        f.body.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 3));
        f.body.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        start(f, "enchant-station-replaced", new SkillParameters("EnchantItem",
                "minecraft:diamond_sword", 1, false, DIMENSION, station.getX(), station.getY(),
                station.getZ(), "", "UP", "MAIN_HAND", "", 0, null, "ENCHANT", 0, ""));
        awaitRunningPhase(helper, f, "APPLY_ENCHANT", 240, () -> {
            f.body.serverLevel().setBlockAndUpdate(station, Blocks.AIR.defaultBlockState());
            await(helper, f, 120, terminal -> {
                helper.assertTrue("ENCHANTMENT_TARGET_INVALIDATED".equals(
                                terminal.behaviorObservation().failureCode()),
                        "replaced enchanting station was not invalidated: "
                                + terminal.behaviorObservation());
                helper.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                        "invalidated enchanting station left its menu open");
                helper.assertTrue(f.body.getInventory().countItem(Items.DIAMOND_SWORD) == 1,
                        "invalidated enchanting station did not return its input item");
                finish(helper, f);
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 900, batch = "daily_brew_main")
    public void brewingStandTicksAndProducesPotion(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-brew");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.getInventory().add(new ItemStack(Items.BLAZE_POWDER));
        f.body.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        f.body.getInventory().add(new ItemStack(Items.NETHER_WART));
        start(f, "brew", params("BrewPotion", "minecraft:nether_wart", "", "BREW", station));
        await(helper, f, 700, snapshot -> { boolean awkward = f.body.getInventory().items.stream()
                    .filter(stack -> stack.is(Items.POTION)).map(stack -> stack.get(DataComponents.POTION_CONTENTS))
                    .anyMatch(contents -> contents != null && contents.is(Potions.AWKWARD));
            helper.assertTrue(awkward, "brewing stand did not produce an awkward potion after real ticks");
            helper.assertTrue("1".equals(snapshot.brew().get("brewResultsTaken")),
                    "terminal brewing snapshot omitted the retrieved result");
            helper.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                    "completed brew left the brewing menu open");
            finish(helper, f); });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 800, batch = "daily_brew_errors")
    public void brewingRejectsMissingIngredientAndFuel(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-brew-resources");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        f.body.getInventory().add(new ItemStack(Items.BLAZE_POWDER));
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "brew-missing-ingredient",
                        params("BrewPotion", "minecraft:nether_wart", "", "BREW", station), 140,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "BREWING_MATERIALS_INSUFFICIENT".equals(
                                        snapshot.behaviorObservation().failureCode()),
                                "brew without ingredient was not rejected honestly: "
                                        + snapshot.behaviorObservation())),
                new Step(() -> { f.body.getInventory().clearContent();
                    f.body.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.WATER));
                    f.body.getInventory().add(new ItemStack(Items.NETHER_WART)); }, "brew-missing-fuel",
                        params("BrewPotion", "minecraft:nether_wart", "", "BREW", station), 140,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "BREWING_FUEL_MISSING".equals(
                                        snapshot.behaviorObservation().failureCode()),
                                "brew without fuel was not rejected honestly: "
                                        + snapshot.behaviorObservation()))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 3000, batch = "daily_fishing")
    public void fishingCastNaturalBiteReelAndLootOrHonestTimeout(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-fishing");
        BlockPos water = f.body.blockPosition().offset(1, 0, 0);
        // Keep the body on dry ground while the requested water target is a real open pool.
        for (int x = 0; x <= 4; x++) for (int z = -2; z <= 2; z++) {
            f.body.serverLevel().setBlockAndUpdate(water.offset(x, -2, z), Blocks.DIRT.defaultBlockState());
            f.body.serverLevel().setBlockAndUpdate(water.offset(x, -1, z), Blocks.WATER.defaultBlockState());
            f.body.serverLevel().setBlockAndUpdate(water.offset(x, 0, z), Blocks.WATER.defaultBlockState());
            f.body.serverLevel().setBlockAndUpdate(water.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            f.body.serverLevel().setBlockAndUpdate(water.offset(x, 2, z), Blocks.AIR.defaultBlockState());
        }
        f.body.getInventory().add(new ItemStack(Items.FISHING_ROD));
        int before = fishCount(f.body);
        int rodDamageBefore = fishingRodDamage(f.body);
        start(f, "fish", paramsDuration("Fish", "minecraft:fishing_rod", "", "FISH", water, 2000));
        await(helper, f, 2850, snapshot -> {
            String code = snapshot.behaviorObservation().failureCode();
            helper.assertTrue("VERIFIED".equals(code) || "DAILY_ACTION_TIMEOUT".equals(code),
                    "natural fishing returned neither a verified cycle nor its bounded timeout: " + code);
            if ("VERIFIED".equals(code)) helper.assertTrue(
                    fishCount(f.body) > before || fishingRodDamage(f.body) > rodDamageBefore
                            || f.body.fishing == null && !snapshot.behaviorObservation().details()
                            .getOrDefault("fishingHookId", "").isBlank(),
                    "fishing reported success without loot or a verified bite/reel result");
            else helper.assertTrue(f.body.fishing == null,
                    "timed-out fishing did not clean up its live hook");
            clearFishingPool(f, water);
            finish(helper, f); });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 800, batch = "daily_elytra_main")
    public void elytraEquipGlideLandAndUnsafeStartFailure(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-elytra");
        f.body.getInventory().add(new ItemStack(Items.ELYTRA));
        BlockPos target = f.body.blockPosition().offset(3, 4, 0);
        f.body.serverLevel().setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        BlockPos unsafe = f.body.blockPosition().offset(3, 4, 2);
        f.body.serverLevel().setBlockAndUpdate(unsafe, Blocks.LAVA.defaultBlockState());
        runSequence(helper, f, java.util.List.of(
                new Step(() -> { f.body.moveTo(f.body.getX(), f.body.getY() + 8, f.body.getZ(), 0, 0);
                    f.body.setOnGround(false); f.body.setDeltaMovement(0, -.1, 0); }, "glide",
                        params("GlideWithElytra", "minecraft:elytra", "", "GLIDE", target), 600,
                        snapshot -> helper.assertTrue("VERIFIED".equals(snapshot.behaviorObservation().failureCode())
                                        && f.body.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),
                                "elytra did not complete a verified controlled landing")),
                new Step(() -> { f.body.moveTo(f.body.getX(), f.body.getY() + 8, f.body.getZ(), 0, 0);
                    f.body.setOnGround(false); f.body.setDeltaMovement(0, -.1, 0); }, "unsafe-glide",
                        params("GlideWithElytra", "minecraft:elytra", "", "GLIDE", unsafe), 120,
                        snapshot -> { helper.assertTrue(snapshot.behaviorObservation().failureCode()
                                        .contains("GLIDE_TERRAIN_UNSAFE"),
                                "unsafe glide did not fail on observed terrain");
                            f.body.serverLevel().setBlockAndUpdate(unsafe, Blocks.AIR.defaultBlockState()); })
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700, batch = "daily_elytra_errors")
    public void elytraRejectsUnavailableAndUnsafeStart(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-elytra-negative");
        BlockPos target = f.body.blockPosition().offset(3, 4, 0);
        f.body.serverLevel().setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        runSequence(helper, f, java.util.List.of(
                new Step(() -> {}, "elytra-unavailable",
                        params("GlideWithElytra", "minecraft:elytra", "", "GLIDE", target), 140,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "ELYTRA_UNAVAILABLE".equals(snapshot.behaviorObservation().failureCode()),
                                "glide without an elytra was not rejected honestly: "
                                        + snapshot.behaviorObservation())),
                new Step(() -> { f.body.getInventory().clearContent();
                    f.body.getInventory().add(new ItemStack(Items.ELYTRA));
                    f.body.setOnGround(true); f.body.setDeltaMovement(Vec3.ZERO); }, "elytra-unsafe-start",
                        params("GlideWithElytra", "minecraft:elytra", "", "GLIDE", target), 180,
                        snapshot -> helper.assertTrue(snapshot.behaviorObservation() != null
                                        && "GLIDE_START_UNSAFE".equals(snapshot.behaviorObservation().failureCode()),
                                "grounded elytra start was not rejected honestly: "
                                        + snapshot.behaviorObservation()))
        ), 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500, batch = "daily_elytra_path")
    public void elytraRejectsHazardObservedAlongTheGlidePath(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-elytra-path");
        BlockPos origin = f.body.blockPosition();
        f.body.getInventory().add(new ItemStack(Items.ELYTRA));
        f.body.moveTo(f.body.getX(), f.body.getY() + 10.0D, f.body.getZ(), 0.0F, 0.0F);
        f.body.setOnGround(false);
        f.body.setDeltaMovement(0.0D, -0.1D, 0.0D);
        BlockPos target = origin.offset(8, 4, 0);
        f.body.serverLevel().setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        BlockPos pathHazard = origin.offset(4, 7, 0);
        f.body.serverLevel().setBlockAndUpdate(pathHazard, Blocks.LAVA.defaultBlockState());
        start(f, "elytra-path-hazard",
                params("GlideWithElytra", "minecraft:elytra", "", "GLIDE", target));
        await(helper, f, 180, snapshot -> {
            helper.assertTrue("GLIDE_TERRAIN_UNSAFE".equals(
                            snapshot.behaviorObservation().failureCode()),
                    "mid-path lava was not rejected before glide control: "
                            + snapshot.behaviorObservation());
            helper.assertTrue(!f.body.isFallFlying(),
                    "unsafe path started fall-flying before terrain validation");
            f.body.serverLevel().setBlockAndUpdate(pathHazard, Blocks.AIR.defaultBlockState());
            finish(helper, f);
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1500, batch = "daily_runtime_pause")
    public void runtimePauseAndResumePreservesMidBrewAction(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-runtime-pause");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        f.body.getInventory().add(new ItemStack(Items.NETHER_WART));
        f.body.getInventory().add(new ItemStack(Items.BLAZE_POWDER));
        start(f, "runtime-pause-brew", params("BrewPotion", "minecraft:nether_wart", "", "BREW", station));
        awaitRunningPhase(helper, f, "WAIT_BREW", 700, () -> {
            CompanionRegistry.RuntimeResult paused = f.registry.runtimePause(f.id, f.lease, 1L);
            helper.assertTrue(paused.success() && "PAUSED".equals(paused.state()),
                    "mid-action runtime pause failed: " + paused.code());
            helper.runAfterDelay(3, () -> {
                CompanionRegistry.RuntimeSnapshot pausedSnapshot = snapshot(f);
                helper.assertValueEqual(pausedSnapshot.behaviorState(), "PAUSED",
                        "paused daily action resumed without an explicit runtime resume");
                CompanionRegistry.RuntimeResult resumed = f.registry.runtimeResume(f.id, f.lease, 1L);
                helper.assertTrue(resumed.success() && "RUNNING".equals(resumed.state()),
                        "mid-action runtime resume failed: " + resumed.code());
                await(helper, f, 800, terminal -> {
                    helper.assertTrue("VERIFIED".equals(terminal.behaviorObservation().failureCode()),
                            "resumed brew did not reach a verified terminal state: "
                                    + terminal.behaviorObservation());
                    finish(helper, f);
                });
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000, batch = "daily_runtime_cancel")
    public void runtimeCancelCleansUpMidBrewWithoutDuplicateEffect(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-runtime-cancel");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        f.body.getInventory().add(new ItemStack(Items.NETHER_WART));
        f.body.getInventory().add(new ItemStack(Items.BLAZE_POWDER));
        int awkwardBefore = awkwardPotionCount(f.body);
        start(f, "runtime-cancel-brew", params("BrewPotion", "minecraft:nether_wart", "", "BREW", station));
        awaitRunningPhase(helper, f, "WAIT_BREW", 700, () -> {
            CompanionRegistry.RuntimeResult cancelled = f.registry.runtimeCancel(f.id, f.lease, 1L);
            helper.assertTrue(cancelled.success() && "CANCELLED".equals(cancelled.state()),
                    "mid-action runtime cancel failed: " + cancelled.code());
            helper.runAfterDelay(3, () -> {
                CompanionRegistry.RuntimeSnapshot terminal = snapshot(f);
                helper.assertValueEqual(terminal.behaviorState(), "IDLE",
                        "cancelled daily action did not return to IDLE");
                helper.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                        "runtime cancel left a brewing menu open");
                helper.assertValueEqual(awkwardPotionCount(f.body), awkwardBefore,
                        "runtime cancel produced a duplicate potion effect");
                finish(helper, f);
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000, batch = "daily_owner_supersede")
    public void ownerFollowSupersedesAndCleansTheRunningDailyAction(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-owner-supersede");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.body.serverLevel().setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        f.body.getInventory().add(new ItemStack(Items.NETHER_WART));
        f.body.getInventory().add(new ItemStack(Items.BLAZE_POWDER));
        start(f, "owner-supersede-brew",
                params("BrewPotion", "minecraft:nether_wart", "", "BREW", station));
        awaitRunningPhase(helper, f, "WAIT_BREW", 700, () -> {
            CompanionRegistry.Result followed = f.registry.follow(f.owner);
            helper.assertTrue(followed.success(), "owner follow did not supersede daily action: " + followed.code());
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                        "owner supersede left the daily brewing menu open");
                helper.assertTrue(f.registry.status(f.owner).contains("mode=FOLLOW"),
                        "owner supersede did not install the follow behavior");
                helper.assertTrue(f.registry.stop(f.owner).success(),
                        "owner supersede cleanup could not stop follow");
                finish(helper, f);
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 250, batch = "daily_runtime_invalid")
    public void invalidDailyStartDoesNotLeaveHalfStartedSkillState(GameTestHelper helper) {
        Fixture f = fixture(helper, "daily-invalid-start");
        SkillParameters missingDestination = new SkillParameters("UseVehicle", "", 1, false,
                DIMENSION, null, null, null, "", "UP", "MAIN_HAND", "",
                null, null, "TRAVEL", 0, "");
        CompanionRegistry.RuntimeResult rejected = f.registry.runtimeStart(f.id, f.lease, 1L,
                "invalid-daily-start", "skill", null, null, null, missingDestination);
        helper.assertTrue(!rejected.success() && "INVALID_SKILL_PARAMETERS".equals(rejected.code()),
                "invalid daily request was not rejected before state mutation: " + rejected);
        CompanionRegistry.RuntimeSnapshot state = snapshot(f);
        helper.assertTrue("IDLE".equals(state.behaviorState()) && state.behaviorId() == null,
                "invalid daily request left a half-started behavior: " + state);
        finish(helper, f);
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        CompanionRegistry registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(registry.create(owner, name).success(), name + " companion create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        helper.assertTrue(body != null, name + " body missing");
        String id = body.getUUID().toString();
        String lease = name + "-lease";
        helper.assertTrue(registry.runtimeAcquireLease(id, lease, 1L, System.currentTimeMillis() + 180_000L).success(),
                name + " lease acquisition failed");
        return new Fixture(registry, owner, body, id, lease);
    }

    private static void start(Fixture f, String id, SkillParameters p) {
        if (!f.registry.runtimeStart(f.id, f.lease, 1L, id, "skill", null, null, null, p).success())
            throw new AssertionError("daily action failed to start: " + id);
    }

    private static SkillParameters params(String capability, String item, String hand, String action, BlockPos pos) {
        return new SkillParameters(capability, item, 1, false, DIMENSION,
                pos == null ? null : pos.getX(), pos == null ? null : pos.getY(), pos == null ? null : pos.getZ(),
                "", "UP", hand, "", null, null, action, 0, "");
    }

    private static SkillParameters params2(String capability, String item, String first, String second,
                                           String action, BlockPos pos) {
        SkillParameters base = params(capability, item, "MAIN_HAND", action, pos);
        return new SkillParameters(capability, item, 1, false, DIMENSION, base.x(), base.y(), base.z(),
                first, "UP", "MAIN_HAND", "", null, null, action, 0, second);
    }

    private static Cow cow(Fixture f, int offset) {
        Cow cow = EntityType.COW.create(f.body.serverLevel());
        if (cow == null) throw new AssertionError("cow fixture failed");
        cow.setPos(f.body.getX() + offset, f.body.getY(), f.body.getZ());
        f.body.serverLevel().addFreshEntity(cow);
        return cow;
    }

    private static void placeBed(Fixture fixture, BlockPos foot, boolean occupied) {
        BlockPos head = foot.north();
        fixture.body.serverLevel().setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, BedPart.FOOT).setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.OCCUPIED, occupied));
        fixture.body.serverLevel().setBlockAndUpdate(head, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, BedPart.HEAD).setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.OCCUPIED, occupied));
    }

    private static int fishCount(CompanionPlayer body) {
        return body.getInventory().countItem(Items.COD) + body.getInventory().countItem(Items.SALMON)
                + body.getInventory().countItem(Items.TROPICAL_FISH)
                + body.getInventory().countItem(Items.PUFFERFISH);
    }

    private static int fishingRodDamage(CompanionPlayer body) {
        int damage = 0;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (stack.is(Items.FISHING_ROD)) damage = Math.max(damage, stack.getDamageValue());
        }
        return damage;
    }

    private static void clearFishingPool(Fixture f, BlockPos water) {
        for (int x = 0; x <= 4; x++) for (int z = -2; z <= 2; z++) {
            f.body.serverLevel().setBlockAndUpdate(water.offset(x, 0, z), Blocks.AIR.defaultBlockState());
            f.body.serverLevel().setBlockAndUpdate(
                    water.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            f.body.serverLevel().setBlockAndUpdate(
                    water.offset(x, -2, z), Blocks.STONE.defaultBlockState());
        }
    }

    private static int awkwardPotionCount(CompanionPlayer body) {
        int count = 0;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.is(Potions.AWKWARD)) count += stack.getCount();
        }
        return count;
    }

    private static CompanionRegistry.RuntimeSnapshot snapshot(Fixture f) {
        return f.registry.runtimeSnapshots(false).stream()
                .filter(value -> value.companionId().equals(f.id)).findFirst().orElseThrow();
    }

    private static void awaitRunningPhase(GameTestHelper helper, Fixture f, String phase,
                                          int remaining, Runnable action) {
        CompanionRegistry.RuntimeSnapshot current = snapshot(f);
        String currentPhase = current.behaviorObservation() == null ? ""
                : current.behaviorObservation().details().getOrDefault("phase", "");
        if ("RUNNING".equals(current.behaviorState()) && phase.equals(currentPhase)) {
            action.run();
            return;
        }
        helper.assertTrue(remaining > 0,
                "daily action did not reach mid-action phase " + phase + ": " + current.evidenceSummary());
        helper.runAfterDelay(1, () -> awaitRunningPhase(helper, f, phase, remaining - 1, action));
    }

    private static void awaitSleepingWhileRunning(GameTestHelper helper, Fixture f, int remaining,
                                                  Runnable action) {
        CompanionRegistry.RuntimeSnapshot current = snapshot(f);
        if (f.body.isSleeping() && "RUNNING".equals(current.behaviorState())) {
            action.run();
            return;
        }
        helper.assertTrue(remaining > 0 && "RUNNING".equals(current.behaviorState()),
                "sleep did not reach cancellable state: code="
                        + (current.behaviorObservation() == null ? "" : current.behaviorObservation().failureCode())
                        + " phase=" + (current.behaviorObservation() == null ? ""
                        : current.behaviorObservation().details().getOrDefault("phase", "")));
        helper.runAfterDelay(1, () -> awaitSleepingWhileRunning(helper, f, remaining - 1, action));
    }

    private static void await(GameTestHelper helper, Fixture f, int remaining,
                              java.util.function.Consumer<CompanionRegistry.RuntimeSnapshot> assertion) {
        CompanionRegistry.RuntimeSnapshot snapshot = snapshot(f);
        if ("RUNNING".equals(snapshot.behaviorState())) {
            helper.assertTrue(remaining > 0, "daily action timed out: " + snapshot.evidenceSummary()
                    + " code=" + (snapshot.behaviorObservation() == null ? "" : snapshot.behaviorObservation().failureCode())
                    + " phase=" + (snapshot.behaviorObservation() == null ? ""
                    : snapshot.behaviorObservation().details().getOrDefault("phase", ""))
                    + " menu=" + f.body.containerMenu.getClass().getSimpleName()
                    + " pos=" + f.body.blockPosition());
            helper.runAfterDelay(1, () -> await(helper, f, remaining - 1, assertion));
            return;
        }
        helper.assertValueEqual(snapshot.behaviorState(), "IDLE",
                "daily action did not reach a terminal idle state: " + snapshot.behaviorObservation());
        helper.assertTrue(snapshot.behaviorObservation() != null,
                "terminal daily action had no verified observation");
        assertion.accept(snapshot);
    }

    private static void runSequence(GameTestHelper helper, Fixture fixture,
                                    java.util.List<Step> steps, int index) {
        if (index >= steps.size()) { finish(helper, fixture); return; }
        Step step = steps.get(index);
        // Give newly spawned entities (boats, animals, villagers, beds) one server
        // tick before the first vanilla interaction. This is still the same live
        // runtime path; it only avoids racing entity insertion with the command.
        helper.runAfterDelay(1, () -> {
            step.before().run();
            start(fixture, step.id(), step.parameters());
            await(helper, fixture, step.timeout(), snapshot -> {
                step.assertion().accept(snapshot);
                runSequence(helper, fixture, steps, index + 1);
            });
        });
    }

    private static SkillParameters paramsDuration(String capability, String item, String hand,
                                                   String action, BlockPos pos, int durationTicks) {
        SkillParameters base = params(capability, item, hand, action, pos);
        return new SkillParameters(capability, item, base.quantity(), base.allowPartial(), base.dimension(),
                base.x(), base.y(), base.z(), base.targetId(), base.face(), base.hand(), base.sessionToken(),
                base.slot(), base.button(), base.menuAction(), durationTicks, base.secondaryTargetId());
    }

    private static void finish(GameTestHelper helper, Fixture f) {
        helper.assertTrue(f.registry.remove(f.owner).success(), "daily action fixture cleanup failed");
        helper.getLevel().getServer().getPlayerList().remove(f.owner);
        helper.succeed();
    }

    private record Fixture(CompanionRegistry registry, ServerPlayer owner, CompanionPlayer body,
                           String id, String lease) { }

    private record Step(Runnable before, String id, SkillParameters parameters, int timeout,
                        java.util.function.Consumer<CompanionRegistry.RuntimeSnapshot> assertion) { }
}
