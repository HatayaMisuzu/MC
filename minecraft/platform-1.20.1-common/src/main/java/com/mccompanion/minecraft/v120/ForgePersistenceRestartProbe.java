package com.mccompanion.minecraft.v120;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Explicitly enabled development probe for a two-process Forge dedicated-server restart.
 *
 * <p>The probe is inert unless {@code mccompanion.persistence.probe} is set to {@code seed} or
 * {@code verify}. It uses only the normal registry lifecycle and clean server shutdown path.
 */
public final class ForgePersistenceRestartProbe {
    private static final UUID OWNER_ID = UUID.fromString("176723bf-7cc7-4ec2-9c99-25826567703d");
    private static final String PROPERTY = "mccompanion.persistence.probe";
    private static String mode = "";
    private static int ticks;

    private ForgePersistenceRestartProbe() {
    }

    public static void begin(MinecraftServer server, CompanionRegistry registry, Logger logger) {
        mode = System.getProperty(PROPERTY, "").trim();
        ticks = 0;
        if (mode.isEmpty()) {
            return;
        }
        if ("seed".equals(mode)) {
            seed(server, registry);
            return;
        }
        if ("verify".equals(mode)) {
            verify(server, registry, logger);
            return;
        }
        throw new IllegalStateException("Unsupported Forge persistence probe mode: " + mode);
    }

    public static void tick(MinecraftServer server, CompanionRegistry registry, Logger logger) {
        if (mode.isEmpty()) {
            return;
        }
        ticks++;
        if ("seed".equals(mode) && ticks == 5) {
            ServerPlayer owner = server.getPlayerList().getPlayer(OWNER_ID);
            require(owner != null, "seed owner disappeared before shutdown");
            require(registry.status(owner).contains("mode=GOTO"),
                    "navigation was not in flight before shutdown");
            logger.info("MCAC_FORGE_PERSISTENCE_SEED_READY");
            mode = "";
            server.halt(false);
        } else if ("verify".equals(mode) && ticks == 2) {
            mode = "";
            server.halt(false);
        }
    }

    private static void seed(MinecraftServer server, CompanionRegistry registry) {
        require(registry.runtimeSnapshots(false).isEmpty(),
                "seed world unexpectedly contains companion records");
        ServerLevel level = server.overworld();
        ServerPlayer owner = new ServerPlayer(
                server,
                level,
                new GameProfile(OWNER_ID, "forge-restart-owner"));
        BlockPos spawn = level.getSharedSpawnPos().above(2);
        owner.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, 0.0F, 0.0F);
        server.getPlayerList().placeNewPlayer(new FakeConnection(), owner);

        CompanionRegistry.Result created = registry.create(owner, "ForgeRestart");
        require(created.success(), "companion creation failed: " + created.code());
        CompanionPlayer body = registry.liveBodyForOwner(OWNER_ID);
        require(body != null, "companion body was not created");
        require(body.addItem(new ItemStack(Items.DIAMOND)),
                "seed inventory did not accept the diamond");

        BlockPos origin = body.blockPosition();
        for (int x = -1; x <= 100; x++) {
            level.setBlockAndUpdate(origin.offset(x, -1, 0), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, 0, 0), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, 1, 0), Blocks.AIR.defaultBlockState());
        }
        Vec3 target = Vec3.atBottomCenterOf(origin.offset(80, 0, 0));
        CompanionRegistry.Result moving = registry.goTo(owner, target.x, target.y, target.z);
        require(moving.success(), "navigation did not start: " + moving.code());
    }

    private static void verify(MinecraftServer server, CompanionRegistry registry, Logger logger) {
        var snapshots = registry.runtimeSnapshots(false);
        require(snapshots.size() == 1, "restart did not recover exactly one companion record");
        var snapshot = snapshots.get(0);
        require(snapshot.ownerId().equals(OWNER_ID.toString()), "restart changed owner identity");
        CompanionPlayer body = registry.liveBodyForOwner(OWNER_ID);
        require(body != null, "restart did not restore the live companion body");
        require(body.getUUID().toString().equals(snapshot.companionId()),
                "restart changed companion UUID");
        require(body.getInventory().contains(new ItemStack(Items.DIAMOND)),
                "restart lost companion inventory");
        require(snapshot.behaviorState().equals("PAUSED"),
                "restart did not quarantine in-flight navigation");
        logger.info("MCAC_FORGE_PERSISTENCE_RESTART_VERIFIED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Forge persistence probe failed: " + message);
        }
    }
}
