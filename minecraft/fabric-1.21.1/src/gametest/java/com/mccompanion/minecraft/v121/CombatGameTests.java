package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.fabric.MinecraftAiCompanionFabric;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;

/** REAL_MINECRAFT_GAMETEST. Arena, starting equipment and target motion are explicit fixtures. */
public final class CombatGameTests implements FabricGameTest {
    @GameTest(batch = "combat_core", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600)
    public void meleeChasesMovingZombieAndVerifiesDeath(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1216);
        var zombie = f.mob(EntityType.ZOMBIE, 8, 0);
        zombie.setTarget(f.body);
        f.body.addItem(new ItemStack(Items.WOODEN_SWORD));
        f.body.getInventory().setItem(12, new ItemStack(Items.IRON_SWORD));
        Vec3 start = f.body.position();
        f.start("MeleeAttack", zombie);
        f.await(450, () -> f.snapshot().behaviorState().equals("IDLE"), () -> {
            helper.assertTrue(zombie.isDeadOrDying(), "combat completed without real target death");
            helper.assertTrue(f.body.position().distanceToSqr(start) > 4, "melee never approached moving target");
            helper.assertTrue(f.body.getMainHandItem().is(Items.IRON_SWORD), "stronger inventory weapon not equipped");
            var detail = f.snapshot().behaviorObservation().details();
            helper.assertTrue(Integer.parseInt(detail.get("attacks")) >= 2, "continuous attacks not observed");
            helper.assertTrue(Integer.parseInt(detail.get("damageObservations")) >= 2, "real damage missing");
            helper.assertTrue(detail.get("targetId").equals(zombie.getUUID().toString()), "target identity changed");
            f.finish(zombie);
        });
    }

    @GameTest(batch = "combat_core", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 500)
    public void shieldBlocksLowersAttacksAndCancels(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1280);
        var zombie = f.mob(EntityType.ZOMBIE, 2, 0);
        zombie.setNoAi(true);
        zombie.setTarget(f.body);
        f.body.addItem(new ItemStack(Items.SHIELD));
        f.body.addItem(new ItemStack(Items.IRON_SWORD));
        var otherThreat = f.mob(EntityType.ZOMBIE, 3, 1);
        otherThreat.setNoAi(true);
        f.start("ShieldCombat", zombie);
        f.await(100, () -> f.body.isBlocking(), () -> {
            // Query after the fixture entities have entered the world's ticking entity index.
            helper.assertTrue(new ReflexController().nearestRetreatThreat(f.body, zombie.getUUID())
                    .orElse(null) == otherThreat, "explicit combat exemption suppressed another hostile reflex");
            otherThreat.discard();
            helper.assertTrue(f.body.getOffhandItem().is(Items.SHIELD), "shield was not equipped through menu");
            float health = f.body.getHealth();
            zombie.doHurtTarget(f.body);
            helper.assertTrue(f.body.getHealth() == health, "raised shield failed to block frontal melee");
            f.await(100, () -> !f.body.isUsingItem() && zombie.getHealth() < zombie.getMaxHealth(), () -> {
                f.await(100, () -> f.body.isBlocking(), () -> {
                    helper.assertTrue(f.registry.runtimeCancel(f.id, f.lease, 1).success(), "shield cancellation rejected");
                    helper.assertTrue(!f.body.isUsingItem() && f.body.zza == 0, "cancel left shield or movement held");
                    f.body.getInventory().offhand.set(0, ItemStack.EMPTY);
                    f.start("ShieldCombat", zombie);
                    f.awaitPaused(40, "SHIELD_MISSING", () -> f.finish(zombie));
                });
            });
        });
    }

    @GameTest(batch = "combat_core", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 700)
    public void bowTracksMovingTargetAndPauseDoesNotFire(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1344);
        var cow = f.mob(EntityType.COW, 11, 0);
        cow.setNoAi(true);
        f.body.addItem(new ItemStack(Items.BOW));
        f.body.addItem(new ItemStack(Items.ARROW, 16));
        f.start("BowAttack", cow);
        f.await(80, () -> f.body.isUsingItem() && f.body.getTicksUsingItem() >= 8, () -> {
            helper.assertTrue(f.registry.runtimePause(f.id, f.lease, 1).success(), "bow pause rejected");
            helper.assertTrue(!f.body.isUsingItem() && f.body.getInventory().countItem(Items.ARROW) == 16
                    && f.arrows() == 0, "pausing draw fired or consumed ammunition");
            helper.assertTrue(f.registry.runtimeResume(f.id, f.lease, 1).success(), "bow resume rejected");
            f.moveTarget(cow, 0);
            f.await(500, () -> f.snapshot().behaviorState().equals("IDLE"), () -> {
                helper.assertTrue(cow.isDeadOrDying(), "bow completed without real damage/death");
                helper.assertTrue(f.moved > 1, "bow fixture never moved");
                helper.assertTrue(f.body.getInventory().countItem(Items.ARROW) < 16, "survival bow consumed no ammo");
                helper.assertTrue(Integer.parseInt(f.snapshot().behaviorObservation().details().get("shots")) >= 1,
                        "no vanilla arrow creation observed");
                f.finish(cow);
            });
        });
    }

    @GameTest(batch = "combat_core", template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void combatMissingAmmoLostIdentityAndBlockedPathFailHonestly(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1408);
        var cow = f.mob(EntityType.COW, 10, 0);
        cow.setNoAi(true);
        f.body.addItem(new ItemStack(Items.BOW));
        f.start("BowAttack", cow);
        f.awaitPaused(40, "AMMO_MISSING", () -> {
            f.start("MeleeAttack", cow);
            cow.discard();
            f.awaitPaused(130, "TARGET_LOST_TIMEOUT", () -> {
                var target = f.mob(EntityType.COW, 8, 0);
                target.setNoAi(true);
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                    if (Math.abs(x) == 2 || Math.abs(z) == 2)
                        for (int y = 0; y < 4; y++) f.body.serverLevel().setBlockAndUpdate(
                                target.blockPosition().offset(x, y, z), Blocks.STONE.defaultBlockState());
                f.start("MeleeAttack", target);
                f.awaitPaused(180, "TARGET_UNREACHABLE", () -> {
                    helper.assertTrue(target.getHealth() == target.getMaxHealth(), "blocked target was damaged");
                    helper.assertTrue(!f.body.isUsingItem() && f.body.zza == 0, "failure left input active");
                    f.finish(target);
                });
            });
        });
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final CompanionRegistry registry;
        final ServerPlayer owner;
        final CompanionPlayer body;
        final FakeConnection connection = new FakeConnection();
        final String id;
        final String lease = "combat-fixture";
        final BlockPos origin;
        boolean finished;
        double moved;
        Vec3 lastMotionPosition;

        Fixture(GameTestHelper helper, int coordinate) {
            this.helper = helper;
            owner = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "combat-" + coordinate), ClientInformation.createDefault());
            helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, CommonListenerCookie.createInitial(owner.getGameProfile(), false));
            registry = MinecraftAiCompanionFabric.integrationRegistryFor(helper.getLevel().getServer());
            helper.assertTrue(registry.create(owner, "Combat").success(), "body creation failed");
            body = registry.liveBodyForOwner(owner.getUUID());
            origin = new BlockPos(coordinate, 100, coordinate);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                body.serverLevel().setChunkForced((coordinate >> 4) + x, (coordinate >> 4) + z, true);
            for (int x = -6; x <= 18; x++) for (int z = -8; z <= 8; z++) {
                body.serverLevel().setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) body.serverLevel().setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                body.serverLevel().setBlockAndUpdate(origin.offset(x, 4, z), Blocks.STONE.defaultBlockState());
            }
            body.teleportTo(body.serverLevel(), coordinate + 0.5, 100, coordinate + 0.5, 0, 0);
            owner.teleportTo(owner.serverLevel(), coordinate - 4.5, 100, coordinate + 0.5, 0, 0);
            body.setDeltaMovement(Vec3.ZERO);
            id = body.getUUID().toString();
            helper.assertTrue(registry.runtimeAcquireLease(id, lease, 1,
                    System.currentTimeMillis() + 120_000).success(), "combat lease failed");
        }

        <T extends Mob> T mob(EntityType<T> type, int x, int z) {
            T mob = type.create(body.serverLevel());
            mob.moveTo(origin.getX() + x + 0.5, origin.getY(), origin.getZ() + z + 0.5, 0, 0);
            mob.setPersistenceRequired();
            helper.assertTrue(body.serverLevel().addFreshEntity(mob), "fixture target could not spawn");
            return mob;
        }

        void start(String capability, LivingEntity target) {
            helper.assertTrue(registry.runtimeStart(id, lease, 1, "combat-" + UUID.randomUUID(), "skill",
                    null, null, null, new SkillParameters(capability, target.getUUID().toString(), "UUID", "",
                            null, null, null, null)).success(), "combat start rejected");
        }

        CompanionRegistry.RuntimeSnapshot snapshot() {
            return registry.runtimeSnapshots(false).stream().filter(s -> s.companionId().equals(id)).findFirst().orElseThrow();
        }

        void await(int remaining, BooleanSupplier condition, Runnable done) {
            if (condition.getAsBoolean()) { done.run(); return; }
            helper.assertTrue(remaining > 0 && snapshot().behaviorState().equals("RUNNING"),
                    "combat blocked: " + snapshot().behaviorState() + " " + snapshot().behaviorObservation());
            helper.runAfterDelay(1, () -> await(remaining - 1, condition, done));
        }

        void awaitPaused(int remaining, String code, Runnable done) {
            await(remaining, () -> snapshot().behaviorState().equals("PAUSED"), () -> {
                helper.assertTrue(snapshot().behaviorObservation().failureCode().equals(code),
                        "wrong failure: " + snapshot().behaviorObservation());
                done.run();
            });
        }

        int arrows() { return body.serverLevel().getEntitiesOfClass(AbstractArrow.class,
                body.getBoundingBox().inflate(24), arrow -> body.equals(arrow.getOwner())).size(); }

        void moveTarget(Mob target, int tick) {
            if (finished || !target.isAlive()) return;
            // Fixture-only movement uses collision-aware vanilla move; no-AI mobs do not integrate input.
            target.move(net.minecraft.world.entity.MoverType.SELF,
                    new Vec3(0, 0, tick % 50 < 25 ? 0.12 : -0.12));
            if (lastMotionPosition != null) moved += target.position().distanceTo(lastMotionPosition);
            lastMotionPosition = target.position();
            helper.runAfterDelay(1, () -> moveTarget(target, tick + 1));
        }

        void finish(LivingEntity target) {
            finished = true;
            target.discard();
            registry.runtimeCancel(id, lease, 1);
            helper.assertTrue(registry.remove(owner).success(), "combat cleanup failed");
            helper.getLevel().getServer().getPlayerList().remove(owner);
            connection.disconnect(Component.literal("Combat GameTest complete"));
            helper.succeed();
        }
    }
}
