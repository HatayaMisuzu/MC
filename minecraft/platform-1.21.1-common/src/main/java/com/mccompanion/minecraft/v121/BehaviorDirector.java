package com.mccompanion.minecraft.v121;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Starts, ticks, pauses, resumes and terminates the local Alpha movement behaviors. */
final class BehaviorDirector {
    private static final int STUCK_TICKS = 80;
    private static final int MAX_REPLANS = 3;
    private static final int AVOIDANCE_TICKS = 35;
    private static final int BEHAVIOR_TIMEOUT_TICKS = 20 * 60 * 5;
    private static final double ARRIVAL_DISTANCE_SQUARED = 1.5D * 1.5D;
    private static final double FOLLOW_DISTANCE_SQUARED = 3.0D * 3.0D;
    private static final float[] REPLAN_YAW_OFFSETS = {45.0F, -45.0F, 90.0F};

    private final MinecraftServer server;
    private final CompanionSavedData savedData;
    private final Logger logger;
    private final PlayerActionGateway actionGateway = new PlayerActionGateway();
    private final ReflexController reflexController = new ReflexController();
    private final Map<UUID, NavigationProgress> navigation = new HashMap<>();

    BehaviorDirector(MinecraftServer server, CompanionSavedData savedData, Logger logger) {
        this.server = server;
        this.savedData = savedData;
        this.logger = logger;
    }

    void start(CompanionEntry entry, CompanionPlayer body) {
        navigation.put(entry.companionId, new NavigationProgress(body.position(), server.getTickCount()));
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void stop(CompanionEntry entry, CompanionPlayer body, boolean success, String code) {
        actionGateway.stopInput(body);
        actionGateway.completeBehavior(body, success, code, server.getTickCount());
        navigation.remove(entry.companionId);
    }

    void forget(UUID companionId) {
        navigation.remove(companionId);
        actionGateway.discard(companionId);
    }

    String evidenceSummary(UUID companionId) {
        return actionGateway.evidenceSummary(companionId);
    }

    void tick(CompanionEntry entry, CompanionPlayer body) {
        if (entry.mode == CompanionEntry.Mode.IDLE || entry.mode == CompanionEntry.Mode.PAUSED) {
            return;
        }
        var reflex = reflexController.blockingReason(body);
        if (reflex.isPresent()) {
            pauseSafely(entry, body, reflex.get());
            return;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
        Vec3 target;
        double arrivalDistanceSquared;
        if (entry.mode == CompanionEntry.Mode.FOLLOW) {
            if (owner == null) {
                pauseSafely(entry, body, "OWNER_OFFLINE");
                return;
            }
            if (owner.serverLevel() != body.serverLevel()) {
                pauseSafely(entry, body, "WORLD_CHANGED");
                return;
            }
            target = owner.position();
            arrivalDistanceSquared = FOLLOW_DISTANCE_SQUARED;
        } else if (entry.mode == CompanionEntry.Mode.GOTO && entry.hasTarget) {
            target = new Vec3(entry.targetX, entry.targetY, entry.targetZ);
            arrivalDistanceSquared = ARRIVAL_DISTANCE_SQUARED;
        } else {
            pauseSafely(entry, body, "INVALID_BEHAVIOR_STATE");
            return;
        }

        NavigationProgress progress = navigation.computeIfAbsent(
                entry.companionId,
                ignored -> new NavigationProgress(body.position(), server.getTickCount()));
        if (server.getTickCount() - progress.startedTick > BEHAVIOR_TIMEOUT_TICKS) {
            pauseSafely(entry, body, "BEHAVIOR_TIMEOUT");
            return;
        }

        Vec3 delta = target.subtract(body.position());
        if (delta.lengthSqr() <= arrivalDistanceSquared) {
            stop(entry, body, true, "NONE");
            if (entry.mode == CompanionEntry.Mode.GOTO) {
                entry.mode = CompanionEntry.Mode.IDLE;
                entry.resumeMode = CompanionEntry.Mode.IDLE;
                entry.hasTarget = false;
                savedData.changed();
            }
            return;
        }

        if (body.position().distanceToSqr(progress.lastProgressPosition) >= 0.05D * 0.05D) {
            progress.lastProgressPosition = body.position();
            progress.stagnantTicks = 0;
        } else if (++progress.stagnantTicks >= STUCK_TICKS) {
            if (progress.replanCount >= MAX_REPLANS) {
                pauseSafely(entry, body, "STUCK");
                return;
            }
            progress.yawOffset = REPLAN_YAW_OFFSETS[progress.replanCount++];
            progress.avoidanceTicks = AVOIDANCE_TICKS;
            progress.stagnantTicks = 0;
            progress.lastProgressPosition = body.position();
            logger.info("companion_replan companion={} attempt={} yawOffset={}",
                    entry.companionId,
                    progress.replanCount,
                    progress.yawOffset);
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        if (progress.avoidanceTicks > 0) {
            yaw += progress.yawOffset;
            progress.avoidanceTicks--;
        } else {
            progress.yawOffset = 0.0F;
        }
        boolean jumpRequested = delta.y > 0.6D || body.horizontalCollision;
        actionGateway.applyMoveInput(body, yaw, jumpRequested);
    }

    private void pauseSafely(CompanionEntry entry, CompanionPlayer body, String code) {
        entry.resumeMode = entry.mode;
        entry.mode = CompanionEntry.Mode.PAUSED;
        stop(entry, body, false, code);
        savedData.changed();
        logger.warn("companion_paused code={} owner={} companion={}", code, entry.ownerId, entry.companionId);
    }

    private static final class NavigationProgress {
        private Vec3 lastProgressPosition;
        private int stagnantTicks;
        private int replanCount;
        private int avoidanceTicks;
        private float yawOffset;
        private final int startedTick;

        private NavigationProgress(Vec3 lastProgressPosition, int startedTick) {
            this.lastProgressPosition = lastProgressPosition;
            this.startedTick = startedTick;
        }
    }
}
