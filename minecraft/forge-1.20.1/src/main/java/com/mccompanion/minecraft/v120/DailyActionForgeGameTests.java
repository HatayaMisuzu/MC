package com.mccompanion.minecraft.v120;

import com.mojang.authlib.GameProfile;
import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real Forge 1.20.1 acceptance coverage for the shared daily-action boundary. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DailyActionForgeGameTests {
    private DailyActionForgeGameTests() { }

    @GameTest(batch = "dailyEquipment", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 600)
    public static void equipmentEquipBestToolBestWeaponUnequipAndRejectDamaged(GameTestHelper h) {
        Fixture f = fixture(h, "daily-equipment");
        f.body.addItem(new ItemStack(Items.IRON_HELMET));
        f.body.addItem(new ItemStack(Items.WOODEN_PICKAXE));
        f.body.addItem(new ItemStack(Items.IRON_PICKAXE));
        f.body.addItem(new ItemStack(Items.STONE_SWORD));
        start(h, f, new SkillParameters("EquipItem", "minecraft:iron_helmet", 1, false,
                f.dimension(), null, null, null, "", "UP", "HEAD", "", null, null, "EQUIP", null));
        await(h, f, 120, snapshot -> {
            h.assertTrue(f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                    "vanilla armor slot was not equipped");
            ItemStack worn = f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            worn.setDamageValue(worn.getMaxDamage() - 1);
            start(h, f, new SkillParameters("EquipItem", "minecraft:iron_helmet", 1, false,
                    f.dimension(), null, null, null, "", "UP", "HEAD", "", null, null, "EQUIP", null));
            await(h, f, 120, wornDamaged -> {
                h.assertTrue("EQUIPMENT_UNUSABLE".equals(wornDamaged.behaviorObservation().failureCode()),
                        "already worn damaged armor was incorrectly accepted");
                start(h, f, new SkillParameters("EquipItem", "minecraft:stone", 1, false,
                        f.dimension(), null, null, null, "", "UP", "MAIN_HAND", "", null, null, "BEST_TOOL", null));
                await(h, f, 120, bestTool -> {
                h.assertTrue(f.body.getMainHandItem().is(Items.IRON_PICKAXE), "best usable pickaxe was not selected");
                start(h, f, new SkillParameters("EquipItem", "", 1, false,
                        f.dimension(), null, null, null, "", "UP", "MAIN_HAND", "", null, null,
                        "BEST_WEAPON_MELEE", null));
                await(h, f, 120, bestWeapon -> {
                    h.assertTrue(f.body.getMainHandItem().is(Items.STONE_SWORD), "best melee weapon was not selected");
                    start(h, f, new SkillParameters("EquipItem", "", 1, false,
                            f.dimension(), null, null, null, "", "UP", "HEAD", "", null, null, "UNEQUIP", null));
                    await(h, f, 120, unequipped -> {
                        h.assertTrue(f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty(),
                                "armor slot was not emptied through vanilla inventory operations");
                        f.body.getInventory().clearContent();
                        ItemStack damaged = new ItemStack(Items.WOODEN_PICKAXE);
                        damaged.setDamageValue(damaged.getMaxDamage() - 1);
                        f.body.addItem(damaged);
                        start(h, f, new SkillParameters("EquipItem", "minecraft:stone", 1, false,
                                f.dimension(), null, null, null, "", "UP", "MAIN_HAND", "", null, null, "BEST_TOOL", null));
                        await(h, f, 120, damagedResult -> {
                            h.assertTrue(!damagedResult.behaviorObservation().failureCode().equals("VERIFIED"),
                                    "nearly broken tool was incorrectly reported as verified equipment");
                            finish(h, f);
                        });
                    });
                });
                });
            });
        });
    }

    @GameTest(batch = "dailyEquipment", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 450)
    public static void equipmentSupportsOffhandAndRejectsMissingItem(GameTestHelper h) {
        Fixture f = fixture(h, "daily-equipment-offhand");
        f.body.addItem(new ItemStack(Items.SHIELD));
        start(h, f, new SkillParameters("EquipItem", "minecraft:shield", 1, false,
                f.dimension(), null, null, null, "", "UP", "OFF_HAND", "", null, null, "EQUIP", null));
        await(h, f, 120, equipped -> {
            h.assertTrue(f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).is(Items.SHIELD),
                    "offhand item was not equipped through the real inventory path");
            start(h, f, new SkillParameters("EquipItem", "", 1, false,
                    f.dimension(), null, null, null, "", "UP", "OFF_HAND", "", null, null, "UNEQUIP", null));
            await(h, f, 120, unequipped -> {
                h.assertTrue(f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).isEmpty(),
                        "offhand shield was not unequipped");
                start(h, f, new SkillParameters("EquipItem", "minecraft:shield", 1, false,
                        f.dimension(), null, null, null, "", "UP", "AUTO", "", null, null, "EQUIP", null));
                await(h, f, 120, automatic -> {
                    h.assertTrue(f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).is(Items.SHIELD),
                            "AUTO equipment did not place a shield in the offhand");
                    f.body.getInventory().clearContent();
                    start(h, f, new SkillParameters("EquipItem", "minecraft:shield", 1, false,
                            f.dimension(), null, null, null, "", "UP", "OFF_HAND", "", null, null, "EQUIP", null));
                    await(h, f, 120, missing -> {
                        h.assertTrue("ITEM_NOT_FOUND".equals(missing.behaviorObservation().failureCode()),
                                "missing offhand item was not rejected honestly: " + missing.behaviorObservation());
                        finish(h, f);
                    });
                });
            });
        });
    }

    @GameTest(batch = "dailySleepCancel", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 800)
    public static void nearbyBedNavigationCanBeCancelledWithoutLeavingTheBodyAsleep(GameTestHelper h) {
        Fixture f = fixture(h, "daily-sleep-cancel");
        f.body.moveTo(512.5D, 80.0D, 512.5D, 0.0F, 0.0F);
        f.owner.moveTo(512.5D, 80.0D, 510.5D, 0.0F, 0.0F);
        BlockPos origin = f.body.blockPosition();
        BlockPos bed = origin.offset(5, 0, 0);
        for (int x = -2; x <= 7; x++) for (int z = -2; z <= 2; z++) {
            f.level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y <= 2; y++) {
                f.level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        f.level.setDayTime(13000L);
        placeBed(f, bed, false);
        var approachPlan = new SurvivalNavigationAdapter().plan(
                f.body, Vec3.atBottomCenterOf(bed.west()));
        h.assertTrue(approachPlan.status()
                        == com.mccompanion.core.navigation.GridPathPlanner.Status.READY,
                "nearby-bed fixture has no real walkable approach: " + approachPlan.status());
        start(h, f, new SkillParameters("SleepAtBed", "", 8, false, f.dimension(),
                null, null, null, "", "UP", "MAIN_HAND", "", null, null, "SLEEP", null));
        awaitSleepingWhileRunning(h, f, 350, () -> {
            h.assertTrue(f.body.blockPosition().distSqr(origin) > 1.0D,
                    "nearby-bed sleep did not navigate toward the selected bed");
            CompanionRegistry.RuntimeResult cancelled = f.registry.runtimeCancel(
                    f.companionId, f.lease, 1L);
            h.assertTrue(cancelled.success() && "CANCELLED".equals(cancelled.state()),
                    "sleep cancellation failed: " + cancelled.code());
            h.runAfterDelay(1, () -> {
                h.assertTrue(!f.body.isSleeping(), "cancelled sleep left the ServerPlayer asleep");
                h.assertTrue("IDLE".equals(runtimeSnapshot(f).behaviorState()),
                        "cancelled sleep did not return to IDLE");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailySleep", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 800)
    public static void sleepWakeAndRejectDaytimeOrOccupiedBed(GameTestHelper h) {
        Fixture f = fixture(h, "daily-sleep");
        BlockPos bed = f.body.blockPosition().offset(2, 0, 0);
        placeBed(f, bed, false);
        start(h, f, new SkillParameters("SleepAtBed", "", 4, false, f.dimension(),
                bed.getX(), bed.getY(), bed.getZ(), "", "UP", "MAIN_HAND", "", null, null, "SLEEP", null));
        await(h, f, 120, daytime -> {
            h.assertTrue(!f.body.isSleeping() && daytime.behaviorObservation().failureCode().contains("BED"),
                    "daytime sleep did not produce a verified bed failure");
            f.level.setDayTime(13000L);
            f.level.setBlockAndUpdate(bed, f.level.getBlockState(bed).setValue(BedBlock.OCCUPIED, true));
            start(h, f, new SkillParameters("SleepAtBed", "", 4, false, f.dimension(),
                    bed.getX(), bed.getY(), bed.getZ(), "", "UP", "MAIN_HAND", "", null, null, "SLEEP", null));
            await(h, f, 120, occupied -> {
                h.assertTrue(!f.body.isSleeping() && occupied.behaviorObservation().failureCode().contains("BED"),
                        "occupied bed did not produce a verified vanilla failure");
                f.level.setBlockAndUpdate(bed, f.level.getBlockState(bed).setValue(BedBlock.OCCUPIED, false));
                start(h, f, new SkillParameters("SleepAtBed", "", 4, false, f.dimension(),
                        bed.getX(), bed.getY(), bed.getZ(), "", "UP", "MAIN_HAND", "", null, null, "SLEEP", null));
                await(h, f, 120, sleeping -> {
                    h.assertTrue(f.body.isSleeping(), "night-time bed use did not enter vanilla sleeping state");
                    start(h, f, new SkillParameters("SleepAtBed", "", 1, false, f.dimension(),
                            null, null, null, "", "UP", "MAIN_HAND", "", null, null, "WAKE", null));
                    await(h, f, 120, awake -> {
                        h.assertTrue(!f.body.isSleeping(), "wake action left the real body asleep");
                        finish(h, f);
                    });
                });
            });
        });
    }

    @GameTest(batch = "dailyBucket", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 600)
    public static void bucketFillsAndPlacesRealWater(GameTestHelper h) {
        Fixture f = fixture(h, "daily-bucket");
        BlockPos source = f.body.blockPosition().offset(2, 0, 0);
        BlockPos target = source.east();
        f.level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        f.body.addItem(new ItemStack(Items.BUCKET));
        int before = f.body.getInventory().countItem(Items.WATER_BUCKET);
        start(h, f, new SkillParameters("UseWaterBucket", "minecraft:bucket", 1, false, f.dimension(),
                source.getX(), source.getY(), source.getZ(), "", "UP", "MAIN_HAND", "", null, null, "FILL", null));
        await(h, f, 120, filled -> {
            h.assertTrue(f.body.getInventory().countItem(Items.WATER_BUCKET) > before,
                    "fill did not create a real water bucket");
            f.level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
            f.level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            start(h, f, new SkillParameters("UseWaterBucket", "minecraft:water_bucket", 1, false, f.dimension(),
                    target.getX(), target.getY(), target.getZ(), "", "UP", "MAIN_HAND", "", null, null, "EMPTY", null));
            await(h, f, 120, emptied -> {
                h.assertTrue(f.level.getFluidState(target).is(net.minecraft.tags.FluidTags.WATER),
                        "empty did not place a live water source");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyBucket", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 300)
    public static void bucketRejectsFillWithoutBucket(GameTestHelper h) {
        Fixture f = fixture(h, "daily-bucket-missing");
        BlockPos source = f.body.blockPosition().offset(2, 0, 0);
        f.level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        start(h, f, new SkillParameters("UseWaterBucket", "minecraft:bucket", 1, false, f.dimension(),
                source.getX(), source.getY(), source.getZ(), "", "UP", "MAIN_HAND", "", null, null, "FILL", null));
        await(h, f, 120, missing -> {
            h.assertTrue("BUCKET_MISSING".equals(missing.behaviorObservation().failureCode()),
                    "fill without a bucket was not rejected honestly: " + missing.behaviorObservation());
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyVehicles", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1200)
    public static void boatTravelDismountAndMinecartMountDismount(GameTestHelper h) {
        Fixture f = fixture(h, "daily-vehicles");
        BlockPos water = f.body.blockPosition().offset(2, 0, 0);
        for (int x = -1; x <= 3; x++) for (int z = -1; z <= 1; z++)
            f.level.setBlockAndUpdate(water.offset(x, 0, z), Blocks.WATER.defaultBlockState());
        Boat boat = EntityType.BOAT.create(f.level);
        h.assertTrue(boat != null, "boat fixture could not be created");
        boat.moveTo(f.body.getX() + 1, f.body.getY() + 1, f.body.getZ(), 0, 0);
        f.level.addFreshEntity(boat);
        String boatType = BuiltInRegistries.ENTITY_TYPE.getKey(boat.getType()).toString();
        start(h, f, new SkillParameters("UseVehicle", boatType, 1, false, f.dimension(),
                null, null, null, boat.getUUID().toString(), "UP", "MAIN_HAND", "", null, null, "MOUNT", null));
        await(h, f, 120, mountedBoat -> {
            h.assertTrue(f.body.getVehicle() == boat, "boat was not mounted through entity interaction");
            BlockPos destination = water.offset(2, 0, 0);
            start(h, f, new SkillParameters("UseVehicle", boatType, 1, false, f.dimension(),
                    destination.getX(), destination.getY(), destination.getZ(), boat.getUUID().toString(), "UP", "MAIN_HAND", "", null, null, "TRAVEL", null));
            await(h, f, 240, traveled -> {
                h.assertTrue(boat.position().distanceToSqr(Vec3.atCenterOf(destination)) < 36,
                        "boat travel did not move toward the requested destination");
                start(h, f, new SkillParameters("UseVehicle", "", 1, false, f.dimension(), null, null, null,
                        boat.getUUID().toString(), "UP", "MAIN_HAND", "", null, null, "DISMOUNT", null));
                await(h, f, 120, dismountedBoat -> {
                    h.assertTrue(f.body.getVehicle() == null, "boat was not dismounted");
                    AbstractMinecart cart = EntityType.MINECART.create(f.level);
                    h.assertTrue(cart != null, "minecart fixture could not be created");
                    BlockPos rail = f.body.blockPosition().offset(1, 0, 0);
                    f.level.setBlockAndUpdate(rail.below(), Blocks.STONE.defaultBlockState());
                    f.level.setBlockAndUpdate(rail, Blocks.RAIL.defaultBlockState());
                    cart.moveTo(rail.getX() + .5D, rail.getY() + .1D, rail.getZ() + .5D, 0, 0);
                    f.level.addFreshEntity(cart);
                    String cartType = BuiltInRegistries.ENTITY_TYPE.getKey(cart.getType()).toString();
                    start(h, f, new SkillParameters("UseVehicle", cartType, 1, false, f.dimension(), null, null, null,
                            cart.getUUID().toString(), "UP", "MAIN_HAND", "", null, null, "MOUNT", null));
                    await(h, f, 120, mountedCart -> {
                        h.assertTrue(f.body.getVehicle() == cart, "minecart was not mounted");
                        start(h, f, new SkillParameters("UseVehicle", "", 1, false, f.dimension(), null, null, null,
                                cart.getUUID().toString(), "UP", "MAIN_HAND", "", null, null, "DISMOUNT", null));
                        await(h, f, 120, dismountedCart -> {
                            h.assertTrue(f.body.getVehicle() == null, "minecart was not dismounted");
                            finish(h, f);
                        });
                    });
                });
            });
        });
    }

    @GameTest(batch = "dailyVehiclesFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 800)
    public static void vehicleDestroyedDuringTravelExitsSafely(GameTestHelper h) {
        Fixture f = fixture(h, "daily-vehicle-destroyed");
        BlockPos water = f.body.blockPosition().offset(2, 0, 0);
        for (int x = -1; x <= 4; x++) for (int z = -1; z <= 1; z++)
            f.level.setBlockAndUpdate(water.offset(x, 0, z), Blocks.WATER.defaultBlockState());
        Boat boat = EntityType.BOAT.create(f.level);
        h.assertTrue(boat != null, "vehicle loss fixture could not create a boat");
        boat.moveTo(f.body.getX() + 1, f.body.getY() + 1, f.body.getZ(), 0, 0);
        f.level.addFreshEntity(boat);
        String boatType = BuiltInRegistries.ENTITY_TYPE.getKey(boat.getType()).toString();
        BlockPos destination = water.offset(4, 0, 0);
        start(h, f, new SkillParameters("UseVehicle", boatType, 1, false, f.dimension(),
                destination.getX(), destination.getY(), destination.getZ(), boat.getUUID().toString(),
                "UP", "MAIN_HAND", "", null, null, "TRAVEL", null));
        awaitPhase(h, f, "TRAVEL_VEHICLE", 360, travelling -> {
            h.assertTrue(f.body.getVehicle() == boat, "travel phase did not retain the real boat passenger");
            boat.discard();
            await(h, f, 120, lost -> {
                h.assertTrue("VEHICLE_LOST".equals(lost.behaviorObservation().failureCode()),
                        "destroyed vehicle did not fail with VEHICLE_LOST: " + lost.behaviorObservation());
                h.assertTrue(f.body.getVehicle() == null, "destroyed vehicle left the body riding after cleanup");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyVehiclesFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 900)
    public static void stationaryMinecartReportsVehicleStuckAndDismounts(GameTestHelper h) {
        Fixture f = fixture(h, "daily-vehicle-stuck");
        BlockPos rail = f.body.blockPosition().offset(1, 0, 0);
        for (int x = 0; x <= 4; x++) {
            f.level.setBlockAndUpdate(rail.offset(x, -1, 0), Blocks.STONE.defaultBlockState());
            f.level.setBlockAndUpdate(rail.offset(x, 0, 0), Blocks.RAIL.defaultBlockState());
        }
        AbstractMinecart cart = EntityType.MINECART.create(f.level);
        h.assertTrue(cart != null, "vehicle stuck fixture could not create a minecart");
        cart.moveTo(rail.getX() + .5D, rail.getY() + .1D, rail.getZ() + .5D, 0, 0);
        f.level.addFreshEntity(cart);
        String cartType = BuiltInRegistries.ENTITY_TYPE.getKey(cart.getType()).toString();
        BlockPos destination = rail.offset(4, 0, 0);
        start(h, f, new SkillParameters("UseVehicle", cartType, 1, false, f.dimension(),
                destination.getX(), destination.getY(), destination.getZ(), cart.getUUID().toString(),
                "UP", "MAIN_HAND", "", null, null, "TRAVEL", null));
        await(h, f, 320, stuck -> {
            h.assertTrue("VEHICLE_STUCK".equals(stuck.behaviorObservation().failureCode()),
                    "stationary minecart did not produce a safe VEHICLE_STUCK exit: " + stuck.behaviorObservation());
            h.assertTrue(f.body.getVehicle() == null, "stuck vehicle cleanup did not dismount the body");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyFarming", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 900)
    public static void matureCropHarvestPickupReplantAndSeedFailure(GameTestHelper h) {
        Fixture f = fixture(h, "daily-farming");
        BlockPos crop = f.body.blockPosition().offset(1, 0, 0);
        f.level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
        f.level.setBlockAndUpdate(crop.below().offset(0, 0, 3), Blocks.WATER.defaultBlockState());
        f.level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
        f.body.addItem(new ItemStack(Items.WHEAT_SEEDS, 2));
        int before = f.body.getInventory().countItem(Items.WHEAT);
        start(h, f, new SkillParameters("FarmCrop", "minecraft:wheat", 1, false, f.dimension(),
                crop.getX(), crop.getY(), crop.getZ(), "", "UP", "MAIN_HAND", "", null, 4, "HARVEST_REPLANT", null));
        await(h, f, 240, harvested -> {
            h.assertTrue(f.body.getInventory().countItem(Items.WHEAT) > before,
                    "mature wheat was not harvested and picked up");
            h.assertTrue(f.level.getBlockState(crop).getBlock() == Blocks.WHEAT
                            && f.level.getBlockState(crop).getValue(BlockStateProperties.AGE_7) == 0,
                    "crop was not replanted at age zero");
            h.assertTrue("true".equals(harvested.crop().get("cropReplanted"))
                            && Integer.parseInt(harvested.crop().getOrDefault("cropSeedConsumed", "0")) > 0,
                    "terminal crop snapshot omitted replant/seed evidence");
            f.body.getInventory().clearContent();
            f.level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
            start(h, f, new SkillParameters("FarmCrop", "minecraft:wheat", 1, false, f.dimension(),
                    crop.getX(), crop.getY(), crop.getZ(), "", "UP", "MAIN_HAND", "", null, 4, "HARVEST_REPLANT", null));
            discardSeedDrops(h, f, 40);
            await(h, f, 120, noSeed -> {
                h.assertTrue(noSeed.behaviorObservation().failureCode().equals("SEED_MISSING"),
                        "missing seed was not reported by the body");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyFarmingFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 750)
    public static void farmingGroundDestroyedBeforeReplantExitsUncertain(GameTestHelper h) {
        Fixture f = fixture(h, "daily-farming-ground-lost");
        BlockPos crop = f.body.blockPosition().offset(1, 0, 0);
        f.level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
        f.level.setBlockAndUpdate(crop.below().offset(0, 0, 3), Blocks.WATER.defaultBlockState());
        f.level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
        f.body.addItem(new ItemStack(Items.WHEAT_SEEDS, 2));
        start(h, f, new SkillParameters("FarmCrop", "minecraft:wheat", 1, false, f.dimension(),
                crop.getX(), crop.getY(), crop.getZ(), "", "UP", "MAIN_HAND", "", null, 4,
                "HARVEST_REPLANT", null));
        awaitPhase(h, f, "PICKUP_CROP", 360, pickingUp -> {
            f.level.setBlockAndUpdate(crop.below(), Blocks.AIR.defaultBlockState());
            await(h, f, 160, failed -> {
                String code = failed.behaviorObservation().failureCode();
                h.assertTrue("FARMLAND_INVALID".equals(code),
                        "destroyed farming ground was not reported as an honest replant failure: "
                                + failed.behaviorObservation());
                h.assertTrue(f.level.getBlockState(crop).isAir(),
                        "farming action replanted onto destroyed ground");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyFarmingLifecycle", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1300)
    public static void farmingPausesAndResumesOnTheSameLiveAction(GameTestHelper h) {
        Fixture f = fixture(h, "daily-farming-pause-resume");
        BlockPos crop = f.body.blockPosition().offset(1, 0, 0);
        f.level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
        f.level.setBlockAndUpdate(crop.below().offset(0, 0, 3), Blocks.WATER.defaultBlockState());
        f.level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
        f.body.addItem(new ItemStack(Items.WHEAT_SEEDS, 2));
        int wheatBefore = f.body.getInventory().countItem(Items.WHEAT);
        start(h, f, new SkillParameters("FarmCrop", "minecraft:wheat", 1, false, f.dimension(),
                crop.getX(), crop.getY(), crop.getZ(), "", "UP", "MAIN_HAND", "", null, 4,
                "HARVEST_REPLANT", null));
        awaitPhase(h, f, "PICKUP_CROP", 420, navigating -> {
            Vec3 pausedAt = f.body.position();
            CompanionRegistry.RuntimeResult paused = f.registry.runtimePause(f.companionId, f.lease, 1L);
            h.assertTrue(paused.success() && "PAUSED".equals(paused.state()),
                    "mid-action runtime pause was not acknowledged: " + paused.code());
            h.runAfterDelay(12, () -> {
                CompanionRegistry.RuntimeSnapshot pausedSnapshot = runtimeSnapshot(f);
                h.assertTrue("PAUSED".equals(pausedSnapshot.behaviorState()),
                        "runtime pause did not retain PAUSED state during the tick window");
                h.assertTrue(f.body.position().distanceToSqr(pausedAt) < 4.0D
                                && Math.abs(f.body.xxa) < 0.001F && Math.abs(f.body.zza) < 0.001F,
                        "paused farming action retained movement input or drifted too far: distanceSquared="
                                + f.body.position().distanceToSqr(pausedAt));
                CompanionRegistry.RuntimeResult resumed = f.registry.runtimeResume(f.companionId, f.lease, 1L);
                h.assertTrue(resumed.success() && "RUNNING".equals(resumed.state()),
                        "mid-action runtime resume was not acknowledged: " + resumed.code());
                await(h, f, 700, completed -> {
                    h.assertTrue("VERIFIED".equals(completed.behaviorObservation().failureCode()),
                            "paused/resumed farming did not complete honestly: " + completed.behaviorObservation());
                    h.assertTrue(f.body.getInventory().countItem(Items.WHEAT) > wheatBefore
                                    && f.level.getBlockState(crop).getBlock() == Blocks.WHEAT
                                    && f.level.getBlockState(crop).getValue(BlockStateProperties.AGE_7) == 0,
                            "paused/resumed farming did not produce exactly the real harvest/replant result");
                    finish(h, f);
                });
            });
        });
    }

    @GameTest(batch = "dailyBreed", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1000)
    public static void twoCowsAreFedAndProduceBaby(GameTestHelper h) {
        Fixture f = fixture(h, "daily-breed");
        Cow first = EntityType.COW.create(f.level), second = EntityType.COW.create(f.level);
        h.assertTrue(first != null && second != null, "cow fixtures could not be created");
        first.moveTo(f.body.getX() + 1, f.body.getY(), f.body.getZ(), 0, 0);
        second.moveTo(f.body.getX() + 2, f.body.getY(), f.body.getZ(), 0, 0);
        BlockPos pen = f.body.blockPosition();
        for (int x = -1; x <= 3; x++) for (int z = -2; z <= 2; z++) {
            if (x == -1 || x == 3 || z == -2 || z == 2) {
                f.level.setBlockAndUpdate(pen.offset(x, 0, z), Blocks.OAK_FENCE.defaultBlockState());
            }
        }
        f.level.addFreshEntity(first); f.level.addFreshEntity(second);
        f.body.getInventory().setItem(0, new ItemStack(Items.WHEAT, 2));
        int babies = f.level.getEntitiesOfClass(Cow.class, f.body.getBoundingBox().inflate(8), Cow::isBaby).size();
        start(h, f, new SkillParameters("BreedAnimals", "", 1, false, f.dimension(), null, null, null,
                first.getUUID().toString(), "UP", "MAIN_HAND", "", null, null, "BREED", null, second.getUUID().toString()));
        await(h, f, 240, bred -> {
            h.assertTrue(f.level.getEntitiesOfClass(Cow.class, f.body.getBoundingBox().inflate(8), Cow::isBaby).size() > babies,
                    "two real cows did not produce a baby");
            h.assertTrue(Integer.parseInt(bred.breed().getOrDefault("babyCount", "0")) > 0
                            && "2".equals(bred.breed().get("breedFoodConsumed")),
                    "terminal breed snapshot omitted baby/food evidence");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyBreedFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 850)
    public static void breedingTargetRemovedStopsBeforeSecondFeed(GameTestHelper h) {
        Fixture f = fixture(h, "daily-breed-target-lost");
        Cow first = EntityType.COW.create(f.level), second = EntityType.COW.create(f.level);
        h.assertTrue(first != null && second != null, "animal target-loss fixtures could not be created");
        first.moveTo(f.body.getX() + 1, f.body.getY(), f.body.getZ(), 0, 0);
        second.moveTo(f.body.getX() + 2, f.body.getY(), f.body.getZ(), 0, 0);
        first.setNoAi(true);
        second.setNoAi(true);
        f.level.addFreshEntity(first);
        f.level.addFreshEntity(second);
        f.body.getInventory().setItem(0, new ItemStack(Items.WHEAT, 2));
        int babiesBefore = f.level.getEntitiesOfClass(Cow.class, f.body.getBoundingBox().inflate(8), Cow::isBaby).size();
        start(h, f, new SkillParameters("BreedAnimals", "", 1, false, f.dimension(), null, null, null,
                first.getUUID().toString(), "UP", "MAIN_HAND", "", null, null, "BREED", null,
                second.getUUID().toString()));
        h.runAfterDelay(3, () -> {
            h.assertTrue(first.isInLove(), "first animal was not fed before target removal");
            second.discard();
            await(h, f, 140, lost -> {
                h.assertTrue("SECOND_ANIMAL_LOST".equals(lost.behaviorObservation().failureCode()),
                        "removed breeding target did not stop with SECOND_ANIMAL_LOST: " + lost.behaviorObservation());
                h.assertTrue(f.level.getEntitiesOfClass(Cow.class, f.body.getBoundingBox().inflate(8), Cow::isBaby).size()
                                == babiesBefore,
                        "removed breeding target unexpectedly produced a baby");
                h.assertTrue(f.body.getInventory().countItem(Items.WHEAT) == 1,
                        "target removal caused a duplicate first-feed effect");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyTrade", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 900)
    public static void villagerOfferTradesAndDisabledOfferFails(GameTestHelper h) {
        Fixture f = fixture(h, "daily-trade");
        f.level.setDayTime(1000L);
        Villager villager = EntityType.VILLAGER.create(f.level);
        h.assertTrue(villager != null, "villager fixture could not be created");
        villager.moveTo(f.body.getX() + 1, f.body.getY(), f.body.getZ(), 0, 0);
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getOffers().clear();
        villager.getOffers().add(new MerchantOffer(new ItemStack(Items.EMERALD),
                new ItemStack(Items.BREAD), 8, 1, 0.05F));
        f.level.addFreshEntity(villager);
        f.body.addItem(new ItemStack(Items.EMERALD));
        int before = f.body.getInventory().countItem(Items.BREAD);
        start(h, f, new SkillParameters("TradeWithVillager", "", 1, false, f.dimension(), null, null, null,
                villager.getUUID().toString(), "UP", "MAIN_HAND", "", 0, null, "TRADE", null));
        await(h, f, 240, traded -> {
            h.assertTrue(f.body.getInventory().countItem(Items.BREAD) > before,
                    "explicit villager offer did not produce its real result");
            h.assertTrue("minecraft:bread".equals(traded.trade().get("tradeOutput"))
                            && "1".equals(traded.trade().get("tradeOutputReceived")),
                    "terminal trade snapshot omitted the verified output");
            h.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                    "completed trade left the merchant menu open");
            villager.getOffers().get(0).setToOutOfStock();
            start(h, f, new SkillParameters("TradeWithVillager", "", 1, false, f.dimension(), null, null, null,
                    villager.getUUID().toString(), "UP", "MAIN_HAND", "", 0, null, "TRADE", null));
            await(h, f, 120, disabled -> {
                h.assertTrue(disabled.behaviorObservation().failureCode().equals("TRADE_DISABLED"),
                        "disabled offer was not rejected");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyTradeFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 750)
    public static void tradeWithoutInputReportsInsufficientResources(GameTestHelper h) {
        Fixture f = fixture(h, "daily-trade-insufficient");
        Villager villager = tradeVillager(f);
        start(h, f, new SkillParameters("TradeWithVillager", "", 1, false, f.dimension(), null, null, null,
                villager.getUUID().toString(), "UP", "MAIN_HAND", "", 0, null, "TRADE", null));
        await(h, f, 260, insufficient -> {
            h.assertTrue("TRADE_RESOURCES_INSUFFICIENT".equals(insufficient.behaviorObservation().failureCode()),
                    "trade without emeralds did not report insufficient resources: "
                            + insufficient.behaviorObservation());
            h.assertTrue(f.body.getInventory().countItem(Items.BREAD) == 0,
                    "insufficient trade fabricated a bread result");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyTradeFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 750)
    public static void tradeWithNoOutputSlotReportsInventoryFull(GameTestHelper h) {
        Fixture f = fixture(h, "daily-trade-full");
        f.body.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
        for (int slot = 1; slot < 36; slot++) {
            f.body.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        Villager villager = tradeVillager(f);
        start(h, f, new SkillParameters("TradeWithVillager", "", 1, false, f.dimension(), null, null, null,
                villager.getUUID().toString(), "UP", "MAIN_HAND", "", 0, null, "TRADE", null));
        awaitPhase(h, f, "SELECT_TRADE", 260, selecting -> {
            // tryMoveItems may return payment items to a newly empty source slot. Refill every
            // such slot after the real selection command so EXECUTE_TRADE observes a genuinely
            // full live inventory, rather than relying on a synthetic menu snapshot.
            for (int slot = 0; slot < 36; slot++) {
                if (f.body.getInventory().getItem(slot).isEmpty()) {
                    f.body.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                }
            }
            await(h, f, 140, full -> {
                h.assertTrue("INVENTORY_FULL".equals(full.behaviorObservation().failureCode()),
                        "trade with no output capacity did not report INVENTORY_FULL: " + full.behaviorObservation());
                h.assertTrue(f.body.getInventory().countItem(Items.BREAD) == 0,
                        "full inventory trade fabricated a bread result");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyTradeFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 750)
    public static void closedTradeMenuStopsBeforeSelection(GameTestHelper h) {
        Fixture f = fixture(h, "daily-trade-menu-invalid");
        Villager villager = tradeVillager(f);
        f.body.addItem(new ItemStack(Items.EMERALD));
        start(h, f, new SkillParameters("TradeWithVillager", "", 1, false, f.dimension(), null, null, null,
                villager.getUUID().toString(), "UP", "MAIN_HAND", "", 0, null, "TRADE", null));
        awaitPhase(h, f, "SELECT_TRADE", 280, selecting -> {
            h.assertTrue(f.body.containerMenu != f.body.inventoryMenu,
                    "trade selection phase did not open a real merchant menu");
            f.body.closeContainer();
            await(h, f, 120, invalid -> {
                h.assertTrue("TRADE_MENU_INVALIDATED".equals(invalid.behaviorObservation().failureCode()),
                        "closed merchant menu did not produce TRADE_MENU_INVALIDATED: "
                                + invalid.behaviorObservation());
                h.assertTrue(f.body.containerMenu == f.body.inventoryMenu && f.body.getInventory().countItem(Items.BREAD) == 0,
                        "invalidated trade menu left a result or an open menu");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyEnchant", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1200)
    public static void enchantmentConsumesXpLapisAndAddsVanillaEnchantment(GameTestHelper h) {
        Fixture f = fixture(h, "daily-enchant");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.level.setBlockAndUpdate(station, Blocks.ENCHANTING_TABLE.defaultBlockState());
        f.body.addItem(new ItemStack(Items.DIAMOND_SWORD));
        f.body.addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
        f.body.giveExperienceLevels(30);
        int xpBefore = f.body.experienceLevel;
        start(h, f, new SkillParameters("EnchantItem", "minecraft:diamond_sword", 1, false, f.dimension(),
                station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND", "", 0, null, "ENCHANT", null));
        await(h, f, 240, snapshot -> {
            ItemStack sword = f.body.getInventory().items.stream()
                    .filter(s -> s.is(Items.DIAMOND_SWORD)).findFirst().orElse(ItemStack.EMPTY);
            h.assertTrue(!EnchantmentHelper.getEnchantments(sword).isEmpty()
                            && f.body.experienceLevel < xpBefore,
                    "vanilla enchanting did not change the item and XP");
            h.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                    "completed enchant left the enchanting menu open");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyEnchant", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 600)
    public static void enchantingRejectsMissingExperienceAndLapis(GameTestHelper h) {
        Fixture f = fixture(h, "daily-enchant-resources");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.level.setBlockAndUpdate(station, Blocks.ENCHANTING_TABLE.defaultBlockState());
        f.body.addItem(new ItemStack(Items.DIAMOND_SWORD));
        f.body.addItem(new ItemStack(Items.LAPIS_LAZULI));
        start(h, f, new SkillParameters("EnchantItem", "minecraft:diamond_sword", 1, false,
                f.dimension(), station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND", "", 0, null, "ENCHANT", null));
        await(h, f, 120, noExperience -> {
            h.assertTrue("EXPERIENCE_INSUFFICIENT".equals(noExperience.behaviorObservation().failureCode()),
                    "enchant without XP was not rejected honestly: " + noExperience.behaviorObservation());
            f.body.giveExperienceLevels(30);
            f.body.getInventory().clearContent();
            f.body.addItem(new ItemStack(Items.DIAMOND_SWORD));
            start(h, f, new SkillParameters("EnchantItem", "minecraft:diamond_sword", 1, false,
                    f.dimension(), station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND", "", 0, null, "ENCHANT", null));
            await(h, f, 120, noLapis -> {
                h.assertTrue("LAPIS_INSUFFICIENT".equals(noLapis.behaviorObservation().failureCode()),
                        "enchant without lapis was not rejected honestly: " + noLapis.behaviorObservation());
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyEnchantIdentity", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 900)
    public static void enchantingStopsIfTheBoundStationIsReplaced(GameTestHelper h) {
        Fixture f = fixture(h, "daily-enchant-identity");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.level.setBlockAndUpdate(station, Blocks.ENCHANTING_TABLE.defaultBlockState());
        f.body.addItem(new ItemStack(Items.DIAMOND_SWORD));
        f.body.addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
        f.body.giveExperienceLevels(30);
        start(h, f, new SkillParameters("EnchantItem", "minecraft:diamond_sword", 1, false,
                f.dimension(), station.getX(), station.getY(), station.getZ(), "", "UP",
                "MAIN_HAND", "", 0, null, "ENCHANT", null));
        awaitPhase(h, f, "APPLY_ENCHANT", 280, applying -> {
            f.level.setBlockAndUpdate(station, Blocks.AIR.defaultBlockState());
            await(h, f, 140, terminal -> {
                h.assertTrue("ENCHANTMENT_TARGET_INVALIDATED".equals(
                                terminal.behaviorObservation().failureCode()),
                        "replaced enchanting station was not invalidated: "
                                + terminal.behaviorObservation());
                h.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                        "invalidated enchanting station left its menu open");
                h.assertTrue(f.body.getInventory().countItem(Items.DIAMOND_SWORD) == 1,
                        "invalidated enchanting station did not return its input item");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyBrew", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1400)
    public static void brewingTicksAndReturnsRealPotionResult(GameTestHelper h) {
        Fixture f = fixture(h, "daily-brew");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.level.setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
        f.body.addItem(new ItemStack(Items.NETHER_WART));
        f.body.addItem(new ItemStack(Items.BLAZE_POWDER));
        int before = f.body.getInventory().countItem(Items.POTION);
        start(h, f, new SkillParameters("BrewPotion", "minecraft:nether_wart", 1, false, f.dimension(),
                station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND", "", 0, null, "BREW", null));
        await(h, f, 700, snapshot -> {
            boolean awkward = f.body.getInventory().items.stream()
                    .filter(stack -> stack.is(Items.POTION))
                    .anyMatch(stack -> PotionUtils.getPotion(stack).equals(Potions.AWKWARD));
            h.assertTrue(awkward && f.body.getInventory().countItem(Items.POTION) >= before,
                    "brewing action did not return an awkward potion with changed contents");
            h.assertTrue(snapshot.behaviorObservation() != null
                            && snapshot.behaviorObservation().failureCode().equals("VERIFIED"),
                    "brewing terminal evidence was not verified: " + snapshot.behaviorObservation());
            h.assertTrue("1".equals(snapshot.brew().get("brewResultsTaken")),
                    "terminal brewing snapshot omitted the retrieved result");
            h.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                    "completed brew left the brewing menu open");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyBrew", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 600)
    public static void brewingRejectsMissingIngredientAndFuel(GameTestHelper h) {
        Fixture f = fixture(h, "daily-brew-resources");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.level.setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
        f.body.addItem(new ItemStack(Items.BLAZE_POWDER));
        start(h, f, new SkillParameters("BrewPotion", "minecraft:nether_wart", 1, false,
                f.dimension(), station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND", "", 0, null, "BREW", null));
        await(h, f, 120, noIngredient -> {
            h.assertTrue("BREWING_MATERIALS_INSUFFICIENT".equals(noIngredient.behaviorObservation().failureCode()),
                    "brew without ingredient was not rejected honestly: " + noIngredient.behaviorObservation());
            f.body.getInventory().clearContent();
            f.body.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
            f.body.addItem(new ItemStack(Items.NETHER_WART));
            start(h, f, new SkillParameters("BrewPotion", "minecraft:nether_wart", 1, false,
                    f.dimension(), station.getX(), station.getY(), station.getZ(), "", "UP", "MAIN_HAND", "", 0, null, "BREW", null));
            await(h, f, 120, noFuel -> {
                h.assertTrue("BREWING_FUEL_MISSING".equals(noFuel.behaviorObservation().failureCode()),
                        "brew without fuel was not rejected honestly: " + noFuel.behaviorObservation());
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyFishing", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 3000)
    public static void fishingUsesNaturalCastBiteReelAndLoot(GameTestHelper h) {
        Fixture f = fixture(h, "daily-fishing");
        BlockPos water = f.body.blockPosition().offset(3, 0, 0);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            f.level.setBlockAndUpdate(water.offset(x, -2, z), Blocks.DIRT.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, -1, z), Blocks.WATER.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, 0, z), Blocks.WATER.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, 2, z), Blocks.AIR.defaultBlockState());
        }
        f.body.addItem(new ItemStack(Items.FISHING_ROD));
        start(h, f, new SkillParameters("Fish", "minecraft:fishing_rod", 1, false, f.dimension(),
                water.getX(), water.getY(), water.getZ(), "", "UP", "MAIN_HAND", "", null, null, "FISH", 2400));
        h.assertTrue(f.registry.runtimeSnapshots(false).stream().anyMatch(s -> s.companionId().equals(f.companionId)),
                "fishing runtime task did not publish a body snapshot");
        await(h, f, 2400, snapshot -> {
            h.assertTrue(snapshot.behaviorObservation().failureCode().equals("VERIFIED"),
                    "natural fishing did not reach verified loot: " + snapshot.behaviorObservation().failureCode());
            clearFishingPool(f, water);
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyFishingFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 350)
    public static void fishingWithoutRodReportsMissingRod(GameTestHelper h) {
        Fixture f = fixture(h, "daily-fishing-missing-rod");
        start(h, f, new SkillParameters("Fish", "minecraft:fishing_rod", 1, false, f.dimension(),
                null, null, null, "", "UP", "MAIN_HAND", "", null, null, "FISH", 80));
        await(h, f, 120, missing -> {
            h.assertTrue("FISHING_ROD_MISSING".equals(missing.behaviorObservation().failureCode()),
                    "missing fishing rod was not rejected honestly: " + missing.behaviorObservation());
            h.assertTrue(f.body.fishing == null && f.body.getInventory().countItem(Items.FISHING_ROD) == 0,
                    "missing-rod action changed fishing state or fabricated a rod");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyFishingFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1400)
    public static void fishingRuntimeCancelReelsOnceAndCleansHook(GameTestHelper h) {
        Fixture f = fixture(h, "daily-fishing-cancel");
        BlockPos water = f.body.blockPosition().offset(3, 0, 0);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            f.level.setBlockAndUpdate(water.offset(x, -2, z), Blocks.DIRT.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, -1, z), Blocks.WATER.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, 0, z), Blocks.WATER.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, 2, z), Blocks.AIR.defaultBlockState());
        }
        f.body.addItem(new ItemStack(Items.FISHING_ROD));
        int fishBefore = fishCount(f.body);
        start(h, f, new SkillParameters("Fish", "minecraft:fishing_rod", 1, false, f.dimension(),
                water.getX(), water.getY(), water.getZ(), "", "UP", "MAIN_HAND", "", null, null, "FISH", 1200));
        awaitPhase(h, f, "WAIT_BITE", 700, waiting -> {
            h.assertTrue(f.body.fishing != null, "fishing WAIT_BITE phase had no live hook to cancel");
            CompanionRegistry.RuntimeResult cancelled = f.registry.runtimeCancel(f.companionId, f.lease, 1L);
            h.assertTrue(cancelled.success() && "CANCELLED".equals(cancelled.state()),
                    "runtime cancel did not acknowledge fishing cancellation: " + cancelled.code());
            h.runAfterDelay(8, () -> {
                CompanionRegistry.RuntimeSnapshot cancelledSnapshot = runtimeSnapshot(f);
                h.assertTrue("IDLE".equals(cancelledSnapshot.behaviorState()),
                        "cancelled fishing action did not return the runtime to IDLE");
                h.assertTrue(f.body.fishing == null, "runtime cancel left a live fishing hook behind");
                int fishAfterCleanup = fishCount(f.body);
                h.runAfterDelay(12, () -> {
                    h.assertTrue(f.body.fishing == null && fishCount(f.body) == fishAfterCleanup,
                            "fishing cancellation produced a duplicate delayed effect");
                    h.assertTrue(fishAfterCleanup >= fishBefore,
                            "fishing cancellation removed pre-existing fish inventory");
                    clearFishingPool(f, water);
                    finish(h, f);
                });
            });
        });
    }

    @GameTest(batch = "dailyRuntimeControl", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 250)
    public static void invalidDailyStartDoesNotLeaveHalfStartedSkillState(GameTestHelper h) {
        Fixture f = fixture(h, "daily-invalid-start");
        SkillParameters missingDestination = new SkillParameters("UseVehicle", "", 1, false,
                f.dimension(), null, null, null, "", "UP", "MAIN_HAND", "",
                null, null, "TRAVEL", null);
        CompanionRegistry.RuntimeResult rejected = f.registry.runtimeStart(f.companionId, f.lease, 1L,
                "invalid-daily-start", "skill", null, null, null, missingDestination);
        h.assertTrue(!rejected.success() && "INVALID_SKILL_PARAMETERS".equals(rejected.code()),
                "invalid daily request was not rejected before mutation: " + rejected);
        CompanionRegistry.RuntimeSnapshot state = runtimeSnapshot(f);
        h.assertTrue("IDLE".equals(state.behaviorState()) && state.behaviorId() == null,
                "invalid daily request left a half-started behavior: " + state);
        finish(h, f);
    }

    @GameTest(batch = "dailyOwnerSupersede", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1300)
    public static void ownerFollowSupersedesAndCleansTheRunningDailyAction(GameTestHelper h) {
        Fixture f = fixture(h, "daily-owner-supersede");
        BlockPos station = f.body.blockPosition().offset(2, 0, 0);
        f.level.setBlockAndUpdate(station, Blocks.BREWING_STAND.defaultBlockState());
        f.body.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
        f.body.addItem(new ItemStack(Items.NETHER_WART));
        f.body.addItem(new ItemStack(Items.BLAZE_POWDER));
        start(h, f, new SkillParameters("BrewPotion", "minecraft:nether_wart", 1, false,
                f.dimension(), station.getX(), station.getY(), station.getZ(), "", "UP",
                "MAIN_HAND", "", 0, null, "BREW", null));
        awaitPhase(h, f, "WAIT_BREW", 800, waiting -> {
            CompanionRegistry.Result followed = f.registry.follow(f.owner);
            h.assertTrue(followed.success(), "owner follow did not supersede daily action: " + followed.code());
            h.runAfterDelay(3, () -> {
                h.assertTrue(f.body.containerMenu == f.body.inventoryMenu,
                        "owner supersede left the daily brewing menu open");
                h.assertTrue(f.registry.status(f.owner).contains("mode=FOLLOW"),
                        "owner supersede did not install the follow behavior");
                h.assertTrue(f.registry.stop(f.owner).success(),
                        "owner supersede cleanup could not stop follow");
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyElytra", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 1200)
    public static void elytraEquipsGlidesAndRejectsUnsafeLanding(GameTestHelper h) {
        Fixture f = fixture(h, "daily-elytra");
        BlockPos target = f.body.blockPosition().offset(3, 4, 0);
        f.level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        f.body.addItem(new ItemStack(Items.ELYTRA));
        f.body.moveTo(f.body.getX(), f.body.getY() + 8, f.body.getZ(), 0, 0);
        f.body.setOnGround(false);
        f.body.setDeltaMovement(0.0D, -0.1D, 0.0D);
        start(h, f, new SkillParameters("GlideWithElytra", "minecraft:elytra", 1, false, f.dimension(),
                target.getX(), target.getY(), target.getZ(), "", "UP", "CHEST", "", null, null, "GLIDE", null));
        await(h, f, 240, snapshot -> {
            h.assertTrue("VERIFIED".equals(snapshot.behaviorObservation().failureCode())
                            && f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA),
                    "elytra did not complete a verified controlled landing: " + snapshot.behaviorObservation());
            BlockPos lava = f.body.blockPosition().offset(3, 4, 2);
            f.level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            f.body.moveTo(f.body.getX(), f.body.getY() + 8, f.body.getZ(), 0, 0);
            f.body.setOnGround(false);
            f.body.setDeltaMovement(0.0D, -0.1D, 0.0D);
            start(h, f, new SkillParameters("GlideWithElytra", "minecraft:elytra", 1, false, f.dimension(),
                    lava.getX(), lava.getY(), lava.getZ(), "", "UP", "CHEST", "", null, null, "GLIDE", null));
            await(h, f, 120, unsafe -> {
                h.assertTrue(unsafe.behaviorObservation() != null
                                && !unsafe.behaviorObservation().failureCode().equals("VERIFIED")
                                && unsafe.behaviorObservation().failureCode().contains("GLIDE"),
                        "unsafe gliding was incorrectly accepted as verified: " + unsafe.behaviorObservation());
                f.level.setBlockAndUpdate(lava, Blocks.AIR.defaultBlockState());
                finish(h, f);
            });
        });
    }

    @GameTest(batch = "dailyElytraFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 400)
    public static void elytraUnavailableStopsBeforeEquipmentMutation(GameTestHelper h) {
        Fixture f = fixture(h, "daily-elytra-unavailable");
        BlockPos target = f.body.blockPosition().offset(3, 3, 0);
        f.level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        start(h, f, new SkillParameters("GlideWithElytra", "minecraft:elytra", 1, false, f.dimension(),
                target.getX(), target.getY(), target.getZ(), "", "UP", "CHEST", "", null, null, "GLIDE", 100));
        await(h, f, 140, unavailable -> {
            h.assertTrue("ELYTRA_UNAVAILABLE".equals(unavailable.behaviorObservation().failureCode()),
                    "missing elytra was not rejected before mutation: " + unavailable.behaviorObservation());
            h.assertTrue(f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()
                            && !f.body.isFallFlying(),
                    "unavailable elytra action changed the chest slot or flight state");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyElytraFailure", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 500)
    public static void elytraOnGroundReportsUnsafeStart(GameTestHelper h) {
        Fixture f = fixture(h, "daily-elytra-unsafe-start");
        BlockPos target = f.body.blockPosition().offset(3, 3, 0);
        f.level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        f.body.addItem(new ItemStack(Items.ELYTRA));
        f.body.setOnGround(true);
        start(h, f, new SkillParameters("GlideWithElytra", "minecraft:elytra", 1, false, f.dimension(),
                target.getX(), target.getY(), target.getZ(), "", "UP", "CHEST", "", null, null, "GLIDE", 100));
        await(h, f, 220, unsafe -> {
            h.assertTrue("GLIDE_START_UNSAFE".equals(unsafe.behaviorObservation().failureCode()),
                    "grounded elytra start was not rejected honestly: " + unsafe.behaviorObservation());
            h.assertTrue(f.body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA)
                            && !f.body.isFallFlying(),
                    "unsafe elytra start did not leave the equipped body grounded");
            finish(h, f);
        });
    }

    @GameTest(batch = "dailyElytraPath", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 650)
    public static void elytraRejectsHazardObservedAlongTheGlidePath(GameTestHelper h) {
        Fixture f = fixture(h, "daily-elytra-path");
        BlockPos origin = f.body.blockPosition();
        f.body.addItem(new ItemStack(Items.ELYTRA));
        f.body.moveTo(f.body.getX(), f.body.getY() + 10.0D, f.body.getZ(), 0.0F, 0.0F);
        f.body.setOnGround(false);
        f.body.setDeltaMovement(0.0D, -0.1D, 0.0D);
        BlockPos target = origin.offset(8, 4, 0);
        f.level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        BlockPos pathHazard = origin.offset(4, 7, 0);
        f.level.setBlockAndUpdate(pathHazard, Blocks.LAVA.defaultBlockState());
        start(h, f, new SkillParameters("GlideWithElytra", "minecraft:elytra", 1, false,
                f.dimension(), target.getX(), target.getY(), target.getZ(), "", "FORWARD",
                "CHEST", "", null, null, "GLIDE", null));
        await(h, f, 200, snapshot -> {
            h.assertTrue("GLIDE_TERRAIN_UNSAFE".equals(snapshot.behaviorObservation().failureCode()),
                    "mid-path lava was not rejected before glide control: "
                            + snapshot.behaviorObservation());
            h.assertTrue(!f.body.isFallFlying(),
                    "unsafe path started fall-flying before terrain validation");
            f.level.setBlockAndUpdate(pathHazard, Blocks.AIR.defaultBlockState());
            finish(h, f);
        });
    }

    private static Fixture fixture(GameTestHelper h, String name) {
        FakeConnection connection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                new GameProfile(UUID.randomUUID(), name + "-owner"));
        Vec3 spawn = h.absoluteVec(new Vec3(1, 1, 1));
        owner.moveTo(spawn.x, spawn.y, spawn.z, 0, 0);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner);
        CompanionRegistry registry = MinecraftAiCompanionForge.integrationRegistryFor(h.getLevel().getServer());
        h.assertTrue(registry.create(owner, name).success(), "companion create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        h.assertTrue(body != null, "daily body was not spawned");
        String id = registry.runtimeSnapshots(false).stream()
                .filter(s -> s.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElseThrow();
        String lease = name + "-lease";
        h.assertTrue(registry.runtimeAcquireLease(id, lease, 1L, System.currentTimeMillis() + 300_000L).success(),
                "daily runtime lease failed");
        return new Fixture(h, owner, connection, registry, body, body.serverLevel(), id, lease);
    }

    private static void start(GameTestHelper h, Fixture f, SkillParameters parameters) {
        CompanionRegistry.RuntimeResult result = f.registry.runtimeStart(f.companionId, f.lease, 1L,
                "daily-" + UUID.randomUUID(), "skill", null, null, null, parameters);
        h.assertTrue(result.success(), "daily runtime start failed: " + result.code());
    }

    private static CompanionRegistry.RuntimeSnapshot runtimeSnapshot(Fixture f) {
        return f.registry.runtimeSnapshots(false).stream()
                .filter(s -> s.companionId().equals(f.companionId)).findFirst().orElseThrow();
    }

    private static String phase(CompanionRegistry.RuntimeSnapshot snapshot) {
        return snapshot.behaviorObservation() == null ? ""
                : snapshot.behaviorObservation().details().getOrDefault("phase", "");
    }

    /** Waits for a specific in-flight phase before mutating the live world or Runtime lifecycle. */
    private static void awaitPhase(GameTestHelper h, Fixture f, String expectedPhase, int ticksRemaining,
                                   Consumer<CompanionRegistry.RuntimeSnapshot> action) {
        CompanionRegistry.RuntimeSnapshot snapshot = runtimeSnapshot(f);
        if ("RUNNING".equals(snapshot.behaviorState()) && expectedPhase.equals(phase(snapshot))) {
            action.accept(snapshot);
            return;
        }
        h.assertTrue("RUNNING".equals(snapshot.behaviorState()) && ticksRemaining > 0,
                "daily action did not reach phase " + expectedPhase + ": " + snapshot.behaviorObservation());
        h.runAfterDelay(1, () -> awaitPhase(h, f, expectedPhase, ticksRemaining - 1, action));
    }

    private static void awaitSleepingWhileRunning(GameTestHelper h, Fixture f, int ticksRemaining,
                                                  Runnable action) {
        CompanionRegistry.RuntimeSnapshot snapshot = runtimeSnapshot(f);
        if (f.body.isSleeping() && "RUNNING".equals(snapshot.behaviorState())) {
            action.run();
            return;
        }
        h.assertTrue(ticksRemaining > 0 && "RUNNING".equals(snapshot.behaviorState()),
                "sleep did not reach cancellable state: code="
                        + (snapshot.behaviorObservation() == null ? "" : snapshot.behaviorObservation().failureCode())
                        + " phase=" + (snapshot.behaviorObservation() == null ? ""
                        : snapshot.behaviorObservation().details().getOrDefault("phase", "")));
        h.runAfterDelay(1, () -> awaitSleepingWhileRunning(h, f, ticksRemaining - 1, action));
    }

    /** Polls the live runtime snapshot on server ticks; this deliberately never calls registry.tick(). */
    private static void await(GameTestHelper h, Fixture f, int ticksRemaining,
                               Consumer<CompanionRegistry.RuntimeSnapshot> terminal) {
        CompanionRegistry.RuntimeSnapshot snapshot = runtimeSnapshot(f);
        if ("RUNNING".equals(snapshot.behaviorState())) {
            h.assertTrue(ticksRemaining > 0,
                    "daily action timed out: " + snapshot.evidenceSummary()
                            + " code=" + (snapshot.behaviorObservation() == null ? ""
                            : snapshot.behaviorObservation().failureCode())
                            + " phase=" + (snapshot.behaviorObservation() == null ? ""
                            : snapshot.behaviorObservation().details().getOrDefault("phase", ""))
                            + " pos=" + f.body.blockPosition()
                            + " wheat=" + f.body.getInventory().countItem(Items.WHEAT)
                            + " seeds=" + f.body.getInventory().countItem(Items.WHEAT_SEEDS)
                            + " drops=" + f.level.getEntitiesOfClass(ItemEntity.class,
                            f.body.getBoundingBox().inflate(8.0D), ItemEntity::isAlive).stream()
                            .limit(4).map(entity -> BuiltInRegistries.ITEM.getKey(entity.getItem().getItem())
                                    + ":" + entity.getItem().getCount() + "@"
                                    + String.format(java.util.Locale.ROOT, "%.2f", f.body.distanceToSqr(entity)))
                            .toList());
            h.runAfterDelay(1, () -> await(h, f, ticksRemaining - 1, terminal));
            return;
        }
        h.assertTrue("IDLE".equals(snapshot.behaviorState()),
                "daily action did not reach a terminal idle state: " + snapshot.behaviorObservation());
        h.assertTrue(snapshot.behaviorObservation() != null,
                "terminal daily action had no body observation");
        terminal.accept(snapshot);
    }

    private static void finish(GameTestHelper h, Fixture f) {
        cleanup(f);
        h.succeed();
    }

    private static void discardSeedDrops(GameTestHelper h, Fixture f, int remaining) {
        f.level.getEntitiesOfClass(ItemEntity.class, f.body.getBoundingBox().inflate(8.0D),
                entity -> entity.getItem().is(Items.WHEAT_SEEDS)).forEach(ItemEntity::discard);
        if (remaining > 0) h.runAfterDelay(1, () -> discardSeedDrops(h, f, remaining - 1));
    }

    private static void clearFishingPool(Fixture f, BlockPos water) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            f.level.setBlockAndUpdate(water.offset(x, 0, z), Blocks.AIR.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            f.level.setBlockAndUpdate(water.offset(x, -2, z), Blocks.STONE.defaultBlockState());
        }
    }

    private static int fishCount(CompanionPlayer body) {
        return body.getInventory().countItem(Items.COD) + body.getInventory().countItem(Items.SALMON)
                + body.getInventory().countItem(Items.TROPICAL_FISH)
                + body.getInventory().countItem(Items.PUFFERFISH);
    }

    private static Villager tradeVillager(Fixture f) {
        Villager villager = EntityType.VILLAGER.create(f.level);
        f.h.assertTrue(villager != null, "trade failure fixture could not create a villager");
        villager.moveTo(f.body.getX() + 1, f.body.getY(), f.body.getZ(), 0, 0);
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getOffers().clear();
        villager.getOffers().add(new MerchantOffer(new ItemStack(Items.EMERALD),
                new ItemStack(Items.BREAD), 8, 1, 0.05F));
        f.level.addFreshEntity(villager);
        return villager;
    }

    private static void cleanup(Fixture f) {
        f.registry.runtimeCancel(f.companionId, f.lease, 1L);
        f.registry.runtimeReleaseLease(f.companionId, f.lease, 1L);
        f.registry.remove(f.owner);
        f.h.getLevel().getServer().getPlayerList().remove(f.owner);
        f.connection.disconnect(Component.literal("daily action GameTest complete"));
    }

    private static void placeBed(Fixture f, BlockPos foot, boolean occupied) {
        BlockPos head = foot.north();
        f.level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, BedPart.FOOT).setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.OCCUPIED, occupied));
        f.level.setBlockAndUpdate(head, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, BedPart.HEAD).setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.OCCUPIED, occupied));
    }

    private record Fixture(GameTestHelper h, ServerPlayer owner, FakeConnection connection,
                           CompanionRegistry registry, CompanionPlayer body,
                           net.minecraft.server.level.ServerLevel level, String companionId,
                           String lease) {
        String dimension() { return level().dimension().location().toString(); }
        String lastResultCode() { return registry.runtimeSnapshots(false).stream()
                .filter(s -> s.companionId().equals(companionId))
                .map(CompanionRegistry.RuntimeSnapshot::behaviorObservation)
                .map(CompanionRegistry.BehaviorObservation::failureCode).findFirst().orElse(""); }
    }
}
