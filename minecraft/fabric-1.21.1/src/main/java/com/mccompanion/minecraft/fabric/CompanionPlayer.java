package com.mccompanion.minecraft.fabric;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** A real server-side player body controlled by the companion registry. */
public final class CompanionPlayer extends ServerPlayer {
    private static final String NBT_MARKER = "MinecraftAiCompanion";
    private static final String NBT_OWNER = "MinecraftAiCompanionOwner";

    private UUID ownerId;
    private final FakeConnection fakeConnection;

    public CompanionPlayer(
            MinecraftServer server,
            ServerLevel initialLevel,
            GameProfile profile,
            UUID ownerId,
            FakeConnection fakeConnection) {
        super(server, initialLevel, profile, ClientInformation.createDefault());
        this.ownerId = ownerId;
        this.fakeConnection = fakeConnection;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public FakeConnection fakeConnection() {
        return fakeConnection;
    }

    /** Sets ordinary player input; {@link #doTick()} applies vanilla travel, collision and movement attributes. */
    public void applyWalkingInput(float yawDegrees, boolean jumpRequested) {
        setYRot(yawDegrees);
        setYHeadRot(yawDegrees);
        setXRot(0.0F);
        xxa = 0.0F;
        zza = 1.0F;
        setJumping((jumpRequested || horizontalCollision) && onGround());
        setShiftKeyDown(false);
    }

    public void stopWalking() {
        xxa = 0.0F;
        zza = 0.0F;
        setJumping(false);
        setShiftKeyDown(false);
        Vec3 velocity = getDeltaMovement();
        setDeltaMovement(0.0D, velocity.y, 0.0D);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(NBT_MARKER, true);
        tag.putUUID(NBT_OWNER, ownerId);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.getBoolean(NBT_MARKER) && tag.hasUUID(NBT_OWNER)) {
            ownerId = tag.getUUID(NBT_OWNER);
        }
    }
}
