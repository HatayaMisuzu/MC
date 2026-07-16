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
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;
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
        skills.put(entry.companionId, createSkill(body, entry, parameters));
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void resumeSkill(CompanionEntry entry, CompanionPlayer body) {
        if (!skills.containsKey(entry.companionId)) {
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
        if (success || !(code.equals("RUNTIME_PAUSE") || code.equals("RUNTIME_DISCONNECTED")
                || code.equals("LEASE_EXPIRED"))) skills.remove(entry.companionId);
    }

    void forget(UUID companionId) {
        navigation.remove(companionId);
        actionGateway.discard(companionId);
        skills.remove(companionId);
        observations.remove(companionId);
    }

    String evidenceSummary(UUID companionId) {
        return actionGateway.evidenceSummary(companionId);
    }

    CompanionRegistry.BehaviorObservation behaviorObservation(UUID companionId) {
        return observations.get(companionId);
    }

    void tick(CompanionEntry entry, CompanionPlayer body) {
        if (entry.mode == CompanionEntry.Mode.IDLE || entry.mode == CompanionEntry.Mode.PAUSED) {
            return;
        }
        if (entry.mode == CompanionEntry.Mode.SKILL) {
            tickSkill(entry, body);
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
        else tickEating(entry, body, progress);
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

    private record CraftingSelection(RecipeHolder<CraftingRecipe> recipe, int availableItems) { }
}
