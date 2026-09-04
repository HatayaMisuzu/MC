package com.mccompanion.minecraft.v120;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** REAL_MINECRAFT_GAMETEST. Target motion, reconnect and arena are fixtures; Body only uses player input. */
@GameTestHolder("minecraft_ai_companion")
@PrefixGameTestTemplate(false)
public final class FinalProductForgeGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 4500, batch = "final_product")
    public static void nonOwnerFollowCrossesTerrainReacquiresIdentityAndSurvivesThreat(GameTestHelper helper) {
        var f = new CombatForgeGameTests.Fixture(helper, 3072);
        f.owner.setGameMode(GameType.SURVIVAL);
        var level = helper.getLevel();
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        for (int x = 191; x <= 206; x++) for (int z = 191; z <= 193; z++) level.setChunkForced(x, z, true);
        for (int x = -5; x <= 214; x++) for (int z = -2; z <= 2; z++) {
            level.setBlockAndUpdate(f.origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y <= 4; y++) level.setBlockAndUpdate(f.origin.offset(x, y, z),
                    (Math.abs(z) == 2 || y == 4 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        for (int z = -1; z <= 1; z++) for (int y = 0; y < 3; y++)
            level.setBlockAndUpdate(f.origin.offset(20, y, z), Blocks.STONE.defaultBlockState());
        BlockPos door = f.origin.offset(20, 0, 0);
        var doorState = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlockAndUpdate(door, doorState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(door.above(), doorState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        for (int x = 34; x <= 39; x++) for (int z = -1; z <= 1; z++)
            level.setBlockAndUpdate(f.origin.offset(x, 0, z), Blocks.WATER.defaultBlockState());
        for (int x = 80; x <= 86; x++) for (int z = -1; z <= 1; z++)
            level.setBlockAndUpdate(f.origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        f.body.addItem(new ItemStack(Items.IRON_SWORD));
        ServerPlayer[] target = {player(helper, UUID.randomUUID(), Vec3.atBottomCenterOf(f.origin.offset(24, 0, 0)))};
        helper.assertTrue(!target[0].getUUID().equals(f.owner.getUUID()), "target must be a non-owner");
        helper.assertTrue(f.registry.runtimeStart(f.id, f.lease, 1, "long-non-owner-follow", "skill",
                null, null, null, new SkillParameters("FollowEntity", target[0].getUUID().toString(),
                "UUID", "", null, null, null, 80)).success(), "follow start failed");
        boolean[] observed = {false, false};
        observeTerrain(f, observed);
        followWaypoints(f, target[0], new int[]{24, 60, 100, 148, 196, 208}, 0, () -> {
            helper.assertTrue(observed[0] && observed[1], "Body did not actually cross water and elevation");
            helper.assertTrue(level.getBlockState(door).getValue(BlockStateProperties.OPEN), "door was not opened");
            Vec3 stopped = f.body.position();
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(f.body.position().distanceToSqr(stopped) < .5, "follow did not hold when target stopped");
                UUID identity = target[0].getUUID();
                level.getServer().getPlayerList().remove(target[0]);
                f.await(20, () -> "TARGET_TEMPORARILY_LOST".equals(f.snapshot().behaviorObservation().failureCode()), () -> {
                    target[0] = player(helper, identity, new Vec3(f.body.getX() - 12, 100, f.origin.getZ() + .5));
                    f.await(180, () -> f.body.distanceToSqr(target[0]) <= 9, () -> {
                        helper.assertTrue(f.registry.entityEventTarget(f.id).identity().equals(identity.toString()),
                                "reconnect changed target identity");
                        // Real owner damage triggers the shared finite defense; the follow identity must survive.
                        f.owner.teleportTo(level, f.body.getX() - 4, 100, f.origin.getZ() + .5, 0, 0);
                        var zombie = f.mob(EntityType.ZOMBIE, f.body.blockPosition().getX() - f.origin.getX() - 2, 0);
                        zombie.setNoAi(true);
                        helper.assertTrue(zombie.doHurtTarget(f.owner), "owner damage fixture did not take effect");
                        target[0].teleportTo(level, f.origin.getX() + 208.5, 100, f.origin.getZ() + .5, 0, 0);
                        f.await(500, () -> zombie.isDeadOrDying() && f.body.distanceToSqr(target[0]) <= 9, () -> {
                            helper.assertTrue("long-non-owner-follow".equals(f.snapshot().behaviorId()),
                                    "recovery replaced the original behavior");
                            level.getServer().getPlayerList().remove(target[0]);
                            f.finish(zombie);
                        });
                    });
                });
            });
        });
    }

    private static void observeTerrain(CombatForgeGameTests.Fixture f, boolean[] observed) {
        if (f.finished) return;
        observed[0] |= f.body.isInWater();
        observed[1] |= f.body.getX() > f.origin.getX() + 80 && f.body.getX() < f.origin.getX() + 87
                && f.body.getY() >= 100.9;
        f.helper.runAfterDelay(1, () -> observeTerrain(f, observed));
    }

    private static void followWaypoints(CombatForgeGameTests.Fixture f, ServerPlayer target, int[] waypoints, int index, Runnable done) {
        if (index == waypoints.length) { done.run(); return; }
        target.teleportTo(f.body.serverLevel(), f.origin.getX() + waypoints[index] + .5,
                100, f.origin.getZ() + .5, 0, 0);
        f.await(500, () -> f.body.distanceToSqr(target) <= 9,
                () -> followWaypoints(f, target, waypoints, index + 1, done));
    }

    private static ServerPlayer player(GameTestHelper helper, UUID identity, Vec3 position) {
        var profile = new GameProfile(identity, "follow-traveler");
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile);
        player.moveTo(position.x, position.y, position.z, 0, 0);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(new FakeConnection(), player);
        player.setGameMode(GameType.SPECTATOR);
        return player;
    }
}
