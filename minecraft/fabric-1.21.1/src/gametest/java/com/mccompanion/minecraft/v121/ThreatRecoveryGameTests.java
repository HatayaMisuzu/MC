package com.mccompanion.minecraft.v121;

import com.mccompanion.core.body.build.SmallBlueprint;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;

/** REAL_MINECRAFT_GAMETEST; initial arena/equipment/hunger and scripted hostile motion are fixtures. */
public final class ThreatRecoveryGameTests implements FabricGameTest {
    @GameTest(batch = "threat_recovery", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    public void lowHealthRetreatEatsAndResumesDurableBlueprint(GameTestHelper helper) {
        if (Boolean.getBoolean("mccompanion.threat.e2e")) { helper.succeed(); return; }
        runLowHealthRecovery(helper);
    }

    // External authenticated requests and bridge status use wall-clock time, while GameTest ticks run accelerated.
    @GameTest(batch = "threat_recovery", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1200000)
    public void runtimeBridgeRecoversDurableGraph(GameTestHelper helper) {
        if (!Boolean.getBoolean("mccompanion.threat.e2e")) { helper.succeed(); return; }
        runLowHealthRecovery(helper);
    }

    private static void runLowHealthRecovery(GameTestHelper helper) {
        ready(fixture(helper, 1536), 100, f -> {
            var blocks = new ArrayList<SmallBlueprint.Block>();
            for (int x = 0; x < 7; x++) blocks.add(new SmallBlueprint.Block(new SmallBlueprint.Offset(x, 0, 0),
                    "minecraft:cobblestone", Map.of(), java.util.List.of()));
            var anchor = f.origin.offset(3, 0, 2);
            var plan = new SmallBlueprint(new SmallBlueprint.Anchor(f.body.serverLevel().dimension().location().toString(),
                    anchor.getX(), anchor.getY(), anchor.getZ()), new SmallBlueprint.Size(7, 1, 1), blocks,
                    new SmallBlueprint.SupportPolicy(java.util.List.of(), 0, false));
            if (Boolean.getBoolean("mccompanion.threat.e2e")) {
                var supply = f.origin.offset(0, 0, 3);
                f.body.serverLevel().setBlockAndUpdate(supply, Blocks.CHEST.defaultBlockState());
                ((net.minecraft.world.Container) f.body.serverLevel().getBlockEntity(supply))
                        .setItem(0, new ItemStack(Items.COBBLESTONE, 7));
            } else {
                f.body.addItem(new ItemStack(Items.COBBLESTONE, 7));
            }
            f.body.getInventory().setItem(15, new ItemStack(Items.COOKED_BEEF, 4));
            f.body.getFoodData().setFoodLevel(10);
            f.body.getFoodData().setSaturation(0);
            CompanionEntry entry = data(f).get(f.owner.getUUID());
            startBlueprint(f, plan, () -> {
                f.await(250, () -> entry.blueprintSession != null && entry.blueprintSession.completed().size() >= 2, () -> {
                    int completed = entry.blueprintSession.completed().size();
                    String behaviorId = entry.runtimeBehaviorId;
                    var zombie = f.mob(EntityType.ZOMBIE, f.body.blockPosition().getX() - f.origin.getX() + 2,
                            f.body.blockPosition().getZ() - f.origin.getZ());
                    zombie.setNoAi(true);
                    zombie.setTarget(f.body);
                    Vec3 before = f.body.position();
                    helper.assertTrue(f.body.hurt(f.body.damageSources().generic(), 15), "low-health fixture damage rejected");
                    f.await(100, () -> "RETREAT".equals(details(f).get("phase"))
                            && f.body.position().distanceToSqr(before) > 4, () -> {
                        var durable = CompanionEntry.load(entry.save());
                        helper.assertTrue(durable.blueprintSession != null && durable.blueprintSession.completed().size() == completed,
                                "safety interruption discarded or repeated completed durable steps");
                        helper.assertTrue(behaviorId.equals(durable.runtimeBehaviorId), "behavior identity changed");
                        helper.assertTrue(f.registry.locallyHandlesSafetyEvent(f.id, "LOW_HEALTH"), "local recovery not advertised");
                        moveAway(f, zombie, 100);
                        f.await(900, () -> "LOCAL_THREAT_RESUMED".equals(code(f)), () -> {
                            helper.assertTrue(f.body.getHealth() >= 12 && f.body.getInventory().countItem(Items.COOKED_BEEF) < 4,
                                    "recovery lacked actual consumption and vanilla healing");
                            helper.assertTrue(entry.blueprintSession.completed().size() == completed, "resume reset durable progress");
                            helper.assertTrue(entry.runtimeBehaviorId.equals(behaviorId), "resume invented another task");
                            f.await(400, () -> f.snapshot().behaviorState().equals("IDLE"), () -> {
                                for (int x = 0; x < 7; x++) helper.assertTrue(f.body.serverLevel().getBlockState(anchor.offset(x, 0, 0))
                                        .is(Blocks.COBBLESTONE), "resumed task did not complete real placement");
                                helper.assertTrue(f.body.getInventory().countItem(Items.COBBLESTONE) == 0, "completed work was replayed");
                                finishPublished(f, zombie, System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos());
                            });
                        });
                    });
                });
            });
        });
    }

    @GameTest(batch = "threat_recovery", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 300)
    public void creeperPreemptsMultipleEnemiesAndCancellationStopsRecovery(GameTestHelper helper) {
        ready(fixture(helper, 1632), 100, f -> {
            var zombie = f.mob(EntityType.ZOMBIE, 2, 0);
            var other = f.mob(EntityType.ZOMBIE, -2, 0);
            var creeper = f.mob(EntityType.CREEPER, 4, 1);
            for (Mob mob : java.util.List.of(zombie, other, creeper)) mob.setNoAi(true);
            f.body.addItem(new ItemStack(Items.IRON_SWORD));
            f.start("MeleeAttack", zombie);
            f.await(100, () -> "CREEPER_DANGER".equals(details(f).get("trigger")), () -> {
                helper.assertTrue(details(f).get("threatId").equals(creeper.getUUID().toString()),
                        "creeper did not take priority over locked melee target");
                helper.assertTrue(MinecraftThreatRecovery.observe(f.body, f.owner).size() == 3, "hostile fixture contamination");
                f.await(100, () -> f.body.distanceTo(zombie) >= 7 && f.body.distanceTo(other) >= 7 && f.body.distanceTo(creeper) >= 8, () -> {
                    helper.assertTrue(f.registry.runtimeCancel(f.id, f.lease, 1).success(), "recovery cancellation failed");
                    helper.assertTrue(!f.body.isUsingItem() && f.body.zza == 0, "cancel retained safety input");
                    helper.runAfterDelay(20, () -> {
                        helper.assertTrue(f.snapshot().behaviorState().equals("IDLE"), "cancel resurrected original task");
                        helper.assertTrue(zombie.isAlive() && other.isAlive() && creeper.isAlive(), "retreat faked enemy removal");
                        other.discard(); creeper.discard(); f.finish(zombie);
                    });
                });
            });
        });
    }

    @GameTest(batch = "threat_recovery", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void skeletonThreatUsesLargerSafeDistance(GameTestHelper helper) {
        ready(fixture(helper, 1728), 100, f -> {
            var skeleton = f.mob(EntityType.SKELETON, 10, 0);
            skeleton.setNoAi(true);
            skeleton.setTarget(f.body);
            helper.assertTrue(f.registry.runtimeStart(f.id, f.lease, 1, "ranged-route", "goto",
                    (double)f.origin.getX(), 100.0, (double)f.origin.getZ() + 10, null).success(), "route start failed");
            f.await(150, () -> "RANGED_THREAT".equals(details(f).get("trigger"))
                    && f.body.distanceTo(skeleton) >= 16, () -> {
                helper.assertTrue(skeleton.getHealth() == skeleton.getMaxHealth(), "retreat fabricated damage");
                f.registry.runtimeCancel(f.id, f.lease, 1);
                f.finish(skeleton);
            });
        });
    }

    @GameTest(batch = "threat_recovery", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500)
    public void ownerDamageInvokesBoundedCombatThenReturnsToRoute(GameTestHelper helper) {
        ready(fixture(helper, 1824), 100, f -> {
            var zombie = f.mob(EntityType.ZOMBIE, -3, 0);
            zombie.setNoAi(true);
            f.body.addItem(new ItemStack(Items.IRON_SWORD));
            helper.assertTrue(f.registry.runtimeStart(f.id, f.lease, 1, "owner-route", "goto",
                    (double)f.origin.getX() + 10, 100.0, (double)f.origin.getZ(), null).success(), "route start failed");
            helper.assertTrue(zombie.doHurtTarget(f.owner), "owner damage fixture failed");
            f.await(100, () -> "DEFEND_OWNER".equals(details(f).get("phase")), () -> {
                f.await(350, () -> f.snapshot().behaviorState().equals("IDLE"), () -> {
                    helper.assertTrue(zombie.isDeadOrDying(), "owner defense did not produce vanilla combat death");
                    helper.assertTrue(f.body.position().distanceToSqr(new Vec3(f.origin.getX() + 10, 100, f.origin.getZ())) < 4,
                            "original route was not completed");
                    helper.assertTrue(f.snapshot().behaviorId().equals("owner-route"), "owner defense replaced original durable identity");
                    f.finish(zombie);
                });
            });
        });
    }

    @GameTest(batch = "threat_recovery", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    public void missingFoodFailsAndPauseCancelsEatingWithoutAutomaticResume(GameTestHelper helper) {
        ready(fixture(helper, 1920), 100, f -> {
        f.body.getFoodData().setFoodLevel(10);
        f.body.getFoodData().setSaturation(0);
        helper.assertTrue(f.registry.runtimeStart(f.id, f.lease, 1, "hungry-route", "goto",
                (double)f.origin.getX() + 10, 100.0, (double)f.origin.getZ(), null).success(), "route start failed");
        f.body.hurt(f.body.damageSources().generic(), 15);
        f.awaitPaused(80, "RECOVERY_FOOD_MISSING", () -> {
            helper.assertTrue(!f.registry.locallyHandlesSafetyEvent(f.id, "LOW_HEALTH"), "failed recovery hides failure");
            f.body.addItem(new ItemStack(Items.COOKED_BEEF, 4));
            helper.assertTrue(f.registry.runtimeResume(f.id, f.lease, 1).success(), "repair resume failed");
            f.await(80, f.body::isUsingItem, () -> {
                helper.assertTrue(f.registry.runtimePause(f.id, f.lease, 1).success(), "eating pause failed");
                helper.assertTrue(!f.body.isUsingItem(), "pause left eating input active");
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(f.snapshot().behaviorState().equals("PAUSED"), "owner pause automatically resumed");
                    f.finish(f.owner);
                });
            });
        });
        });
    }

    @GameTest(batch = "threat_recovery", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600)
    public void explicitOwnerDefenseUsesSharedContinuousCombat(GameTestHelper helper) {
        ready(fixture(helper, 2016), 100, f -> {
            var zombie = f.mob(EntityType.ZOMBIE, -6, 0);
            zombie.setNoAi(true);
            f.body.addItem(new ItemStack(Items.IRON_SWORD));
            // addFreshEntity becomes observable on the next world tick.
            helper.runAfterDelay(1, () -> {
                f.start("DefendOwner", zombie);
                f.await(450, () -> f.snapshot().behaviorState().equals("IDLE"), () -> {
                    helper.assertTrue(zombie.isDeadOrDying(), "explicit defense lacks real target death");
                    helper.assertTrue(Integer.parseInt(details(f).get("attacks")) >= 2,
                            "DefendOwner did not reuse the continuous combat controller");
                    f.finish(zombie);
                });
            });
        });
    }

    private static void startBlueprint(CombatGameTests.Fixture f, SmallBlueprint plan, Runnable action) {
        if (Boolean.getBoolean("mccompanion.threat.e2e")) {
            f.registry.runtimeReleaseLease(f.id, f.lease, 1);
            org.slf4j.LoggerFactory.getLogger("ThreatE2E").info("threat_e2e_ready companion={}", f.id);
            awaitExternalBlueprint(f, System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos(), action);
        } else {
            f.helper.assertTrue(f.registry.runtimeStart(f.id, f.lease, 1, "durable-threat-blueprint", "skill",
                    null, null, null, new SkillParameters("BuildSmallBlueprint", plan)).success(), "blueprint start failed");
            action.run();
        }
    }

    private static void awaitExternalBlueprint(CombatGameTests.Fixture f, long deadline, Runnable action) {
        if (data(f).get(f.owner.getUUID()).blueprintSession != null) { action.run(); return; }
        f.helper.assertTrue(System.nanoTime() < deadline, "external Runtime did not start blueprint");
        f.helper.runAfterDelay(1, () -> awaitExternalBlueprint(f, deadline, action));
    }

    private static void finishPublished(CombatGameTests.Fixture f, Mob target, long deadline) {
        if (!Boolean.getBoolean("mccompanion.threat.e2e")
                || f.snapshot().behaviorId().equals(f.registry.runtimeLastPublishedBehaviorId())) { f.finish(target); return; }
        f.helper.assertTrue(System.nanoTime() < deadline, "Bridge did not publish observed completion");
        f.helper.runAfterDelay(1, () -> finishPublished(f, target, deadline));
    }

    // Read the actual vanilla protection countdown; never mutate it to make damage pass.
    private static int spawnProtection(net.minecraft.server.level.ServerPlayer player) {
        try {
            var field = net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            field.setAccessible(true);
            return field.getInt(player);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static void ready(CombatGameTests.Fixture f, int ticks, java.util.function.Consumer<CombatGameTests.Fixture> action) {
        if (spawnProtection(f.body) <= 0 && spawnProtection(f.owner) <= 0) { action.accept(f); return; }
        f.helper.assertTrue(ticks > 0, "spawn damage protection did not expire");
        f.helper.runAfterDelay(1, () -> ready(f, ticks - 1, action));
    }

    private static Map<String, String> details(CombatGameTests.Fixture f) {
        var observation = f.snapshot().behaviorObservation();
        return observation == null ? Map.of() : observation.details();
    }

    private static String code(CombatGameTests.Fixture f) {
        var observation = f.snapshot().behaviorObservation();
        return observation == null ? "" : observation.failureCode();
    }

    private static CombatGameTests.Fixture fixture(GameTestHelper helper, int coordinate) {
        var f = new CombatGameTests.Fixture(helper, coordinate);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            f.body.serverLevel().setChunkForced((coordinate >> 4) + x, (coordinate >> 4) + z, true);
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            f.body.serverLevel().setBlockAndUpdate(f.origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y < 4; y++) f.body.serverLevel().setBlockAndUpdate(f.origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
            f.body.serverLevel().setBlockAndUpdate(f.origin.offset(x, 4, z), Blocks.STONE.defaultBlockState());
        }
        f.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        f.body.serverLevel().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        return f;
    }

    private static CompanionSavedData data(CombatGameTests.Fixture f) {
        return f.body.serverLevel().getServer().overworld().getDataStorage().computeIfAbsent(
                CompanionSavedData.FACTORY, CompanionSavedData.STORAGE_ID);
    }

    private static void moveAway(CombatGameTests.Fixture f, Mob mob, int ticks) {
        if (ticks <= 0 || f.finished) return;
        mob.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, 0, -0.25));
        f.helper.runAfterDelay(1, () -> moveAway(f, mob, ticks - 1));
    }
}
