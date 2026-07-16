package com.mccompanion.minecraft.v121;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
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
    private final Map<UUID, SkillProgress> skills = new HashMap<>();
    private final Map<UUID, ScanProgress> scans = new HashMap<>();
    private final Map<UUID, MineProgress> mines = new HashMap<>();
    private final Map<UUID, SmeltProgress> smelts = new HashMap<>();
    private final Map<UUID, RetreatProgress> retreats = new HashMap<>();
    private final Map<UUID, DefendProgress> defends = new HashMap<>();
    private final Map<UUID, CompanionRegistry.BehaviorObservation> observations = new HashMap<>();

    BehaviorDirector(MinecraftServer server, CompanionSavedData savedData, Logger logger) {
        this.server = server;
        this.savedData = savedData;
        this.logger = logger;
    }

    void start(CompanionEntry entry, CompanionPlayer body) {
        navigation.put(entry.companionId, new NavigationProgress(server.getTickCount()));
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void startSkill(CompanionEntry entry, CompanionPlayer body, SkillParameters parameters) {
        observations.remove(entry.companionId);
        if (parameters.capability().equals("ExploreArea")) {
            skills.remove(entry.companionId);
            mines.remove(entry.companionId);
            smelts.remove(entry.companionId);
            defends.remove(entry.companionId);
            scans.put(entry.companionId, createScan(body, parameters));
        } else if (parameters.capability().equals("MineResourceVein")) {
            skills.remove(entry.companionId);
            scans.remove(entry.companionId);
            smelts.remove(entry.companionId);
            defends.remove(entry.companionId);
            mines.put(entry.companionId, createMine(body, parameters));
        } else if (parameters.capability().equals("SmeltItem")) {
            skills.remove(entry.companionId);
            scans.remove(entry.companionId);
            mines.remove(entry.companionId);
            defends.remove(entry.companionId);
            smelts.put(entry.companionId, createSmelt(body, entry, parameters));
        } else if (parameters.capability().equals("DefendOwner")) {
            skills.remove(entry.companionId);
            scans.remove(entry.companionId);
            mines.remove(entry.companionId);
            smelts.remove(entry.companionId);
            defends.put(entry.companionId, createDefend(body, entry, parameters));
        } else {
            scans.remove(entry.companionId);
            mines.remove(entry.companionId);
            smelts.remove(entry.companionId);
            defends.remove(entry.companionId);
            skills.put(entry.companionId, createSkill(body, entry, parameters));
        }
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void resumeSkill(CompanionEntry entry, CompanionPlayer body) {
        if (!skills.containsKey(entry.companionId) && !scans.containsKey(entry.companionId)
                && !mines.containsKey(entry.companionId) && !smelts.containsKey(entry.companionId)
                && !defends.containsKey(entry.companionId)) {
            pauseSafely(entry, body, "RECOVERY_REQUIRED");
            return;
        }
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void stop(CompanionEntry entry, CompanionPlayer body, boolean success, String code) {
        actionGateway.stopInput(body);
        actionGateway.completeBehavior(body, success, code, server.getTickCount());
        navigation.remove(entry.companionId);
        SkillProgress skill = skills.get(entry.companionId);
        if (skill != null && (skill.parameters.capability().equals("WithdrawFromStorage")
                || skill.parameters.capability().equals("DepositToStorage"))) body.closeContainer();
        if (skill != null && skill.parameters.capability().equals("CraftItem")) {
            returnCraftingInputs(body);
            if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        }
        if (smelts.containsKey(entry.companionId) && body.containerMenu instanceof FurnaceMenu) {
            returnFurnaceInputs(body);
            body.closeContainer();
        }
        if (success || !(code.equals("RUNTIME_PAUSE") || code.equals("RUNTIME_DISCONNECTED")
                || code.equals("LEASE_EXPIRED"))) {
            skills.remove(entry.companionId);
            scans.remove(entry.companionId);
            mines.remove(entry.companionId);
            smelts.remove(entry.companionId);
            defends.remove(entry.companionId);
        }
    }

    void forget(UUID companionId) {
        navigation.remove(companionId);
        actionGateway.discard(companionId);
        skills.remove(companionId);
        scans.remove(companionId);
        mines.remove(companionId);
        smelts.remove(companionId);
        retreats.remove(companionId);
        defends.remove(companionId);
        observations.remove(companionId);
    }

    String evidenceSummary(UUID companionId) {
        return actionGateway.evidenceSummary(companionId);
    }

    CompanionRegistry.BehaviorObservation behaviorObservation(UUID companionId) {
        return observations.get(companionId);
    }

    void tick(CompanionEntry entry, CompanionPlayer body) {
        RetreatProgress retreat = retreats.get(entry.companionId);
        if (retreat != null) {
            tickRetreat(entry, body, retreat);
            return;
        }
        if (entry.mode != CompanionEntry.Mode.PAUSED && !defends.containsKey(entry.companionId)) {
            var threat = reflexController.nearestRetreatThreat(body);
            if (threat.isPresent() && threat.get().distanceToSqr(body) <= 9.0D) {
                beginRetreat(entry, body, threat.get());
                return;
            }
        }
        if (entry.mode == CompanionEntry.Mode.IDLE || entry.mode == CompanionEntry.Mode.PAUSED) {
            return;
        }
        var reflex = reflexController.blockingReason(body);
        if (reflex.isPresent()) {
            pauseSafely(entry, body, reflex.get());
            return;
        }
        if (entry.mode == CompanionEntry.Mode.SKILL) {
            tickSkill(entry, body);
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
                ignored -> new NavigationProgress(server.getTickCount()));
        if (server.getTickCount() - progress.startedTick > BEHAVIOR_TIMEOUT_TICKS) {
            pauseSafely(entry, body, "BEHAVIOR_TIMEOUT");
            return;
        }

        Vec3 delta = target.subtract(body.position());
        double distanceSquared = delta.lengthSqr();
        if (distanceSquared <= arrivalDistanceSquared) {
            stop(entry, body, true, "NONE");
            if (entry.mode == CompanionEntry.Mode.GOTO) {
                entry.mode = CompanionEntry.Mode.IDLE;
                entry.resumeMode = CompanionEntry.Mode.IDLE;
                entry.hasTarget = false;
                savedData.changed();
            }
            return;
        }

        // Collision and gravity can make a trapped player jitter without getting closer.
        // Only measurable target-distance reduction counts as navigation progress.
        if (distanceSquared + 0.25D < progress.bestDistanceSquared) {
            progress.bestDistanceSquared = distanceSquared;
            progress.stagnantTicks = 0;
        } else if (++progress.stagnantTicks >= STUCK_TICKS) {
            if (progress.replanCount >= MAX_REPLANS) {
                pauseSafely(entry, body, "STUCK");
                return;
            }
            progress.yawOffset = REPLAN_YAW_OFFSETS[progress.replanCount++];
            progress.avoidanceTicks = AVOIDANCE_TICKS;
            progress.stagnantTicks = 0;
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

    private void beginRetreat(CompanionEntry entry, CompanionPlayer body, Entity threat) {
        CompanionEntry.Mode interruptedMode = entry.mode;
        if (interruptedMode != CompanionEntry.Mode.IDLE && interruptedMode != CompanionEntry.Mode.PAUSED) {
            stop(entry, body, false, "SAFETY_RETREAT_TRIGGERED");
        }
        retreats.put(entry.companionId, new RetreatProgress(
                threat.getUUID(), interruptedMode, body.position(), server.getTickCount()));
        actionGateway.startBehavior(body, CompanionEntry.Mode.GOTO, server.getTickCount());
        savedData.changed();
        logger.warn("companion_safety_retreat owner={} companion={} threat={}",
                entry.ownerId, entry.companionId, threat.getUUID());
    }

    private void tickRetreat(CompanionEntry entry, CompanionPlayer body, RetreatProgress retreat) {
        Entity threat = body.serverLevel().getEntity(retreat.threatId);
        double displacement = body.position().distanceToSqr(retreat.startPosition);
        boolean clear = threat == null || !threat.isAlive() || threat.distanceToSqr(body) >= 36.0D;
        if (clear && displacement >= 9.0D) {
            if (++retreat.clearTicks < 5) { actionGateway.stopInput(body); return; }
            observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                    "SAFETY_RETREAT_COMPLETE", "", 1, 1));
            actionGateway.stopInput(body);
            actionGateway.completeBehavior(body, true, "NONE", server.getTickCount());
            retreats.remove(entry.companionId);
            entry.mode = retreat.interruptedMode == CompanionEntry.Mode.IDLE
                    ? CompanionEntry.Mode.IDLE : CompanionEntry.Mode.PAUSED;
            entry.resumeMode = CompanionEntry.Mode.IDLE;
            savedData.changed();
            return;
        }
        retreat.clearTicks = 0;
        if (server.getTickCount() - retreat.startedTick > 200) {
            actionGateway.stopInput(body);
            actionGateway.completeBehavior(body, false, "SAFETY_RETREAT_STUCK", server.getTickCount());
            retreats.remove(entry.companionId);
            entry.mode = CompanionEntry.Mode.PAUSED;
            entry.resumeMode = CompanionEntry.Mode.IDLE;
            observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                    "SAFETY_RETREAT_STUCK", "", 1, 0));
            savedData.changed();
            return;
        }
        if (threat == null) { actionGateway.stopInput(body); return; }
        Vec3 away = body.position().subtract(threat.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
        actionGateway.applyMoveInput(body, yaw, body.horizontalCollision);
    }

    private SkillProgress createSkill(CompanionPlayer body, CompanionEntry entry, SkillParameters parameters) {
        ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
        if (parameters.capability().equals("DeliverItem")) {
            Item item = resolveItem(parameters.itemId());
            if (item == null) return SkillProgress.failed(parameters, "ITEM_UNKNOWN");
            int available = count(body, item);
            if (available < parameters.quantity() && !parameters.allowPartial()) {
                recordShortage(entry, parameters, available);
                return SkillProgress.failed(parameters, "ITEM_INSUFFICIENT");
            }
            int target = Math.min(available, parameters.quantity());
            if (target == 0) {
                recordShortage(entry, parameters, available);
                return SkillProgress.failed(parameters, "ITEM_INSUFFICIENT");
            }
            return new SkillProgress(parameters, item, target, owner == null ? 0 : count(owner, item),
                    body.getFoodData().getFoodLevel(), available, server.getTickCount(), null);
        }
        if (parameters.capability().equals("EatAndRecover")) {
            Item item = parameters.itemId().isBlank() ? firstFood(body) : resolveItem(parameters.itemId());
            if (item == null || !new ItemStack(item).has(DataComponents.FOOD)) return SkillProgress.failed(parameters, "FOOD_MISSING");
            return new SkillProgress(parameters, item, 1, 0, body.getFoodData().getFoodLevel(),
                    count(body, item), server.getTickCount(), null);
        }
        if (parameters.capability().equals("CollectResource")) {
            Item item = resolveItem(parameters.itemId());
            if (item == null) return SkillProgress.failed(parameters, "ITEM_UNKNOWN");
            if (inventoryCapacity(body, new ItemStack(item)) < 1) {
                return SkillProgress.failed(parameters, "INVENTORY_FULL");
            }
            int available = nearbyResourceCount(body, item, 16.0D);
            if (available < parameters.quantity() && !parameters.allowPartial()) {
                observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                        "RESOURCE_INSUFFICIENT", parameters.itemId(), parameters.quantity(), available));
                return SkillProgress.failed(parameters, "RESOURCE_INSUFFICIENT");
            }
            int target = Math.min(available, parameters.quantity());
            if (target < 1) return SkillProgress.failed(parameters, "RESOURCE_NOT_FOUND");
            SkillProgress progress = new SkillProgress(parameters, item, target, 0,
                    body.getFoodData().getFoodLevel(), count(body, item), server.getTickCount(), null);
            progress.lastActionTick = server.getTickCount();
            return progress;
        }
        if (parameters.capability().equals("WithdrawFromStorage")) {
            Item item = resolveItem(parameters.itemId());
            if (item == null) return SkillProgress.failed(parameters, "ITEM_UNKNOWN");
            if (!parameters.hasBlockTarget()) return SkillProgress.failed(parameters, "CONTAINER_TARGET_MISSING");
            if (!body.serverLevel().dimension().location().toString().equals(parameters.dimension())) {
                return SkillProgress.failed(parameters, "WORLD_CHANGED");
            }
            BlockPos position = new BlockPos(parameters.x(), parameters.y(), parameters.z());
            if (body.distanceToSqr(Vec3.atCenterOf(position)) > 25.0D) {
                return SkillProgress.failed(parameters, "CONTAINER_OUT_OF_REACH");
            }
            if (!(body.serverLevel().getBlockEntity(position) instanceof Container)) {
                return SkillProgress.failed(parameters, "CONTAINER_MISSING");
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
            body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            if (body.containerMenu == body.inventoryMenu) return SkillProgress.failed(parameters, "CONTAINER_OPEN_FAILED");
            int available = countStorageMenu(body, item);
            if (available < parameters.quantity() && !parameters.allowPartial()) {
                body.closeContainer();
                recordShortage(entry, parameters, available);
                return SkillProgress.failed(parameters, "ITEM_INSUFFICIENT");
            }
            int target = Math.min(available, parameters.quantity());
            if (target == 0) {
                body.closeContainer();
                recordShortage(entry, parameters, available);
                return SkillProgress.failed(parameters, "ITEM_INSUFFICIENT");
            }
            return new SkillProgress(parameters, item, target, 0, body.getFoodData().getFoodLevel(),
                    count(body, item), server.getTickCount(), null, position, available);
        }
        if (parameters.capability().equals("DepositToStorage")) {
            Item item = resolveItem(parameters.itemId());
            if (item == null) return SkillProgress.failed(parameters, "ITEM_UNKNOWN");
            int available = count(body, item);
            if (available < parameters.quantity() && !parameters.allowPartial()) {
                recordShortage(entry, parameters, available);
                return SkillProgress.failed(parameters, "ITEM_INSUFFICIENT");
            }
            if (!parameters.hasBlockTarget()) return SkillProgress.failed(parameters, "CONTAINER_TARGET_MISSING");
            if (!body.serverLevel().dimension().location().toString().equals(parameters.dimension())) {
                return SkillProgress.failed(parameters, "WORLD_CHANGED");
            }
            BlockPos position = new BlockPos(parameters.x(), parameters.y(), parameters.z());
            if (body.distanceToSqr(Vec3.atCenterOf(position)) > 25.0D) {
                return SkillProgress.failed(parameters, "CONTAINER_OUT_OF_REACH");
            }
            if (!(body.serverLevel().getBlockEntity(position) instanceof Container)) {
                return SkillProgress.failed(parameters, "CONTAINER_MISSING");
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
            body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            if (body.containerMenu == body.inventoryMenu) return SkillProgress.failed(parameters, "CONTAINER_OPEN_FAILED");
            int capacity = storageCapacity(body, item);
            int requested = Math.min(available, parameters.quantity());
            if (capacity < requested && !parameters.allowPartial()) {
                body.closeContainer();
                return SkillProgress.failed(parameters, "CONTAINER_FULL");
            }
            int target = Math.min(requested, capacity);
            if (target == 0) {
                body.closeContainer();
                return SkillProgress.failed(parameters, available == 0 ? "ITEM_INSUFFICIENT" : "CONTAINER_FULL");
            }
            return new SkillProgress(parameters, item, target, 0, body.getFoodData().getFoodLevel(),
                    available, server.getTickCount(), null, position, countStorageMenu(body, item));
        }
        if (parameters.capability().equals("CraftItem")) {
            Item item = resolveItem(parameters.itemId());
            if (item == null) return SkillProgress.failed(parameters, "ITEM_UNKNOWN");
            int gridSize = parameters.hasBlockTarget() ? 3 : 2;
            CraftingSelection selection = selectCraftingRecipe(body, item, parameters.quantity(), gridSize);
            if (selection == null && gridSize == 2 && hasCraftingRecipe(body, item, 3)) {
                return SkillProgress.failed(parameters, "CRAFTING_TABLE_REQUIRED");
            }
            if (selection == null) return SkillProgress.failed(parameters, "RECIPE_UNAVAILABLE");
            if (selection.availableItems < parameters.quantity() && !parameters.allowPartial()) {
                observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                        "MATERIALS_INSUFFICIENT", parameters.itemId(), parameters.quantity(), selection.availableItems));
                return SkillProgress.failed(parameters, "MATERIALS_INSUFFICIENT");
            }
            int target = Math.min(parameters.quantity(), selection.availableItems);
            if (target == 0) return SkillProgress.failed(parameters, "MATERIALS_INSUFFICIENT");
            BlockPos station = null;
            if (parameters.hasBlockTarget()) {
                if (!body.serverLevel().dimension().location().toString().equals(parameters.dimension())) {
                    return SkillProgress.failed(parameters, "WORLD_CHANGED");
                }
                station = new BlockPos(parameters.x(), parameters.y(), parameters.z());
                if (body.distanceToSqr(Vec3.atCenterOf(station)) > 25.0D) {
                    return SkillProgress.failed(parameters, "CRAFTING_TABLE_OUT_OF_REACH");
                }
                if (!body.serverLevel().getBlockState(station).is(Blocks.CRAFTING_TABLE)) {
                    return SkillProgress.failed(parameters, "CRAFTING_TABLE_MISSING");
                }
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(station), Direction.UP, station, false);
                body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                if (!(body.containerMenu instanceof CraftingMenu)) {
                    return SkillProgress.failed(parameters, "CRAFTING_TABLE_OPEN_FAILED");
                }
            } else if (body.containerMenu != body.inventoryMenu) {
                body.closeContainer();
            }
            return new SkillProgress(parameters, item, target, 0, body.getFoodData().getFoodLevel(),
                    count(body, item), server.getTickCount(), null, station, 0, selection.recipe);
        }
        return SkillProgress.failed(parameters, "CAPABILITY_UNAVAILABLE");
    }

    private void recordShortage(CompanionEntry entry, SkillParameters parameters, int available) {
        observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                "ITEM_INSUFFICIENT", parameters.itemId(), parameters.quantity(), Math.max(0, available)));
    }

    private void tickSkill(CompanionEntry entry, CompanionPlayer body) {
        ScanProgress scan = scans.get(entry.companionId);
        if (scan != null) { tickScan(entry, body, scan); return; }
        MineProgress mine = mines.get(entry.companionId);
        if (mine != null) { tickMine(entry, body, mine); return; }
        SmeltProgress smelt = smelts.get(entry.companionId);
        if (smelt != null) { tickSmelt(entry, body, smelt); return; }
        DefendProgress defend = defends.get(entry.companionId);
        if (defend != null) { tickDefend(entry, body, defend); return; }
        SkillProgress progress = skills.get(entry.companionId);
        if (progress == null) { pauseSafely(entry, body, "RECOVERY_REQUIRED"); return; }
        if (progress.failureCode != null) { pauseSafely(entry, body, progress.failureCode); return; }
        if (server.getTickCount() - progress.startedTick > 20 * 20) {
            pauseSafely(entry, body, "SKILL_TIMEOUT"); return;
        }
        if (progress.parameters.capability().equals("DeliverItem")) tickDelivery(entry, body, progress);
        else if (progress.parameters.capability().equals("WithdrawFromStorage")) tickWithdrawal(entry, body, progress);
        else if (progress.parameters.capability().equals("DepositToStorage")) tickDeposit(entry, body, progress);
        else if (progress.parameters.capability().equals("CraftItem")) tickCraft(entry, body, progress);
        else if (progress.parameters.capability().equals("CollectResource")) tickCollection(entry, body, progress);
        else tickEating(entry, body, progress);
    }

    private void tickCollection(CompanionEntry entry, CompanionPlayer body, SkillProgress progress) {
        int collected = count(body, progress.item) - progress.itemBaseline;
        if (collected >= progress.target) {
            observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                    "COLLECT_COMPLETE", progress.parameters.itemId(), progress.parameters.quantity(), collected));
            actionGateway.stopInput(body);
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE;
            entry.resumeMode = CompanionEntry.Mode.IDLE; savedData.changed(); return;
        }
        if (collected > progress.bestProgress) {
            progress.bestProgress = collected;
            progress.lastActionTick = server.getTickCount();
        }
        java.util.List<ItemEntity> resources = body.serverLevel().getEntitiesOfClass(
                ItemEntity.class, body.getBoundingBox().inflate(16.0D),
                entity -> entity.isAlive() && entity.getItem().is(progress.item));
        if (resources.isEmpty()) {
            actionGateway.stopInput(body);
            if (server.getTickCount() - progress.lastActionTick > 40) {
                pauseSafely(entry, body, "RESOURCE_LOST");
            }
            return;
        }
        ItemEntity nearest = resources.stream().min(
                java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(body))).orElseThrow();
        double distance = nearest.distanceToSqr(body);
        if (distance <= 2.25D) {
            actionGateway.stopInput(body);
            int before = count(body, progress.item);
            nearest.playerTouch(body);
            if (count(body, progress.item) == before && inventoryCapacity(body, nearest.getItem()) < 1) {
                pauseSafely(entry, body, "INVENTORY_FULL");
            }
            return;
        }
        Vec3 delta = nearest.position().subtract(body.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        actionGateway.applyMoveInput(body, yaw, delta.y > 0.6D || body.horizontalCollision);
    }

    private ScanProgress createScan(CompanionPlayer body, SkillParameters parameters) {
        if (!body.serverLevel().dimension().location().toString().equals(parameters.dimension())) {
            return ScanProgress.failed(parameters, "WORLD_CHANGED");
        }
        ResourceLocation key = ResourceLocation.tryParse(parameters.itemId());
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return ScanProgress.failed(parameters, "BLOCK_UNKNOWN");
        }
        int radius = parameters.quantity();
        if (radius < 1 || radius > 16) return ScanProgress.failed(parameters, "SCAN_RADIUS_INVALID");
        BlockPos center = parameters.hasBlockTarget()
                ? new BlockPos(parameters.x(), parameters.y(), parameters.z()) : body.blockPosition();
        if (body.distanceToSqr(Vec3.atCenterOf(center)) > 32.0D * 32.0D) {
            return ScanProgress.failed(parameters, "SCAN_ORIGIN_OUT_OF_RANGE");
        }
        return new ScanProgress(parameters, BuiltInRegistries.BLOCK.get(key), center,
                radius, Math.min(radius, 8), server.getTickCount(), null);
    }

    private void tickScan(CompanionEntry entry, CompanionPlayer body, ScanProgress scan) {
        if (scan.failureCode != null) { pauseSafely(entry, body, scan.failureCode); return; }
        if (!body.serverLevel().dimension().location().toString().equals(scan.parameters.dimension())) {
            pauseSafely(entry, body, "WORLD_CHANGED"); return;
        }
        if (server.getTickCount() - scan.startedTick > 20 * 20) {
            pauseSafely(entry, body, "SCAN_TIMEOUT"); return;
        }
        int diameter = scan.radius * 2 + 1;
        int height = scan.verticalRadius * 2 + 1;
        int total = diameter * diameter * height;
        int budget = 256;
        while (budget-- > 0 && scan.index < total) {
            int value = scan.index++;
            int yOffset = value % height - scan.verticalRadius;
            value /= height;
            int zOffset = value % diameter - scan.radius;
            int xOffset = value / diameter - scan.radius;
            BlockPos position = scan.center.offset(xOffset, yOffset, zOffset);
            if (!body.serverLevel().hasChunkAt(position)) continue;
            if (body.serverLevel().getBlockState(position).is(scan.block) && scan.candidates.size() < 64) {
                scan.candidates.add(new CompanionRegistry.ScanCandidate(
                        BuiltInRegistries.BLOCK.getKey(scan.block).toString(), scan.parameters.dimension(),
                        position.getX(), position.getY(), position.getZ(),
                        position.distSqr(scan.center)));
            }
        }
        if (scan.index < total) return;
        scan.candidates.sort(java.util.Comparator.comparingDouble(CompanionRegistry.ScanCandidate::distanceSquared));
        observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                "SCAN_COMPLETE", scan.parameters.itemId(), total, scan.candidates.size(), scan.candidates));
        stop(entry, body, true, "NONE");
        entry.mode = CompanionEntry.Mode.IDLE;
        entry.resumeMode = CompanionEntry.Mode.IDLE;
        savedData.changed();
    }

    private MineProgress createMine(CompanionPlayer body, SkillParameters parameters) {
        if (!parameters.hasBlockTarget()) return MineProgress.failed(parameters, "MINE_ORIGIN_MISSING");
        if (!body.serverLevel().dimension().location().toString().equals(parameters.dimension())) {
            return MineProgress.failed(parameters, "WORLD_CHANGED");
        }
        ResourceLocation key = ResourceLocation.tryParse(parameters.itemId());
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return MineProgress.failed(parameters, "BLOCK_UNKNOWN");
        }
        Block block = BuiltInRegistries.BLOCK.get(key);
        BlockPos origin = new BlockPos(parameters.x(), parameters.y(), parameters.z());
        if (!body.serverLevel().hasChunkAt(origin) || !body.serverLevel().getBlockState(origin).is(block)) {
            return MineProgress.failed(parameters, "RESOURCE_CHANGED");
        }
        if (body.distanceToSqr(Vec3.atCenterOf(origin)) > 25.0D) {
            return MineProgress.failed(parameters, "RESOURCE_OUT_OF_REACH");
        }
        if (body.serverLevel().getBlockState(origin).requiresCorrectToolForDrops()
                && !body.hasCorrectToolForDrops(body.serverLevel().getBlockState(origin))) {
            return MineProgress.failed(parameters, "TOOL_INADEQUATE");
        }
        java.util.ArrayDeque<BlockPos> pending = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.List<BlockPos> targets = new java.util.ArrayList<>();
        pending.add(origin); visited.add(origin);
        while (!pending.isEmpty() && targets.size() < parameters.quantity()) {
            BlockPos position = pending.removeFirst();
            if (!body.serverLevel().hasChunkAt(position)
                    || !body.serverLevel().getBlockState(position).is(block)
                    || body.distanceToSqr(Vec3.atCenterOf(position)) > 25.0D) continue;
            targets.add(position.immutable());
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = position.relative(direction);
                if (visited.add(adjacent) && adjacent.distManhattan(origin) <= 6) pending.addLast(adjacent);
            }
        }
        if (targets.isEmpty()) return MineProgress.failed(parameters, "RESOURCE_NOT_FOUND");
        return new MineProgress(parameters, block, targets, server.getTickCount(), inventoryItemCount(body), null);
    }

    private void tickMine(CompanionEntry entry, CompanionPlayer body, MineProgress mine) {
        if (mine.failureCode != null) { pauseSafely(entry, body, mine.failureCode); return; }
        if (!body.serverLevel().dimension().location().toString().equals(mine.parameters.dimension())) {
            pauseSafely(entry, body, "WORLD_CHANGED"); return;
        }
        if (server.getTickCount() - mine.startedTick > 20 * 60) {
            pauseSafely(entry, body, "MINE_TIMEOUT"); return;
        }
        if (mine.targetIndex < mine.targets.size()) {
            BlockPos target = mine.targets.get(mine.targetIndex);
            var state = body.serverLevel().getBlockState(target);
            if (!state.is(mine.block)) {
                pauseSafely(entry, body, "RESOURCE_CHANGED"); return;
            }
            if (body.distanceToSqr(Vec3.atCenterOf(target)) > 25.0D) {
                pauseSafely(entry, body, "RESOURCE_OUT_OF_REACH"); return;
            }
            if (state.requiresCorrectToolForDrops() && !body.hasCorrectToolForDrops(state)) {
                pauseSafely(entry, body, "TOOL_INADEQUATE"); return;
            }
            float increment = state.getDestroyProgress(body, body.serverLevel(), target);
            if (!(increment > 0.0F) || !Float.isFinite(increment)) {
                pauseSafely(entry, body, "BLOCK_UNBREAKABLE"); return;
            }
            Vec3 delta = Vec3.atCenterOf(target).subtract(body.getEyePosition());
            body.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            body.setXRot((float) -Math.toDegrees(Math.atan2(delta.y,
                    Math.sqrt(delta.x * delta.x + delta.z * delta.z))));
            body.swing(InteractionHand.MAIN_HAND);
            mine.destroyProgress += increment;
            if (mine.destroyProgress < 1.0F) return;
            actionGateway.markVanillaGameModeAction(body);
            if (!body.gameMode.destroyBlock(target) || body.serverLevel().getBlockState(target).is(mine.block)) {
                pauseSafely(entry, body, "BLOCK_BREAK_REJECTED"); return;
            }
            mine.destroyed.add(new CompanionRegistry.ScanCandidate(
                    BuiltInRegistries.BLOCK.getKey(mine.block).toString(), mine.parameters.dimension(),
                    target.getX(), target.getY(), target.getZ(), mine.targets.getFirst().distSqr(target)));
            body.serverLevel().getEntitiesOfClass(ItemEntity.class,
                            new net.minecraft.world.phys.AABB(target).inflate(2.0D),
                            entity -> entity.isAlive() && entity.getAge() <= 2
                                    && !mine.dropEntities.contains(entity.getUUID()))
                    .forEach(entity -> {
                        mine.dropEntities.add(entity.getUUID());
                        mine.expectedDropCount += entity.getItem().getCount();
                    });
            mine.targetIndex++;
            mine.destroyProgress = 0.0F;
            return;
        }
        java.util.List<ItemEntity> drops = mine.dropEntities.stream()
                .map(body.serverLevel()::getEntity).filter(ItemEntity.class::isInstance)
                .map(ItemEntity.class::cast).filter(ItemEntity::isAlive).toList();
        int inventoryDelta = inventoryItemCount(body) - mine.inventoryBaseline;
        if (mine.expectedDropCount > 0 && drops.isEmpty() && inventoryDelta >= mine.expectedDropCount) {
            mine.destroyed.sort(java.util.Comparator.comparingDouble(CompanionRegistry.ScanCandidate::distanceSquared));
            observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                    "MINE_COMPLETE", mine.parameters.itemId(), mine.parameters.quantity(),
                    mine.destroyed.size(), mine.destroyed));
            actionGateway.stopInput(body);
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE;
            entry.resumeMode = CompanionEntry.Mode.IDLE; savedData.changed(); return;
        }
        if (mine.expectedDropCount == 0 && server.getTickCount() - mine.startedTick > 20) {
            pauseSafely(entry, body, "DROP_NOT_PRODUCED"); return;
        }
        if (drops.isEmpty()) return;
        ItemEntity nearest = drops.stream().min(
                java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(body))).orElseThrow();
        if (nearest.distanceToSqr(body) <= 2.25D) {
            actionGateway.stopInput(body);
            int before = inventoryItemCount(body);
            nearest.playerTouch(body);
            if (inventoryItemCount(body) == before && inventoryCapacity(body, nearest.getItem()) < 1) {
                pauseSafely(entry, body, "INVENTORY_FULL");
            }
            return;
        }
        Vec3 delta = nearest.position().subtract(body.position());
        actionGateway.applyMoveInput(body, (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)),
                delta.y > 0.6D || body.horizontalCollision);
    }

    private SmeltProgress createSmelt(CompanionPlayer body, CompanionEntry entry, SkillParameters parameters) {
        Item output = resolveItem(parameters.itemId());
        if (output == null) return SmeltProgress.failed(parameters, "ITEM_UNKNOWN");
        if (!parameters.hasBlockTarget()) return SmeltProgress.failed(parameters, "FURNACE_TARGET_MISSING");
        if (!body.serverLevel().dimension().location().toString().equals(parameters.dimension())) {
            return SmeltProgress.failed(parameters, "WORLD_CHANGED");
        }
        BlockPos station = new BlockPos(parameters.x(), parameters.y(), parameters.z());
        if (body.distanceToSqr(Vec3.atCenterOf(station)) > 25.0D) {
            return SmeltProgress.failed(parameters, "FURNACE_OUT_OF_REACH");
        }
        if (!body.serverLevel().getBlockState(station).is(Blocks.FURNACE)) {
            return SmeltProgress.failed(parameters, "FURNACE_MISSING");
        }
        SmeltingSelection selection = selectSmeltingRecipe(body, output);
        if (selection == null) return SmeltProgress.failed(parameters, "SMELTING_RECIPE_UNAVAILABLE");
        int availableOutput = selection.availableInputs * selection.outputPerInput;
        if (availableOutput < parameters.quantity() && !parameters.allowPartial()) {
            observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                    "MATERIALS_INSUFFICIENT", parameters.itemId(), parameters.quantity(), availableOutput));
            return SmeltProgress.failed(parameters, "MATERIALS_INSUFFICIENT");
        }
        int target = Math.min(parameters.quantity(), availableOutput);
        if (target < 1) return SmeltProgress.failed(parameters, "MATERIALS_INSUFFICIENT");
        if (inventoryCapacity(body, new ItemStack(output, target)) < target) {
            return SmeltProgress.failed(parameters, "INVENTORY_FULL");
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(station), Direction.UP, station, false);
        body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        if (!(body.containerMenu instanceof FurnaceMenu)) {
            return SmeltProgress.failed(parameters, "FURNACE_OPEN_FAILED");
        }
        if (!body.containerMenu.getSlot(0).getItem().isEmpty()
                || !body.containerMenu.getSlot(1).getItem().isEmpty()
                || !body.containerMenu.getSlot(2).getItem().isEmpty()) {
            body.closeContainer();
            return SmeltProgress.failed(parameters, "FURNACE_BUSY");
        }
        if (findFurnaceFuelSlot(body) < 0) {
            body.closeContainer();
            return SmeltProgress.failed(parameters, "FUEL_MISSING");
        }
        int inputs = (target + selection.outputPerInput - 1) / selection.outputPerInput;
        return new SmeltProgress(parameters, output, selection.ingredient, target, inputs,
                count(body, output), station, server.getTickCount(), null);
    }

    private DefendProgress createDefend(
            CompanionPlayer body, CompanionEntry entry, SkillParameters parameters) {
        ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
        if (owner == null) return DefendProgress.failed(parameters, "OWNER_OFFLINE");
        if (owner.serverLevel() != body.serverLevel()) return DefendProgress.failed(parameters, "WORLD_CHANGED");
        if (body.getHealth() <= Math.min(4.0F, body.getMaxHealth() * 0.2F)) {
            return DefendProgress.failed(parameters, "LOW_HEALTH");
        }
        Entity threat = body.serverLevel().getEntities(owner, owner.getBoundingBox().inflate(8.0D),
                        entity -> entity.isAlive() && entity instanceof net.minecraft.world.entity.monster.Enemy)
                .stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(owner)))
                .orElse(null);
        if (threat == null) return DefendProgress.failed(parameters, "NO_OWNER_THREAT");
        return new DefendProgress(parameters, threat.getUUID(), server.getTickCount(), null);
    }

    private void tickDefend(CompanionEntry entry, CompanionPlayer body, DefendProgress progress) {
        if (progress.failureCode != null) { pauseSafely(entry, body, progress.failureCode); return; }
        ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
        if (owner == null) { pauseSafely(entry, body, "OWNER_OFFLINE"); return; }
        if (owner.serverLevel() != body.serverLevel()) { pauseSafely(entry, body, "WORLD_CHANGED"); return; }
        if (server.getTickCount() - progress.startedTick > 200) {
            pauseSafely(entry, body, "DEFEND_TIMEOUT"); return;
        }
        Entity threat = body.serverLevel().getEntity(progress.threatId);
        if (threat == null || !threat.isAlive()) {
            finishDefend(entry, body, "THREAT_DEFEATED");
            return;
        }
        if (threat.distanceToSqr(owner) > 100.0D) {
            actionGateway.stopInput(body);
            if (++progress.clearTicks >= 20) finishDefend(entry, body, "OWNER_CLEAR");
            return;
        }
        progress.clearTicks = 0;
        Vec3 delta = threat.position().subtract(body.position());
        if (delta.lengthSqr() > 7.0D) {
            actionGateway.applyMoveInput(body,
                    (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)), body.horizontalCollision);
            return;
        }
        actionGateway.stopInput(body);
        body.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        if (body.getAttackStrengthScale(0.5F) < 0.9F) return;
        body.attack(threat);
        body.swing(InteractionHand.MAIN_HAND);
        progress.attacks++;
    }

    private void finishDefend(CompanionEntry entry, CompanionPlayer body, String result) {
        observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                "DEFEND_COMPLETE", result, 1, 1));
        actionGateway.stopInput(body);
        stop(entry, body, true, "NONE");
        entry.mode = CompanionEntry.Mode.IDLE;
        entry.resumeMode = CompanionEntry.Mode.IDLE;
        savedData.changed();
    }

    private void tickSmelt(CompanionEntry entry, CompanionPlayer body, SmeltProgress progress) {
        if (progress.failureCode != null) { pauseSafely(entry, body, progress.failureCode); return; }
        if (server.getTickCount() - progress.startedTick > 20 * 60 * 5) {
            pauseSafely(entry, body, "SMELT_TIMEOUT"); return;
        }
        if (!body.serverLevel().dimension().location().toString().equals(progress.parameters.dimension())) {
            pauseSafely(entry, body, "WORLD_CHANGED"); return;
        }
        if (body.distanceToSqr(Vec3.atCenterOf(progress.station)) > 25.0D) {
            pauseSafely(entry, body, "FURNACE_OUT_OF_REACH"); return;
        }
        if (!body.serverLevel().getBlockState(progress.station).is(Blocks.FURNACE)) {
            pauseSafely(entry, body, "FURNACE_MISSING"); return;
        }
        if (!(body.containerMenu instanceof FurnaceMenu)) {
            pauseSafely(entry, body, "FURNACE_CLOSED"); return;
        }
        if (!progress.setup) {
            int source = findIngredientSlot(body, progress.ingredient);
            if (source < 0) { pauseSafely(entry, body, "MATERIALS_INSUFFICIENT"); return; }
            if (!moveExactToMenuSlot(body, source, 0, progress.inputCount)) {
                pauseSafely(entry, body, "FURNACE_INPUT_FAILED"); return;
            }
            int fuel = findFurnaceFuelSlot(body);
            if (fuel < 0) { pauseSafely(entry, body, "FUEL_MISSING"); return; }
            body.containerMenu.clicked(fuel, 0, ClickType.PICKUP, body);
            body.containerMenu.clicked(1, 0, ClickType.PICKUP, body);
            if (!body.containerMenu.getCarried().isEmpty()) {
                pauseSafely(entry, body, "FURNACE_FUEL_FAILED"); return;
            }
            progress.setup = true;
            return;
        }
        ItemStack result = body.containerMenu.getSlot(2).getItem();
        if (result.is(progress.output) && result.getCount() >= progress.target) {
            body.containerMenu.clicked(2, 0, ClickType.QUICK_MOVE, body);
            int produced = count(body, progress.output) - progress.outputBaseline;
            if (produced < progress.target) {
                pauseSafely(entry, body, "FURNACE_RESULT_PICKUP_FAILED"); return;
            }
            returnFurnaceInputs(body);
            observations.put(entry.companionId, new CompanionRegistry.BehaviorObservation(
                    "SMELT_COMPLETE", progress.parameters.itemId(), progress.parameters.quantity(), produced));
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE;
            entry.resumeMode = CompanionEntry.Mode.IDLE; savedData.changed(); return;
        }
        var state = body.serverLevel().getBlockState(progress.station);
        boolean lit = state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
        if (!lit && body.containerMenu.getSlot(1).getItem().isEmpty()
                && !body.containerMenu.getSlot(0).getItem().isEmpty()
                && server.getTickCount() - progress.startedTick > 20) {
            pauseSafely(entry, body, "FUEL_MISSING");
        }
    }

    private void tickCraft(CompanionEntry entry, CompanionPlayer body, SkillProgress progress) {
        int produced = count(body, progress.item) - progress.itemBaseline;
        if (produced >= progress.target) {
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE;
            entry.resumeMode = CompanionEntry.Mode.IDLE; savedData.changed(); return;
        }
        if (progress.recipe == null || !(body.containerMenu instanceof RecipeBookMenu<?, ?> menu)) {
            pauseSafely(entry, body, "CRAFTING_MENU_CLOSED"); return;
        }
        if (progress.containerPosition != null) {
            if (!body.serverLevel().dimension().location().toString().equals(progress.parameters.dimension())) {
                pauseSafely(entry, body, "WORLD_CHANGED"); return;
            }
            if (body.distanceToSqr(Vec3.atCenterOf(progress.containerPosition)) > 25.0D) {
                pauseSafely(entry, body, "CRAFTING_TABLE_OUT_OF_REACH"); return;
            }
            if (!body.serverLevel().getBlockState(progress.containerPosition).is(Blocks.CRAFTING_TABLE)) {
                pauseSafely(entry, body, "CRAFTING_TABLE_MISSING"); return;
            }
        }
        if (server.getTickCount() - progress.lastActionTick < 2) return;
        ItemStack result = progress.recipe.value().getResultItem(body.registryAccess());
        if (inventoryCapacity(body, result) < result.getCount()) {
            pauseSafely(entry, body, "INVENTORY_FULL"); return;
        }
        if (!placeRecipeInputs(body, menu, progress.recipe.value())) {
            pauseSafely(entry, body, "CRAFTING_RECIPE_PLACEMENT_FAILED"); return;
        }
        int resultSlot = menu.getResultSlotIndex();
        ItemStack visibleResult = body.containerMenu.getSlot(resultSlot).getItem();
        if (!visibleResult.is(progress.item) || visibleResult.isEmpty()) {
            pauseSafely(entry, body, "CRAFTING_RECIPE_PLACEMENT_FAILED"); return;
        }
        body.containerMenu.clicked(resultSlot, 0, ClickType.PICKUP, body);
        ItemStack carried = body.containerMenu.getCarried();
        if (!carried.is(progress.item) || carried.isEmpty()) {
            pauseSafely(entry, body, "CRAFTING_RESULT_PICKUP_FAILED"); return;
        }
        int targetSlot = findInventorySlotWithCapacity(body, carried);
        if (targetSlot < 0) { pauseSafely(entry, body, "INVENTORY_FULL"); return; }
        body.containerMenu.clicked(targetSlot, 0, ClickType.PICKUP, body);
        if (!body.containerMenu.getCarried().isEmpty()) {
            pauseSafely(entry, body, "CRAFTING_RESULT_STORE_FAILED"); return;
        }
        progress.actions++; progress.lastActionTick = server.getTickCount();
    }

    private void tickDeposit(CompanionEntry entry, CompanionPlayer body, SkillProgress progress) {
        if (!body.serverLevel().dimension().location().toString().equals(progress.parameters.dimension())) {
            pauseSafely(entry, body, "WORLD_CHANGED"); return;
        }
        if (progress.containerPosition == null
                || body.distanceToSqr(Vec3.atCenterOf(progress.containerPosition)) > 25.0D) {
            pauseSafely(entry, body, "CONTAINER_OUT_OF_REACH"); return;
        }
        if (!(body.serverLevel().getBlockEntity(progress.containerPosition) instanceof Container)) {
            pauseSafely(entry, body, "CONTAINER_MISSING"); return;
        }
        if (body.containerMenu == body.inventoryMenu) {
            pauseSafely(entry, body, "CONTAINER_CLOSED"); return;
        }
        int bodyDelta = progress.itemBaseline - count(body, progress.item);
        int storageDelta = countStorageMenu(body, progress.item) - progress.containerBaseline;
        if (bodyDelta >= progress.target && storageDelta >= progress.target) {
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE;
            entry.resumeMode = CompanionEntry.Mode.IDLE; savedData.changed(); return;
        }
        if (server.getTickCount() - progress.lastActionTick < 2) return;
        int source = findBodyItemSlot(body, progress.item);
        int target = findStorageInsertSlot(body, progress.item);
        if (source < 0) { pauseSafely(entry, body, "ITEM_INSUFFICIENT"); return; }
        if (target < 0) { pauseSafely(entry, body, "CONTAINER_FULL"); return; }
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        body.containerMenu.clicked(target, 1, ClickType.PICKUP, body);
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        if (!body.containerMenu.getCarried().isEmpty()) {
            pauseSafely(entry, body, "CONTAINER_TRANSACTION_FAILED"); return;
        }
        progress.actions++; progress.lastActionTick = server.getTickCount();
    }

    private void tickWithdrawal(CompanionEntry entry, CompanionPlayer body, SkillProgress progress) {
        if (!body.serverLevel().dimension().location().toString().equals(progress.parameters.dimension())) {
            pauseSafely(entry, body, "WORLD_CHANGED"); return;
        }
        if (progress.containerPosition == null
                || body.distanceToSqr(Vec3.atCenterOf(progress.containerPosition)) > 25.0D) {
            pauseSafely(entry, body, "CONTAINER_OUT_OF_REACH"); return;
        }
        if (!(body.serverLevel().getBlockEntity(progress.containerPosition) instanceof Container)) {
            pauseSafely(entry, body, "CONTAINER_MISSING"); return;
        }
        if (body.containerMenu == body.inventoryMenu) {
            pauseSafely(entry, body, "CONTAINER_CLOSED"); return;
        }
        int bodyDelta = count(body, progress.item) - progress.itemBaseline;
        int storageDelta = progress.containerBaseline - countStorageMenu(body, progress.item);
        if (bodyDelta >= progress.target && storageDelta >= progress.target) {
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE;
            entry.resumeMode = CompanionEntry.Mode.IDLE; savedData.changed(); return;
        }
        if (server.getTickCount() - progress.lastActionTick < 2) return;
        int source = findStorageSlot(body, progress.item);
        int target = findInventorySlot(body, progress.item);
        if (source < 0) { pauseSafely(entry, body, "ITEM_INSUFFICIENT"); return; }
        if (target < 0) { pauseSafely(entry, body, "INVENTORY_FULL"); return; }
        // One item is moved through the same PICKUP/right-click menu sequence a real player uses.
        // This preserves slot validation, stack limits and container bookkeeping without editing either inventory.
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        body.containerMenu.clicked(target, 1, ClickType.PICKUP, body);
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        if (!body.containerMenu.getCarried().isEmpty()) {
            pauseSafely(entry, body, "CONTAINER_TRANSACTION_FAILED"); return;
        }
        progress.actions++; progress.lastActionTick = server.getTickCount();
    }

    private void tickDelivery(CompanionEntry entry, CompanionPlayer body, SkillProgress progress) {
        ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
        if (owner == null) { pauseSafely(entry, body, "OWNER_OFFLINE"); return; }
        if (owner.serverLevel() != body.serverLevel()) { pauseSafely(entry, body, "WORLD_CHANGED"); return; }
        if (owner.distanceToSqr(body) > 16.0D) { pauseSafely(entry, body, "OWNER_OUT_OF_REACH"); return; }
        // A handoff is an explicit pickup interaction: keep asking only the
        // ItemEntities created by this skill to touch the nearby recipient.
        // ItemEntity still enforces its vanilla pickup delay and inventory rules.
        body.serverLevel().getEntitiesOfClass(
                        ItemEntity.class,
                        owner.getBoundingBox().inflate(3.0D),
                        entity -> progress.droppedEntities.contains(entity.getUUID()))
                .forEach(entity -> entity.playerTouch(owner));
        if (count(owner, progress.item) - progress.ownerBaseline >= progress.target) {
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE; entry.resumeMode = CompanionEntry.Mode.IDLE;
            savedData.changed(); return;
        }
        if (progress.actions >= progress.target) return;
        if (server.getTickCount() - progress.lastActionTick < 4) return;
        int slot = ensureHotbarItem(body, progress.item);
        if (slot < 0 || slot > 8) { pauseSafely(entry, body, "ITEM_NOT_IN_HOTBAR"); return; }
        body.getInventory().selected = slot;
        Vec3 delta = owner.position().subtract(body.position());
        body.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        body.setXRot(0.0F);
        if (!body.drop(false)) { pauseSafely(entry, body, "DROP_FAILED"); return; }
        // ServerPlayer#drop creates the real vanilla ItemEntity and initially
        // associates it with the thrower. Mark only that freshly tossed item for
        // the intended recipient; pickup and inventory mutation remain vanilla.
        body.serverLevel().getEntitiesOfClass(
                        ItemEntity.class,
                        body.getBoundingBox().inflate(2.5D),
                        entity -> entity.getAge() <= 1 && entity.getItem().is(progress.item))
                .stream()
                .min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(body)))
                .ifPresent(entity -> {
                    entity.setTarget(owner.getUUID());
                    progress.droppedEntities.add(entity.getUUID());
                    // This is an intentional handoff, not an unattended world
                    // drop. Remove the normal toss pickup delay and immediately
                    // exercise ItemEntity's vanilla pickup path while both
                    // players are still within the validated interaction range.
                    entity.setNoPickUpDelay();
                    entity.playerTouch(owner);
                });
        progress.actions++; progress.lastActionTick = server.getTickCount();
    }

    private void tickEating(CompanionEntry entry, CompanionPlayer body, SkillProgress progress) {
        boolean consumed = count(body, progress.item) < progress.itemBaseline;
        boolean recovered = body.getFoodData().getFoodLevel() > progress.foodBaseline || !body.canEat(false);
        if (consumed && recovered) {
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE; entry.resumeMode = CompanionEntry.Mode.IDLE;
            savedData.changed(); return;
        }
        if (!body.canEat(false)) {
            stop(entry, body, true, "NONE"); entry.mode = CompanionEntry.Mode.IDLE; entry.resumeMode = CompanionEntry.Mode.IDLE;
            savedData.changed(); return;
        }
        if (body.isUsingItem()) return;
        int slot = findSlot(body, progress.item);
        if (slot < 0 || slot > 8) { pauseSafely(entry, body, "FOOD_NOT_IN_HOTBAR"); return; }
        body.getInventory().selected = slot;
        body.gameMode.useItem(body, body.serverLevel(), body.getInventory().getSelected(), InteractionHand.MAIN_HAND);
        progress.actions++; progress.lastActionTick = server.getTickCount();
    }

    private static Item resolveItem(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null || !BuiltInRegistries.ITEM.containsKey(key) ? null : BuiltInRegistries.ITEM.get(key);
    }

    private static Item firstFood(CompanionPlayer body) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) return stack.getItem();
        }
        return null;
    }

    private static int nearbyResourceCount(CompanionPlayer body, Item item, double radius) {
        return body.serverLevel().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(radius),
                        entity -> entity.isAlive() && entity.getItem().is(item)).stream()
                .mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static int inventoryItemCount(CompanionPlayer body) {
        int count = 0;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            count += body.getInventory().getItem(slot).getCount();
        }
        return count;
    }

    private static int findSlot(ServerPlayer player, Item item) {
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).is(item)) return slot;
        return -1;
    }

    /** Moves an existing main-inventory stack through the vanilla InventoryMenu before handoff. */
    private static int ensureHotbarItem(CompanionPlayer body, Item item) {
        int existing = findSlot(body, item);
        if (existing >= 0) return existing;
        int sourceInventory = -1;
        for (int slot = 9; slot < 36; slot++) {
            if (body.getInventory().getItem(slot).is(item)) { sourceInventory = slot; break; }
        }
        if (sourceInventory < 0 || !body.inventoryMenu.getCarried().isEmpty()) return -1;
        int targetHotbar = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (body.getInventory().getItem(slot).isEmpty()) { targetHotbar = slot; break; }
        }
        if (targetHotbar < 0) return -1;
        // InventoryMenu ids 9..35 are the player's main inventory and 36..44 are the hotbar.
        // PICKUP is the same validated menu transaction a real player uses; no stack is edited directly.
        body.inventoryMenu.clicked(sourceInventory, 0, ClickType.PICKUP, body);
        body.inventoryMenu.clicked(36 + targetHotbar, 0, ClickType.PICKUP, body);
        if (!body.inventoryMenu.getCarried().isEmpty()) {
            body.inventoryMenu.clicked(sourceInventory, 0, ClickType.PICKUP, body);
            return -1;
        }
        return findSlot(body, item);
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int countStorageMenu(CompanionPlayer body, Item item) {
        int total = 0;
        for (Slot slot : body.containerMenu.slots) {
            if (slot.container != body.getInventory() && slot.getItem().is(item)) total += slot.getItem().getCount();
        }
        return total;
    }

    private static int findStorageSlot(CompanionPlayer body, Item item) {
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container != body.getInventory() && slot.mayPickup(body) && slot.getItem().is(item)) return index;
        }
        return -1;
    }

    private static int findInventorySlot(CompanionPlayer body, Item item) {
        ItemStack single = new ItemStack(item);
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container != body.getInventory() || !slot.mayPlace(single)) continue;
            ItemStack existing = slot.getItem();
            if (existing.isEmpty() || existing.is(item) && existing.getCount() < slot.getMaxStackSize(existing)) return index;
        }
        return -1;
    }

    private static int findInventorySlotWithCapacity(CompanionPlayer body, ItemStack stack) {
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container != body.getInventory() || !slot.mayPlace(stack)) continue;
            ItemStack existing = slot.getItem();
            if (existing.isEmpty() && slot.getMaxStackSize(stack) >= stack.getCount()) return index;
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && slot.getMaxStackSize(existing) - existing.getCount() >= stack.getCount()) return index;
        }
        return -1;
    }

    private static int inventoryCapacity(CompanionPlayer body, ItemStack stack) {
        int capacity = 0;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack existing = body.getInventory().getItem(slot);
            if (existing.isEmpty()) capacity += stack.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                capacity += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
        }
        return capacity;
    }

    private static CraftingSelection selectCraftingRecipe(
            CompanionPlayer body, Item item, int quantity, int gridSize) {
        CraftingSelection best = null;
        StackedContents contents = new StackedContents();
        body.getInventory().fillStackedContents(contents);
        for (RecipeHolder<CraftingRecipe> recipe
                : body.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = recipe.value().getResultItem(body.registryAccess());
            if (!result.is(item) || !recipe.value().canCraftInDimensions(gridSize, gridSize)) continue;
            int requestedCrafts = Math.max(1, (quantity + result.getCount() - 1) / result.getCount());
            int craftable = contents.getBiggestCraftableStack(
                    recipe, requestedCrafts, new it.unimi.dsi.fastutil.ints.IntArrayList());
            CraftingSelection candidate = new CraftingSelection(recipe, craftable * result.getCount());
            if (best == null || candidate.availableItems > best.availableItems) best = candidate;
            if (candidate.availableItems >= quantity) return candidate;
        }
        return best;
    }

    private static boolean hasCraftingRecipe(CompanionPlayer body, Item item, int gridSize) {
        return body.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .anyMatch(recipe -> recipe.value().getResultItem(body.registryAccess()).is(item)
                        && recipe.value().canCraftInDimensions(gridSize, gridSize));
    }

    private static SmeltingSelection selectSmeltingRecipe(CompanionPlayer body, Item output) {
        SmeltingSelection best = null;
        for (RecipeHolder<SmeltingRecipe> recipe
                : body.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
            ItemStack result = recipe.value().getResultItem(body.registryAccess());
            if (!result.is(output) || recipe.value().getIngredients().isEmpty()) continue;
            Ingredient ingredient = recipe.value().getIngredients().getFirst();
            int available = 0;
            for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
                ItemStack stack = body.getInventory().getItem(slot);
                if (ingredient.test(stack)) available += stack.getCount();
            }
            SmeltingSelection candidate = new SmeltingSelection(ingredient, available, result.getCount());
            if (best == null || candidate.availableInputs > best.availableInputs) best = candidate;
        }
        return best;
    }

    private static int findFurnaceFuelSlot(CompanionPlayer body) {
        if (!(body.containerMenu instanceof FurnaceMenu)) return -1;
        Slot fuelSlot = body.containerMenu.getSlot(1);
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container == body.getInventory() && slot.mayPickup(body)
                    && fuelSlot.mayPlace(slot.getItem())) return index;
        }
        return -1;
    }

    private static boolean moveExactToMenuSlot(
            CompanionPlayer body, int source, int target, int quantity) {
        if (quantity < 1 || !body.containerMenu.getSlot(target).getItem().isEmpty()
                || body.containerMenu.getSlot(source).getItem().getCount() < quantity) return false;
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        for (int moved = 0; moved < quantity; moved++) {
            body.containerMenu.clicked(target, 1, ClickType.PICKUP, body);
        }
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        return body.containerMenu.getCarried().isEmpty()
                && body.containerMenu.getSlot(target).getItem().getCount() == quantity;
    }

    private static void returnFurnaceInputs(CompanionPlayer body) {
        if (!(body.containerMenu instanceof FurnaceMenu)) return;
        for (int slot = 0; slot <= 2; slot++) {
            if (!body.containerMenu.getSlot(slot).getItem().isEmpty()) {
                body.containerMenu.clicked(slot, 0, ClickType.QUICK_MOVE, body);
            }
        }
    }

    private static void returnCraftingInputs(CompanionPlayer body) {
        if (!(body.containerMenu instanceof RecipeBookMenu<?, ?> menu)) return;
        int end = menu.getResultSlotIndex() + 1 + menu.getGridWidth() * menu.getGridHeight();
        for (int slot = menu.getResultSlotIndex() + 1; slot < end; slot++) {
            if (!body.containerMenu.getSlot(slot).getItem().isEmpty()) {
                body.containerMenu.clicked(slot, 0, ClickType.QUICK_MOVE, body);
            }
        }
    }

    private static boolean placeRecipeInputs(
            CompanionPlayer body, RecipeBookMenu<?, ?> menu, CraftingRecipe recipe) {
        returnCraftingInputs(body);
        int gridWidth = menu.getGridWidth();
        int recipeWidth = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : recipe.getIngredients().size();
        int recipeHeight = recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : 1;
        if (recipeWidth > gridWidth || recipeHeight > menu.getGridHeight()) return false;
        for (int ingredientIndex = 0; ingredientIndex < recipe.getIngredients().size(); ingredientIndex++) {
            Ingredient ingredient = recipe.getIngredients().get(ingredientIndex);
            if (ingredient.isEmpty()) continue;
            int row = recipe instanceof ShapedRecipe ? ingredientIndex / recipeWidth : ingredientIndex / gridWidth;
            int column = recipe instanceof ShapedRecipe ? ingredientIndex % recipeWidth : ingredientIndex % gridWidth;
            int target = menu.getResultSlotIndex() + 1 + row * gridWidth + column;
            int source = findIngredientSlot(body, ingredient);
            if (source < 0) { returnCraftingInputs(body); return false; }
            body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
            body.containerMenu.clicked(target, 1, ClickType.PICKUP, body);
            body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
            if (!body.containerMenu.getCarried().isEmpty()) {
                returnCraftingInputs(body); return false;
            }
        }
        return true;
    }

    private static int findIngredientSlot(CompanionPlayer body, Ingredient ingredient) {
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container == body.getInventory() && slot.mayPickup(body)
                    && ingredient.test(slot.getItem())) return index;
        }
        return -1;
    }

    private static int findBodyItemSlot(CompanionPlayer body, Item item) {
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container == body.getInventory() && slot.mayPickup(body) && slot.getItem().is(item)) return index;
        }
        return -1;
    }

    private static int findStorageInsertSlot(CompanionPlayer body, Item item) {
        ItemStack single = new ItemStack(item);
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container == body.getInventory() || !slot.mayPlace(single)) continue;
            ItemStack existing = slot.getItem();
            if (existing.isEmpty() || existing.is(item) && existing.getCount() < slot.getMaxStackSize(existing)) {
                return index;
            }
        }
        return -1;
    }

    private static int storageCapacity(CompanionPlayer body, Item item) {
        ItemStack single = new ItemStack(item);
        int capacity = 0;
        for (Slot slot : body.containerMenu.slots) {
            if (slot.container == body.getInventory() || !slot.mayPlace(single)) continue;
            ItemStack existing = slot.getItem();
            if (existing.isEmpty()) capacity += slot.getMaxStackSize(single);
            else if (existing.is(item)) capacity += Math.max(0, slot.getMaxStackSize(existing) - existing.getCount());
        }
        return capacity;
    }

    private void pauseSafely(CompanionEntry entry, CompanionPlayer body, String code) {
        entry.resumeMode = entry.mode;
        entry.mode = CompanionEntry.Mode.PAUSED;
        stop(entry, body, false, code);
        savedData.changed();
        logger.warn("companion_paused code={} owner={} companion={}", code, entry.ownerId, entry.companionId);
    }

    private static final class NavigationProgress {
        private double bestDistanceSquared = Double.POSITIVE_INFINITY;
        private int stagnantTicks;
        private int replanCount;
        private int avoidanceTicks;
        private float yawOffset;
        private final int startedTick;

        private NavigationProgress(int startedTick) {
            this.startedTick = startedTick;
        }
    }

    private static final class RetreatProgress {
        private final UUID threatId;
        private final CompanionEntry.Mode interruptedMode;
        private final Vec3 startPosition;
        private final int startedTick;
        private int clearTicks;

        private RetreatProgress(UUID threatId, CompanionEntry.Mode interruptedMode,
                                Vec3 startPosition, int startedTick) {
            this.threatId = threatId; this.interruptedMode = interruptedMode;
            this.startPosition = startPosition; this.startedTick = startedTick;
        }
    }

    private static final class SkillProgress {
        private final SkillParameters parameters;
        private final Item item;
        private final int target;
        private final int ownerBaseline;
        private final int foodBaseline;
        private final int itemBaseline;
        private final int startedTick;
        private final String failureCode;
        private final BlockPos containerPosition;
        private final int containerBaseline;
        private final RecipeHolder<CraftingRecipe> recipe;
        private final Set<UUID> droppedEntities = new HashSet<>();
        private int actions;
        private int lastActionTick;
        private int bestProgress;

        private SkillProgress(SkillParameters parameters, Item item, int target, int ownerBaseline,
                              int foodBaseline, int itemBaseline, int startedTick, String failureCode) {
            this(parameters, item, target, ownerBaseline, foodBaseline, itemBaseline, startedTick, failureCode, null, 0);
        }
        private SkillProgress(SkillParameters parameters, Item item, int target, int ownerBaseline,
                              int foodBaseline, int itemBaseline, int startedTick, String failureCode,
                              BlockPos containerPosition, int containerBaseline) {
            this(parameters, item, target, ownerBaseline, foodBaseline, itemBaseline, startedTick, failureCode,
                    containerPosition, containerBaseline, null);
        }
        private SkillProgress(SkillParameters parameters, Item item, int target, int ownerBaseline,
                              int foodBaseline, int itemBaseline, int startedTick, String failureCode,
                              BlockPos containerPosition, int containerBaseline,
                              RecipeHolder<CraftingRecipe> recipe) {
            this.parameters = parameters; this.item = item; this.target = target; this.ownerBaseline = ownerBaseline;
            this.foodBaseline = foodBaseline; this.itemBaseline = itemBaseline;
            this.startedTick = startedTick; this.failureCode = failureCode;
            this.containerPosition = containerPosition; this.containerBaseline = containerBaseline;
            this.recipe = recipe;
        }
        private static SkillProgress failed(SkillParameters parameters, String code) {
            return new SkillProgress(parameters, null, 0, 0, 0, 0, 0, code);
        }
    }

    private static final class ScanProgress {
        private final SkillParameters parameters;
        private final Block block;
        private final BlockPos center;
        private final int radius;
        private final int verticalRadius;
        private final int startedTick;
        private final String failureCode;
        private final java.util.List<CompanionRegistry.ScanCandidate> candidates = new java.util.ArrayList<>();
        private int index;

        private ScanProgress(SkillParameters parameters, Block block, BlockPos center, int radius,
                             int verticalRadius, int startedTick, String failureCode) {
            this.parameters = parameters; this.block = block; this.center = center; this.radius = radius;
            this.verticalRadius = verticalRadius; this.startedTick = startedTick; this.failureCode = failureCode;
        }

        private static ScanProgress failed(SkillParameters parameters, String code) {
            return new ScanProgress(parameters, Blocks.AIR, BlockPos.ZERO, 1, 1, 0, code);
        }
    }

    private static final class MineProgress {
        private final SkillParameters parameters;
        private final Block block;
        private final java.util.List<BlockPos> targets;
        private final int startedTick;
        private final int inventoryBaseline;
        private final String failureCode;
        private final java.util.Set<UUID> dropEntities = new java.util.HashSet<>();
        private final java.util.List<CompanionRegistry.ScanCandidate> destroyed = new java.util.ArrayList<>();
        private int targetIndex;
        private float destroyProgress;
        private int expectedDropCount;

        private MineProgress(SkillParameters parameters, Block block, java.util.List<BlockPos> targets,
                             int startedTick, int inventoryBaseline, String failureCode) {
            this.parameters = parameters; this.block = block; this.targets = java.util.List.copyOf(targets);
            this.startedTick = startedTick; this.inventoryBaseline = inventoryBaseline; this.failureCode = failureCode;
        }

        private static MineProgress failed(SkillParameters parameters, String code) {
            return new MineProgress(parameters, Blocks.AIR, java.util.List.of(), 0, 0, code);
        }
    }

    private static final class SmeltProgress {
        private final SkillParameters parameters;
        private final Item output;
        private final Ingredient ingredient;
        private final int target;
        private final int inputCount;
        private final int outputBaseline;
        private final BlockPos station;
        private final int startedTick;
        private final String failureCode;
        private boolean setup;

        private SmeltProgress(SkillParameters parameters, Item output, Ingredient ingredient, int target,
                              int inputCount, int outputBaseline, BlockPos station, int startedTick,
                              String failureCode) {
            this.parameters = parameters; this.output = output; this.ingredient = ingredient; this.target = target;
            this.inputCount = inputCount; this.outputBaseline = outputBaseline; this.station = station;
            this.startedTick = startedTick; this.failureCode = failureCode;
        }

        private static SmeltProgress failed(SkillParameters parameters, String code) {
            return new SmeltProgress(parameters, null, Ingredient.EMPTY, 0, 0, 0,
                    BlockPos.ZERO, 0, code);
        }
    }

    private static final class DefendProgress {
        private final SkillParameters parameters;
        private final UUID threatId;
        private final int startedTick;
        private final String failureCode;
        private int clearTicks;
        private int attacks;

        private DefendProgress(SkillParameters parameters, UUID threatId, int startedTick, String failureCode) {
            this.parameters = parameters; this.threatId = threatId;
            this.startedTick = startedTick; this.failureCode = failureCode;
        }

        private static DefendProgress failed(SkillParameters parameters, String code) {
            return new DefendProgress(parameters, new UUID(0L, 0L), 0, code);
        }
    }

    private record CraftingSelection(RecipeHolder<CraftingRecipe> recipe, int availableItems) { }
    private record SmeltingSelection(Ingredient ingredient, int availableInputs, int outputPerInput) { }
}
