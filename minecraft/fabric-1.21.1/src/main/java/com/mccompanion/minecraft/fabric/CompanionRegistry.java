package com.mccompanion.minecraft.fabric;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.slf4j.Logger;

/** Owns all live companion bodies and their persistent behavior state for one dedicated server. */
final class CompanionRegistry {
    private final MinecraftServer server;
    private final Logger logger;
    private final CompanionSavedData savedData;
    private final Map<UUID, CompanionPlayer> liveBodies = new HashMap<>();
    private final BehaviorDirector behaviorDirector;
    private final CompanionDeathController deathController;

    CompanionRegistry(MinecraftServer server, Logger logger) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.savedData = server.overworld().getDataStorage().computeIfAbsent(
                CompanionSavedData.FACTORY,
                CompanionSavedData.STORAGE_ID);
        this.behaviorDirector = new BehaviorDirector(server, savedData, logger);
        this.deathController = new CompanionDeathController(logger);
    }

    void start() {
        for (CompanionEntry entry : new ArrayList<>(savedData.entries())) {
            if (entry.spawned) {
                spawnBody(entry, null);
            }
        }
        logger.info("Loaded {} companion record(s), {} live body/bodies", savedData.entries().size(), liveBodies.size());
    }

    void shutdown() {
        for (CompanionPlayer body : new ArrayList<>(liveBodies.values())) {
            body.stopWalking();
        }
        server.getPlayerList().saveAll();
        server.overworld().getDataStorage().save();
        liveBodies.clear();
    }

    Result create(ServerPlayer owner, String requestedName) {
        if (savedData.get(owner.getUUID()) != null) {
            return Result.failure("COMPANION_ALREADY_EXISTS", "You already own a companion; remove it first.");
        }
        String profileName = sanitizeProfileName(requestedName, owner.getGameProfile().getName());
        CompanionEntry entry = new CompanionEntry(UUID.randomUUID(), owner.getUUID(), profileName);
        savedData.put(entry);
        if (!spawnBody(entry, owner)) {
            savedData.remove(owner.getUUID());
            return Result.failure("SPAWN_FAILED", "The companion body could not enter this world.");
        }
        logger.info("companion_created owner={} companion={} profile={}", owner.getUUID(), entry.companionId, profileName);
        return Result.success("Created companion " + profileName + " (" + entry.companionId + ").");
    }

    Result spawn(ServerPlayer owner) {
        CompanionEntry entry = savedData.get(owner.getUUID());
        if (entry == null) {
            return notFound();
        }
        CompanionPlayer existingBody = liveBodies.get(entry.companionId);
        if (existingBody != null && !existingBody.isDeadOrDying()) {
            return Result.failure("COMPANION_ALREADY_SPAWNED", entry.profileName + " is already in the world.");
        }
        if (existingBody != null) {
            liveBodies.remove(entry.companionId);
            stopAndRemove(existingBody);
        }
        entry.spawned = true;
        entry.mode = CompanionEntry.Mode.IDLE;
        savedData.changed();
        if (!spawnBody(entry, owner)) {
            entry.spawned = false;
            savedData.changed();
            return Result.failure("SPAWN_FAILED", "The companion body could not enter this world.");
        }
        return Result.success("Spawned " + entry.profileName + ".");
    }

    Result despawn(ServerPlayer owner) {
        CompanionEntry entry = savedData.get(owner.getUUID());
        if (entry == null) {
            return notFound();
        }
        CompanionPlayer body = liveBodies.remove(entry.companionId);
        if (body == null) {
            entry.spawned = false;
            entry.mode = CompanionEntry.Mode.IDLE;
            savedData.changed();
            return Result.failure("COMPANION_NOT_SPAWNED", entry.profileName + " is already sleeping.");
        }
        stopAndRemove(body);
        entry.spawned = false;
        entry.mode = CompanionEntry.Mode.IDLE;
        entry.resumeMode = CompanionEntry.Mode.IDLE;
        savedData.changed();
        return Result.success("Put " + entry.profileName + " to sleep; position and inventory were saved.");
    }

    Result remove(ServerPlayer owner) {
        CompanionEntry entry = savedData.remove(owner.getUUID());
        if (entry == null) {
            return notFound();
        }
        CompanionPlayer body = liveBodies.remove(entry.companionId);
        if (body != null) {
            stopAndRemove(body);
        }
        behaviorDirector.forget(entry.companionId);
        logger.info("companion_removed owner={} companion={}", owner.getUUID(), entry.companionId);
        return Result.success("Removed companion " + entry.profileName + ". Existing vanilla player-data is retained for recovery.");
    }

    Result follow(ServerPlayer owner) {
        CompanionEntry entry = requireLiveEntry(owner);
        if (entry == null) {
            return missingOrSleeping(owner);
        }
        entry.mode = CompanionEntry.Mode.FOLLOW;
        entry.resumeMode = CompanionEntry.Mode.FOLLOW;
        entry.hasTarget = false;
        savedData.changed();
        behaviorDirector.start(entry, liveBodies.get(entry.companionId));
        return Result.success(entry.profileName + " is following you using collision-aware player movement.");
    }

    Result goTo(ServerPlayer owner, double x, double y, double z) {
        CompanionEntry entry = requireLiveEntry(owner);
        if (entry == null) {
            return missingOrSleeping(owner);
        }
        entry.mode = CompanionEntry.Mode.GOTO;
        entry.resumeMode = CompanionEntry.Mode.GOTO;
        entry.hasTarget = true;
        entry.targetX = x;
        entry.targetY = y;
        entry.targetZ = z;
        savedData.changed();
        behaviorDirector.start(entry, liveBodies.get(entry.companionId));
        return Result.success(entry.profileName + " is walking to " + formatPosition(x, y, z) + ".");
    }

    Result come(ServerPlayer owner) {
        CompanionEntry entry = requireLiveEntry(owner);
        if (entry == null) {
            return missingOrSleeping(owner);
        }
        if (liveBodies.get(entry.companionId).serverLevel() != owner.serverLevel()) {
            return Result.failure("WORLD_CHANGED", "Companion and owner must be in the same dimension.");
        }
        return goTo(owner, owner.getX(), owner.getY(), owner.getZ());
    }

    Result stop(ServerPlayer owner) {
        CompanionEntry entry = requireLiveEntry(owner);
        if (entry == null) {
            return missingOrSleeping(owner);
        }
        CompanionPlayer body = liveBodies.get(entry.companionId);
        behaviorDirector.stop(entry, body, false, "CANCELLED_BY_OWNER");
        entry.mode = CompanionEntry.Mode.IDLE;
        entry.resumeMode = CompanionEntry.Mode.IDLE;
        entry.hasTarget = false;
        savedData.changed();
        return Result.success("Stopped " + entry.profileName + ".");
    }

    Result pause(ServerPlayer owner) {
        CompanionEntry entry = requireLiveEntry(owner);
        if (entry == null) {
            return missingOrSleeping(owner);
        }
        if (entry.mode == CompanionEntry.Mode.PAUSED) {
            return Result.failure("ALREADY_PAUSED", entry.profileName + " is already paused.");
        }
        entry.resumeMode = entry.mode;
        entry.mode = CompanionEntry.Mode.PAUSED;
        behaviorDirector.stop(entry, liveBodies.get(entry.companionId), false, "PAUSED_BY_OWNER");
        savedData.changed();
        return Result.success("Paused " + entry.profileName + ".");
    }

    Result resume(ServerPlayer owner) {
        CompanionEntry entry = requireLiveEntry(owner);
        if (entry == null) {
            return missingOrSleeping(owner);
        }
        if (entry.mode != CompanionEntry.Mode.PAUSED) {
            return Result.failure("NOT_PAUSED", entry.profileName + " is not paused.");
        }
        entry.mode = entry.resumeMode == CompanionEntry.Mode.PAUSED
                ? CompanionEntry.Mode.IDLE
                : entry.resumeMode;
        savedData.changed();
        if (entry.mode != CompanionEntry.Mode.IDLE) {
            behaviorDirector.start(entry, liveBodies.get(entry.companionId));
        }
        return Result.success("Resumed " + entry.profileName + " in " + entry.mode + " mode.");
    }

    String status(ServerPlayer owner) {
        CompanionEntry entry = savedData.get(owner.getUUID());
        if (entry == null) {
            return "COMPANION_NOT_FOUND: use /companion create [name].";
        }
        CompanionPlayer body = liveBodies.get(entry.companionId);
        if (body == null) {
            return entry.profileName + " id=" + entry.companionId + " state=SLEEPING mode=" + entry.mode;
        }
        return entry.profileName + " id=" + entry.companionId
                + " state=SPAWNED mode=" + entry.mode
                + " dimension=" + body.serverLevel().dimension().location()
                + " position=" + formatPosition(body.getX(), body.getY(), body.getZ())
                + " discardedPackets=" + body.fakeConnection().discardedPacketCount()
                + " " + behaviorDirector.evidenceSummary(entry.companionId);
    }

    String globalStatus() {
        return "records=" + savedData.entries().size() + " liveBodies=" + liveBodies.size()
                + " runtime=OFFLINE localControl=AVAILABLE";
    }

    /** Package-scoped integration seam used by the headless GameTest module. */
    CompanionPlayer liveBodyForOwner(UUID ownerId) {
        CompanionEntry entry = savedData.get(ownerId);
        return entry == null ? null : liveBodies.get(entry.companionId);
    }

    void tick() {
        for (CompanionEntry entry : new ArrayList<>(savedData.entries())) {
            CompanionPlayer body = liveBodies.get(entry.companionId);
            if (!entry.spawned) {
                continue;
            }
            if (body == null || body.isRemoved()) {
                spawnBody(entry, null);
                continue;
            }
            if (deathController.isDeathReadyForRecovery(body)) {
                deathController.recordDeath(entry, body);
                behaviorDirector.stop(entry, body, false, "BODY_DEAD");
                entry.resumeMode = CompanionEntry.Mode.IDLE;
                entry.mode = CompanionEntry.Mode.PAUSED;
                entry.spawned = false;
                entry.deathPendingRecovery = true;
                liveBodies.remove(entry.companionId);
                stopAndRemove(body);
                savedData.changed();
                continue;
            }
            behaviorDirector.tick(entry, body);
            // Real client players are advanced from ServerGamePacketListenerImpl.tick(). This packetless body has no
            // network listener in MinecraftServer's connection list, so advance its normal Player tick exactly once.
            body.doTick();
            if (body.connection != null) {
                body.connection.resetPosition();
            }
        }
    }

    private boolean spawnBody(CompanionEntry entry, ServerPlayer spawnBeside) {
        if (liveBodies.containsKey(entry.companionId)) {
            return true;
        }
        try {
            ServerLevel initialLevel = spawnBeside == null ? server.overworld() : spawnBeside.serverLevel();
            FakeConnection connection = new FakeConnection();
            CompanionPlayer body = new CompanionPlayer(
                    server,
                    initialLevel,
                    new GameProfile(entry.companionId, entry.profileName),
                    entry.ownerId,
                    connection);
            if (spawnBeside != null) {
                // Spawn placement is isolated here; all behavior ticks use travel(...) and never teleport/setPos.
                body.moveTo(spawnBeside.getX(), spawnBeside.getY(), spawnBeside.getZ(), spawnBeside.getYRot(), 0.0F);
            }
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(body.getGameProfile(), false);
            server.getPlayerList().placeNewPlayer(connection, body, cookie);
            if (entry.deathPendingRecovery) {
                // Lifecycle-only recovery: vanilla already performed drops and saved that inventory before this load.
                // Restore health and place the newly constructed ServerPlayer body beside its owner; behaviors never
                // call these placement methods.
                body.setHealth(body.getMaxHealth());
                if (spawnBeside != null) {
                    body.moveTo(spawnBeside.getX(), spawnBeside.getY(), spawnBeside.getZ(), spawnBeside.getYRot(), 0.0F);
                }
                entry.deathPendingRecovery = false;
                savedData.changed();
            }
            liveBodies.put(entry.companionId, body);
            logger.info("companion_body_spawned owner={} companion={} dimension={} position={}",
                    entry.ownerId,
                    entry.companionId,
                    body.serverLevel().dimension().location(),
                    formatPosition(body.getX(), body.getY(), body.getZ()));
            return true;
        } catch (RuntimeException exception) {
            logger.error("companion_body_spawn_failed owner={} companion={}", entry.ownerId, entry.companionId, exception);
            return false;
        }
    }

    private void stopAndRemove(CompanionPlayer body) {
        body.stopWalking();
        behaviorDirector.forget(body.getUUID());
        if (!body.isRemoved()) {
            server.getPlayerList().remove(body);
        }
        body.fakeConnection().disconnect(Component.literal("Companion body sleeping"));
    }

    private CompanionEntry requireLiveEntry(ServerPlayer owner) {
        CompanionEntry entry = savedData.get(owner.getUUID());
        if (entry == null || !entry.spawned || !liveBodies.containsKey(entry.companionId)) {
            return null;
        }
        return entry;
    }

    private Result missingOrSleeping(ServerPlayer owner) {
        CompanionEntry entry = savedData.get(owner.getUUID());
        return entry == null
                ? notFound()
                : Result.failure("COMPANION_NOT_SPAWNED", entry.profileName + " is sleeping; use /companion spawn.");
    }

    private static Result notFound() {
        return Result.failure("COMPANION_NOT_FOUND", "No companion is registered for this owner.");
    }

    private static String sanitizeProfileName(String requested, String ownerName) {
        String source = requested == null || requested.isBlank() ? "AI_" + ownerName : requested;
        String sanitized = source.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isBlank()) {
            sanitized = "AI_Companion";
        }
        return sanitized.substring(0, Math.min(16, sanitized.length()));
    }

    private static String formatPosition(double x, double y, double z) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", x, y, z);
    }

    record Result(boolean success, String code, String message) {
        static Result success(String message) {
            return new Result(true, "OK", message);
        }

        static Result failure(String code, String message) {
            return new Result(false, code, message);
        }

        Component component() {
            return Component.literal(success ? message : code + ": " + message);
        }
    }

}
