package com.mccompanion.minecraft.v121;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.Container;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.network.CommonListenerCookie;
import org.slf4j.Logger;

/** Owns all live companion bodies and their persistent behavior state for one dedicated server. */
public final class CompanionRegistry {
    private final MinecraftServer server;
    private final Logger logger;
    private final CompanionSavedData savedData;
    private final Map<UUID, CompanionPlayer> liveBodies = new HashMap<>();
    private final Map<UUID, RuntimeControl> runtimeControls = new HashMap<>();
    private long runtimeCommandCount;
    private String runtimeLastPublishedBehaviorId;
    private boolean runtimeConnected;
    private final BehaviorDirector behaviorDirector;
    private final CompanionDeathController deathController;

    public CompanionRegistry(MinecraftServer server, Logger logger) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.savedData = server.overworld().getDataStorage().computeIfAbsent(
                CompanionSavedData.FACTORY,
                CompanionSavedData.STORAGE_ID);
        this.behaviorDirector = new BehaviorDirector(server, savedData, logger);
        this.deathController = new CompanionDeathController(logger);
    }

    public void start() {
        for (CompanionEntry entry : new ArrayList<>(savedData.entries())) {
            if (entry.spawned) {
                spawnBody(entry, null);
            }
        }
        logger.info("Loaded {} companion record(s), {} live body/bodies", savedData.entries().size(), liveBodies.size());
    }

    public void shutdown() {
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
        return removeByOwnerId(owner.getUUID());
    }

    Result removeByOwnerId(UUID ownerId) {
        CompanionEntry entry = savedData.remove(ownerId);
        if (entry == null) {
            return notFound();
        }
        CompanionPlayer body = liveBodies.remove(entry.companionId);
        if (body != null) {
            stopAndRemove(body);
        }
        behaviorDirector.forget(entry.companionId);
        runtimeControls.remove(entry.companionId);
        logger.info("companion_removed owner={} companion={}", ownerId, entry.companionId);
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
                + " runtime=" + (runtimeConnected ? "ONLINE" : "OFFLINE")
                + " " + behaviorDirector.evidenceSummary(entry.companionId);
    }

    public String globalStatus() {
        return "records=" + savedData.entries().size() + " liveBodies=" + liveBodies.size()
                + " runtime=" + (runtimeConnected ? "ONLINE" : "OFFLINE") + " localControl=AVAILABLE";
    }

    public java.util.List<RuntimeSnapshot> runtimeSnapshots(boolean runtimeConnected) {
        java.util.List<RuntimeSnapshot> snapshots = new ArrayList<>();
        for (CompanionEntry entry : savedData.entries()) {
            CompanionPlayer body = liveBodies.get(entry.companionId);
            RuntimeControl control = runtimeControls.get(entry.companionId);
            java.util.Map<String, Integer> inventory = new java.util.TreeMap<>();
            int freeSlots = 0;
            if (body != null) {
                for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
                    var stack = body.getInventory().getItem(slot);
                    if (stack.isEmpty()) freeSlots++;
                    else inventory.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                            stack.getCount(), Integer::sum);
                }
            }
            snapshots.add(new RuntimeSnapshot(
                    entry.companionId.toString(),
                    entry.ownerId.toString(),
                    entry.profileName,
                    body == null ? "minecraft:overworld" : body.serverLevel().dimension().location().toString(),
                    body == null ? 0.0D : body.getX(),
                    body == null ? 0.0D : body.getY(),
                    body == null ? 0.0D : body.getZ(),
                    body == null ? "SLEEPING" : "SPAWNED",
                    control == null ? null : control.behaviorId,
                    behaviorState(entry),
                    control == null || control.behaviorId == null ? 0L : control.behaviorRevision,
                    control == null ? 0L : control.epoch,
                    runtimeConnected,
                    body == null ? 0.0F : body.getHealth(),
                    body == null ? 0.0F : body.getMaxHealth(),
                    body == null ? 0 : body.getFoodData().getFoodLevel(),
                    body == null ? 0 : body.getAirSupply(),
                    body != null && body.isOnFire(),
                    body != null && body.isInLava(),
                    freeSlots,
                    java.util.Map.copyOf(inventory),
                    body == null ? java.util.List.of() : visibleContainers(body),
                    behaviorDirector.evidenceSummary(entry.companionId),
                    behaviorDirector.behaviorObservation(entry.companionId)));
        }
        return java.util.List.copyOf(snapshots);
    }

    /** Returns only the live authenticated Runtime body; callers must remain on the server thread. */
    public CompanionPlayer runtimeBody(String companionId) {
        CompanionEntry entry = entryByCompanion(companionId);
        return entry == null ? null : liveBodies.get(entry.companionId);
    }

    private static java.util.List<ContainerSnapshot> visibleContainers(CompanionPlayer body) {
        java.util.List<ContainerSnapshot> visible = new ArrayList<>();
        BlockPos origin = body.blockPosition();
        for (BlockPos position : BlockPos.betweenClosed(origin.offset(-5, -3, -5), origin.offset(5, 3, 5))) {
            if (visible.size() >= 16 || !body.serverLevel().hasChunkAt(position)) continue;
            var blockEntity = body.serverLevel().getBlockEntity(position);
            if (!(blockEntity instanceof Container)) continue;
            Vec3 target = Vec3.atCenterOf(position);
            var hit = body.serverLevel().clip(new ClipContext(body.getEyePosition(), target,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(position)) continue;
            var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            visible.add(new ContainerSnapshot(key.toString(), body.serverLevel().dimension().location().toString(),
                    position.getX(), position.getY(), position.getZ()));
        }
        return java.util.List.copyOf(visible);
    }

    public RuntimeResult runtimeAcquireLease(
            String companionId,
            String proposedLeaseId,
            long proposedEpoch,
            long expiresAt) {
        CompanionEntry entry = entryByCompanion(companionId);
        if (entry == null) return RuntimeResult.failure("COMPANION_NOT_FOUND");
        if (proposedLeaseId == null || proposedLeaseId.isBlank() || proposedEpoch <= 0 || expiresAt <= System.currentTimeMillis()) {
            return RuntimeResult.failure("INVALID_LEASE");
        }
        RuntimeControl previous = runtimeControls.get(entry.companionId);
        if (previous != null && proposedEpoch <= previous.epoch) {
            return RuntimeResult.failure("STALE_EPOCH");
        }
        RuntimeControl control = new RuntimeControl(proposedLeaseId, proposedEpoch, expiresAt);
        runtimeControls.put(entry.companionId, control);
        return RuntimeResult.success(null, control.behaviorRevision, "IDLE");
    }

    public RuntimeResult runtimeRenewLease(String companionId, String leaseId, long epoch, long expiresAt) {
        CompanionEntry entry = entryByCompanion(companionId);
        RuntimeControl control = entry == null ? null : runtimeControls.get(entry.companionId);
        if (!validLease(control, leaseId, epoch)) return RuntimeResult.failure("STALE_EPOCH");
        if (expiresAt <= System.currentTimeMillis()) return RuntimeResult.failure("LEASE_EXPIRED");
        control.expiresAt = Math.max(control.expiresAt, expiresAt);
        return RuntimeResult.success(control.behaviorId, control.behaviorRevision, behaviorState(entry));
    }

    public RuntimeResult runtimeStart(
            String companionId,
            String leaseId,
            long epoch,
            String behaviorId,
            String behaviorType,
            Double x,
            Double y,
            Double z,
            SkillParameters skill) {
        CompanionEntry entry = entryByCompanion(companionId);
        RuntimeControl control = entry == null ? null : runtimeControls.get(entry.companionId);
        RuntimeResult leaseFailure = checkLease(control, leaseId, epoch);
        if (leaseFailure != null) return leaseFailure;
        CompanionPlayer body = liveBodies.get(entry.companionId);
        if (body == null) return RuntimeResult.failure("COMPANION_NOT_SPAWNED");
        if (behaviorId == null || behaviorId.isBlank()) return RuntimeResult.failure("INVALID_BEHAVIOR_ID");
        String normalized = behaviorType == null ? "" : behaviorType.toLowerCase(Locale.ROOT);
        if (normalized.equals("follow")) {
            entry.mode = CompanionEntry.Mode.FOLLOW;
            entry.resumeMode = CompanionEntry.Mode.FOLLOW;
            entry.hasTarget = false;
        } else if (normalized.equals("goto") || normalized.equals("travel")) {
            if (x == null || y == null || z == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return RuntimeResult.failure("INVALID_TARGET");
            }
            entry.mode = CompanionEntry.Mode.GOTO;
            entry.resumeMode = CompanionEntry.Mode.GOTO;
            entry.hasTarget = true;
            entry.targetX = x;
            entry.targetY = y;
            entry.targetZ = z;
        } else if (normalized.equals("return")) {
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
            if (owner == null) return RuntimeResult.failure("OWNER_OFFLINE");
            if (owner.serverLevel() != body.serverLevel()) return RuntimeResult.failure("WORLD_CHANGED");
            entry.mode = CompanionEntry.Mode.GOTO;
            entry.resumeMode = CompanionEntry.Mode.GOTO;
            entry.hasTarget = true;
            entry.targetX = owner.getX();
            entry.targetY = owner.getY();
            entry.targetZ = owner.getZ();
        } else if (normalized.equals("skill") && skill != null) {
            if (!skill.capability().equals("DeliverItem") && !skill.capability().equals("EatAndRecover")
                    && !skill.capability().equals("WithdrawFromStorage")
                    && !skill.capability().equals("DepositToStorage")
                    && !skill.capability().equals("CraftItem")
                    && !skill.capability().equals("ExploreArea")
                    && !skill.capability().equals("CollectResource")
                    && !skill.capability().equals("MineResourceVein")
                    && !skill.capability().equals("SmeltItem")
                    && !skill.capability().equals("DefendOwner")
                    && !skill.capability().equals("LookAt")
                    && !skill.capability().equals("InteractBlock")
                    && !skill.capability().equals("InteractEntity")
                    && !skill.capability().equals("MenuAction")
                    && !skill.capability().equals("UseItem")
                    && !skill.capability().equals("DropItem")
                    && !skill.capability().equals("AttackEntity")
                    && !skill.capability().equals("PlaceBlock")
                    && !skill.capability().equals("RetreatFromDanger")) {
                return RuntimeResult.failure("CAPABILITY_UNAVAILABLE");
            }
            entry.mode = CompanionEntry.Mode.SKILL;
            entry.resumeMode = CompanionEntry.Mode.SKILL;
            entry.hasTarget = false;
        } else {
            return RuntimeResult.failure("UNSUPPORTED_BEHAVIOR");
        }
        control.behaviorId = behaviorId;
        control.behaviorRevision++;
        savedData.changed();
        if (entry.mode == CompanionEntry.Mode.SKILL) behaviorDirector.startSkill(entry, body, skill);
        else behaviorDirector.start(entry, body);
        return RuntimeResult.success(behaviorId, control.behaviorRevision, "RUNNING");
    }

    public RuntimeResult runtimePause(String companionId, String leaseId, long epoch) {
        CompanionEntry entry = entryByCompanion(companionId);
        RuntimeControl control = entry == null ? null : runtimeControls.get(entry.companionId);
        RuntimeResult leaseFailure = checkLease(control, leaseId, epoch);
        if (leaseFailure != null) return leaseFailure;
        CompanionPlayer body = liveBodies.get(entry.companionId);
        if (body == null) return RuntimeResult.failure("COMPANION_NOT_SPAWNED");
        if (entry.mode != CompanionEntry.Mode.PAUSED) {
            entry.resumeMode = entry.mode;
            entry.mode = CompanionEntry.Mode.PAUSED;
            behaviorDirector.stop(entry, body, false, "RUNTIME_PAUSE");
            control.behaviorRevision++;
            savedData.changed();
        }
        return RuntimeResult.success(control.behaviorId, control.behaviorRevision, "PAUSED");
    }

    public RuntimeResult runtimeResume(String companionId, String leaseId, long epoch) {
        CompanionEntry entry = entryByCompanion(companionId);
        RuntimeControl control = entry == null ? null : runtimeControls.get(entry.companionId);
        RuntimeResult leaseFailure = checkLease(control, leaseId, epoch);
        if (leaseFailure != null) return leaseFailure;
        CompanionPlayer body = liveBodies.get(entry.companionId);
        if (body == null) return RuntimeResult.failure("COMPANION_NOT_SPAWNED");
        if (entry.mode != CompanionEntry.Mode.PAUSED) return RuntimeResult.failure("NOT_PAUSED");
        entry.mode = entry.resumeMode == CompanionEntry.Mode.PAUSED ? CompanionEntry.Mode.IDLE : entry.resumeMode;
        control.behaviorRevision++;
        savedData.changed();
        if (entry.mode == CompanionEntry.Mode.SKILL) behaviorDirector.resumeSkill(entry, body);
        else if (entry.mode != CompanionEntry.Mode.IDLE) behaviorDirector.start(entry, body);
        return RuntimeResult.success(control.behaviorId, control.behaviorRevision, behaviorState(entry));
    }

    public RuntimeResult runtimeCancel(String companionId, String leaseId, long epoch) {
        CompanionEntry entry = entryByCompanion(companionId);
        RuntimeControl control = entry == null ? null : runtimeControls.get(entry.companionId);
        RuntimeResult leaseFailure = checkLease(control, leaseId, epoch);
        if (leaseFailure != null) return leaseFailure;
        CompanionPlayer body = liveBodies.get(entry.companionId);
        if (body != null) behaviorDirector.stop(entry, body, false, "RUNTIME_CANCEL");
        entry.mode = CompanionEntry.Mode.IDLE;
        entry.resumeMode = CompanionEntry.Mode.IDLE;
        entry.hasTarget = false;
        control.behaviorRevision++;
        String behaviorId = control.behaviorId;
        control.behaviorId = null;
        savedData.changed();
        return RuntimeResult.success(behaviorId, control.behaviorRevision, "CANCELLED");
    }

    public RuntimeResult runtimeReleaseLease(String companionId, String leaseId, long epoch) {
        RuntimeResult result = runtimeCancel(companionId, leaseId, epoch);
        CompanionEntry entry = entryByCompanion(companionId);
        if (result.success && entry != null) runtimeControls.remove(entry.companionId);
        return result;
    }

    public void runtimeDisconnected() {
        for (Map.Entry<UUID, RuntimeControl> value : new ArrayList<>(runtimeControls.entrySet())) {
            CompanionEntry entry = entryByCompanion(value.getKey().toString());
            CompanionPlayer body = entry == null ? null : liveBodies.get(entry.companionId);
            if (entry != null && body != null && entry.mode != CompanionEntry.Mode.IDLE
                    && entry.mode != CompanionEntry.Mode.PAUSED) {
                entry.resumeMode = entry.mode;
                entry.mode = CompanionEntry.Mode.PAUSED;
                behaviorDirector.stop(entry, body, false, "RUNTIME_OFFLINE");
                savedData.changed();
            }
        }
        runtimeControls.clear();
    }

    public void recordRuntimeCommand() {
        runtimeCommandCount++;
    }

    public long runtimeCommandCount() {
        return runtimeCommandCount;
    }

    public void recordRuntimeLifecyclePublished(String behaviorId) {
        runtimeLastPublishedBehaviorId = behaviorId;
    }

    public String runtimeLastPublishedBehaviorId() {
        return runtimeLastPublishedBehaviorId;
    }

    public void setRuntimeConnected(boolean connected) {
        runtimeConnected = connected;
    }

    private CompanionEntry entryByCompanion(String companionId) {
        if (companionId == null) return null;
        for (CompanionEntry entry : savedData.entries()) {
            if (entry.companionId.toString().equals(companionId)) return entry;
        }
        return null;
    }

    private static RuntimeResult checkLease(RuntimeControl control, String leaseId, long epoch) {
        if (!validLease(control, leaseId, epoch)) return RuntimeResult.failure("STALE_EPOCH");
        if (control.expiresAt <= System.currentTimeMillis()) return RuntimeResult.failure("LEASE_EXPIRED");
        return null;
    }

    private static boolean validLease(RuntimeControl control, String leaseId, long epoch) {
        return control != null && control.epoch == epoch && control.leaseId.equals(leaseId);
    }

    private static String behaviorState(CompanionEntry entry) {
        return switch (entry.mode) {
            case IDLE -> "IDLE";
            case FOLLOW, GOTO, SKILL -> "RUNNING";
            case PAUSED -> "PAUSED";
        };
    }

    public record RuntimeSnapshot(
            String companionId, String ownerId, String displayName, String dimension,
            double x, double y, double z, String bodyState, String behaviorId,
            String behaviorState, long behaviorRevision, long controlEpoch, boolean runtimeConnected,
            float health, float maxHealth, int foodLevel, int airSupply, boolean onFire, boolean inLava,
            int freeInventorySlots, java.util.Map<String, Integer> inventory,
            java.util.List<ContainerSnapshot> visibleContainers, String evidenceSummary,
            BehaviorObservation behaviorObservation) { }

    public record BehaviorObservation(String failureCode, String itemId, int requested, int available,
                                      java.util.List<ScanCandidate> candidates) {
        public BehaviorObservation {
            candidates = candidates == null ? java.util.List.of() : java.util.List.copyOf(candidates);
        }
        public BehaviorObservation(String failureCode, String itemId, int requested, int available) {
            this(failureCode, itemId, requested, available, java.util.List.of());
        }
    }

    public record ScanCandidate(String block, String dimension, int x, int y, int z, double distanceSquared) { }

    public record ContainerSnapshot(String type, String dimension, int x, int y, int z) { }

    public record RuntimeResult(boolean success, String code, String behaviorId, long behaviorRevision, String state) {
        static RuntimeResult success(String behaviorId, long revision, String state) {
            return new RuntimeResult(true, "OK", behaviorId, revision, state);
        }

        static RuntimeResult failure(String code) {
            return new RuntimeResult(false, code, null, 0, "FAILED");
        }
    }

    private static final class RuntimeControl {
        private final String leaseId;
        private final long epoch;
        private long expiresAt;
        private String behaviorId;
        private long behaviorRevision;

        private RuntimeControl(String leaseId, long epoch, long expiresAt) {
            this.leaseId = leaseId;
            this.epoch = epoch;
            this.expiresAt = expiresAt;
        }
    }

    /** Package-scoped integration seam used by the headless GameTest module. */
    CompanionPlayer liveBodyForOwner(UUID ownerId) {
        CompanionEntry entry = savedData.get(ownerId);
        return entry == null ? null : liveBodies.get(entry.companionId);
    }

    public void tick() {
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
            // Companion bodies always participate under ordinary survival rules;
            // creative item retention would invalidate action verification.
            body.setGameMode(GameType.SURVIVAL);
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
