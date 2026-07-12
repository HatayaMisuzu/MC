package com.mccompanion.minecraft.fabric;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

final class CompanionEntry {
    enum Mode {
        IDLE,
        FOLLOW,
        GOTO,
        PAUSED
    }

    final UUID companionId;
    final UUID ownerId;
    final String profileName;
    boolean spawned;
    Mode mode;
    Mode resumeMode;
    boolean hasTarget;
    boolean deathPendingRecovery;
    double targetX;
    double targetY;
    double targetZ;

    CompanionEntry(UUID companionId, UUID ownerId, String profileName) {
        this.companionId = Objects.requireNonNull(companionId, "companionId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.profileName = requireProfileName(profileName);
        this.spawned = true;
        this.mode = Mode.IDLE;
        this.resumeMode = Mode.IDLE;
    }

    static CompanionEntry load(CompoundTag tag) {
        CompanionEntry entry = new CompanionEntry(
                tag.getUUID("companionId"),
                tag.getUUID("ownerId"),
                tag.getString("profileName"));
        entry.spawned = tag.getBoolean("spawned");
        entry.mode = parseMode(tag.getString("mode"), Mode.IDLE);
        entry.resumeMode = parseMode(tag.getString("resumeMode"), Mode.IDLE);
        entry.hasTarget = tag.getBoolean("hasTarget");
        entry.deathPendingRecovery = tag.getBoolean("deathPendingRecovery");
        entry.targetX = tag.getDouble("targetX");
        entry.targetY = tag.getDouble("targetY");
        entry.targetZ = tag.getDouble("targetZ");
        return entry;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("companionId", companionId);
        tag.putUUID("ownerId", ownerId);
        tag.putString("profileName", profileName);
        tag.putBoolean("spawned", spawned);
        tag.putString("mode", mode.name());
        tag.putString("resumeMode", resumeMode.name());
        tag.putBoolean("hasTarget", hasTarget);
        tag.putBoolean("deathPendingRecovery", deathPendingRecovery);
        tag.putDouble("targetX", targetX);
        tag.putDouble("targetY", targetY);
        tag.putDouble("targetZ", targetZ);
        return tag;
    }

    private static Mode parseMode(String value, Mode fallback) {
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String requireProfileName(String name) {
        Objects.requireNonNull(name, "profileName");
        if (name.isBlank() || name.length() > 16) {
            throw new IllegalArgumentException("profileName must contain 1..16 characters");
        }
        return name;
    }
}
