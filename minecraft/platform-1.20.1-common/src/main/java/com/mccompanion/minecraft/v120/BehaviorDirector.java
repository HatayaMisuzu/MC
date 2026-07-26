package com.mccompanion.minecraft.v120;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
    private final Map<UUID, PrimitiveProgress> primitives = new HashMap<>();

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
        primitives.put(entry.companionId, new PrimitiveProgress(parameters, server.getTickCount()));
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void resumeSkill(CompanionEntry entry, CompanionPlayer body) {
        if (!primitives.containsKey(entry.companionId)) {
            pauseSafely(entry, body, "RECOVERY_REQUIRED");
            return;
        }
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void stop(CompanionEntry entry, CompanionPlayer body, boolean success, String code) {
        actionGateway.stopInput(body);
        actionGateway.completeBehavior(body, success, code, server.getTickCount());
        navigation.remove(entry.companionId);
        if (success || !(code.equals("RUNTIME_PAUSE")
                || code.equals("RUNTIME_DISCONNECTED")
                || code.equals("LEASE_EXPIRED"))) {
            primitives.remove(entry.companionId);
        }
    }

    void forget(UUID companionId) {
        navigation.remove(companionId);
        primitives.remove(companionId);
        MenuSessionTracker.invalidate(companionId);
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
        if (entry.mode == CompanionEntry.Mode.SKILL) {
            tickPrimitive(entry, body);
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

    private void tickPrimitive(CompanionEntry entry, CompanionPlayer body) {
        PrimitiveProgress progress = primitives.get(entry.companionId);
        if (progress == null) {
            pauseSafely(entry, body, "RECOVERY_REQUIRED");
            return;
        }
        if (server.getTickCount() - progress.startedTick > 20 * 60) {
            pauseSafely(entry, body, "PRIMITIVE_TIMEOUT");
            return;
        }
        actionGateway.stopInput(body);
        String capability = progress.parameters.capability();
        if (Set.of(
                        "CollectResource",
                        "MineResourceVein",
                        "WithdrawFromStorage",
                        "DepositToStorage",
                        "DeliverItem",
                        "EatAndRecover",
                        "DefendOwner",
                        "RetreatFromDanger")
                .contains(capability)) {
            String composite = tickComposite(entry, body, progress);
            if ("WAIT".equals(composite)) return;
            if (composite != null) {
                pauseSafely(entry, body, composite);
                return;
            }
            stop(entry, body, true, "NONE");
            entry.mode = CompanionEntry.Mode.IDLE;
            entry.resumeMode = CompanionEntry.Mode.IDLE;
            savedData.changed();
            return;
        }
        String failure = switch (capability) {
            case "LookAt" -> lookAt(body, progress.parameters);
            case "InteractBlock", "PlaceBlock" -> interactBlock(body, progress.parameters);
            case "InteractEntity", "AttackEntity" -> interactEntity(body, progress.parameters);
            case "MenuAction" -> menuAction(body, progress.parameters);
            case "UseItem" -> useItem(body, progress.parameters);
            case "DropItem" -> dropItem(body, progress.parameters);
            default -> "CAPABILITY_UNAVAILABLE";
        };
        if (failure != null) {
            pauseSafely(entry, body, failure);
            return;
        }
        stop(entry, body, true, "NONE");
        entry.mode = CompanionEntry.Mode.IDLE;
        entry.resumeMode = CompanionEntry.Mode.IDLE;
        savedData.changed();
    }

    private String tickComposite(
            CompanionEntry entry,
            CompanionPlayer body,
            PrimitiveProgress progress) {
        return switch (progress.parameters.capability()) {
            case "CollectResource" -> tickCollect(body, progress);
            case "MineResourceVein" -> tickMine(body, progress);
            case "WithdrawFromStorage" -> tickStorage(body, progress, true);
            case "DepositToStorage" -> tickStorage(body, progress, false);
            case "DeliverItem" -> tickDeliver(entry, body, progress);
            case "EatAndRecover" -> tickEat(body, progress);
            case "DefendOwner" -> tickDefend(entry, body, progress);
            case "RetreatFromDanger" -> tickRetreat(body, progress);
            default -> "CAPABILITY_UNAVAILABLE";
        };
    }

    private String tickCollect(CompanionPlayer body, PrimitiveProgress progress) {
        Item item = resolveItem(progress.parameters.itemId());
        if (item == null) return "ITEM_UNKNOWN";
        if (!progress.initialized) {
            progress.initialized = true;
            progress.baseline = body.getInventory().countItem(item);
            int available = body.serverLevel().getEntitiesOfClass(
                            ItemEntity.class,
                            body.getBoundingBox().inflate(16.0D),
                            entity -> entity.isAlive() && entity.getItem().is(item))
                    .stream()
                    .mapToInt(entity -> entity.getItem().getCount())
                    .sum();
            if (available < progress.parameters.quantity() && !progress.parameters.allowPartial()) {
                return "RESOURCE_INSUFFICIENT";
            }
            progress.target = Math.min(available, progress.parameters.quantity());
            if (progress.target < 1) return "RESOURCE_NOT_FOUND";
        }
        if (body.getInventory().countItem(item) - progress.baseline >= progress.target) return null;
        ItemEntity target = body.serverLevel().getEntitiesOfClass(
                        ItemEntity.class,
                        body.getBoundingBox().inflate(16.0D),
                        entity -> entity.isAlive() && entity.getItem().is(item))
                .stream()
                .min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(body)))
                .orElse(null);
        if (target == null) return "RESOURCE_DISAPPEARED";
        if (target.distanceToSqr(body) <= 2.25D) {
            int before = body.getInventory().countItem(item);
            target.setNoPickUpDelay();
            target.playerTouch(body);
            return body.getInventory().countItem(item) > before ? "WAIT" : "INVENTORY_FULL";
        }
        Vec3 delta = target.position().subtract(body.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        actionGateway.applyMoveInput(body, yaw, delta.y > 0.6D || body.horizontalCollision);
        return "WAIT";
    }

    private String tickMine(CompanionPlayer body, PrimitiveProgress progress) {
        ResourceLocation id = ResourceLocation.tryParse(progress.parameters.itemId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return "BLOCK_UNKNOWN";
        if (!progress.parameters.hasBlockTarget()) return "BLOCK_TARGET_MISSING";
        if (!sameDimension(body, progress.parameters)) return "WORLD_CHANGED";
        if (!progress.initialized) {
            progress.initialized = true;
            BlockPos origin = new BlockPos(
                    progress.parameters.x(),
                    progress.parameters.y(),
                    progress.parameters.z());
            java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
            java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
            queue.add(origin);
            while (!queue.isEmpty() && progress.blocks.size() < progress.parameters.quantity()) {
                BlockPos current = queue.removeFirst();
                if (!visited.add(current)
                        || !body.serverLevel().hasChunkAt(current)
                        || !body.serverLevel().getBlockState(current).is(BuiltInRegistries.BLOCK.get(id))) {
                    continue;
                }
                progress.blocks.add(current.immutable());
                for (Direction direction : Direction.values()) queue.addLast(current.relative(direction));
            }
            if (progress.blocks.size() < progress.parameters.quantity()
                    && !progress.parameters.allowPartial()) {
                return "RESOURCE_INSUFFICIENT";
            }
            if (progress.blocks.isEmpty()) return "BLOCK_NOT_FOUND";
        }
        if (progress.actions >= progress.blocks.size()) return null;
        BlockPos target = progress.blocks.get(progress.actions);
        if (body.distanceToSqr(Vec3.atCenterOf(target)) > 25.0D) return "BLOCK_OUT_OF_REACH";
        if (!body.serverLevel().getBlockState(target).is(BuiltInRegistries.BLOCK.get(id))) {
            return "BLOCK_CHANGED";
        }
        var state = body.serverLevel().getBlockState(target);
        if (state.requiresCorrectToolForDrops() && !body.hasCorrectToolForDrops(state)) {
            return "TOOL_INADEQUATE";
        }
        float increment = state.getDestroyProgress(body, body.serverLevel(), target);
        if (!(increment > 0.0F) || !Float.isFinite(increment)) return "BLOCK_UNBREAKABLE";
        var visible = body.serverLevel().clip(new ClipContext(
                body.getEyePosition(),
                Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                body));
        if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(target)) {
            return "BLOCK_NOT_VISIBLE";
        }
        Vec3 delta = Vec3.atCenterOf(target).subtract(body.getEyePosition());
        body.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        body.setXRot((float) -Math.toDegrees(Math.atan2(
                delta.y,
                Math.sqrt(delta.x * delta.x + delta.z * delta.z))));
        body.swing(InteractionHand.MAIN_HAND);
        progress.destroyProgress += increment;
        if (progress.destroyProgress < 1.0F) return "WAIT";
        if (!body.gameMode.destroyBlock(target)) return "BLOCK_BREAK_FAILED";
        actionGateway.markVanillaGameModeAction(body);
        body.serverLevel().getEntitiesOfClass(
                        ItemEntity.class,
                        new net.minecraft.world.phys.AABB(target).inflate(2.0D),
                        entity -> entity.getAge() <= 2)
                .forEach(entity -> {
                    entity.setNoPickUpDelay();
                    entity.playerTouch(body);
                });
        if (body.serverLevel().getBlockState(target).is(BuiltInRegistries.BLOCK.get(id))) {
            return "UNCERTAIN_EFFECT";
        }
        progress.actions++;
        progress.destroyProgress = 0.0F;
        return progress.actions >= progress.blocks.size() ? null : "WAIT";
    }

    private String tickStorage(
            CompanionPlayer body,
            PrimitiveProgress progress,
            boolean withdraw) {
        Item item = resolveItem(progress.parameters.itemId());
        if (item == null) return "ITEM_UNKNOWN";
        if (!progress.parameters.hasBlockTarget()) return "CONTAINER_TARGET_MISSING";
        if (!sameDimension(body, progress.parameters)) return "WORLD_CHANGED";
        BlockPos position = new BlockPos(
                progress.parameters.x(),
                progress.parameters.y(),
                progress.parameters.z());
        if (body.distanceToSqr(Vec3.atCenterOf(position)) > 25.0D) {
            return "CONTAINER_OUT_OF_REACH";
        }
        if (!(body.serverLevel().getBlockEntity(position) instanceof Container)) {
            return "CONTAINER_MISSING";
        }
        if (!progress.initialized) {
            BlockHitResult hit =
                    new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
            body.gameMode.useItemOn(
                    body,
                    body.serverLevel(),
                    body.getMainHandItem(),
                    InteractionHand.MAIN_HAND,
                    hit);
            actionGateway.markVanillaGameModeAction(body);
            if (body.containerMenu == body.inventoryMenu) return "CONTAINER_OPEN_FAILED";
            progress.initialized = true;
            progress.baseline = withdraw
                    ? body.getInventory().countItem(item)
                    : countStorageMenu(body, item);
            int available = withdraw
                    ? countStorageMenu(body, item)
                    : body.getInventory().countItem(item);
            if (available < progress.parameters.quantity() && !progress.parameters.allowPartial()) {
                body.closeContainer();
                return "ITEM_INSUFFICIENT";
            }
            progress.target = Math.min(available, progress.parameters.quantity());
            if (progress.target < 1) {
                body.closeContainer();
                return "ITEM_INSUFFICIENT";
            }
        }
        int changed = withdraw
                ? body.getInventory().countItem(item) - progress.baseline
                : countStorageMenu(body, item) - progress.baseline;
        if (changed >= progress.target) {
            body.closeContainer();
            return null;
        }
        int source = withdraw ? findStorageSlot(body, item) : findBodyItemSlot(body, item);
        int target = withdraw ? findInventorySlot(body, item) : findStorageInsertSlot(body, item);
        if (source < 0) return "ITEM_INSUFFICIENT";
        if (target < 0) return withdraw ? "INVENTORY_FULL" : "CONTAINER_FULL";
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        body.containerMenu.clicked(target, 1, ClickType.PICKUP, body);
        body.containerMenu.clicked(source, 0, ClickType.PICKUP, body);
        actionGateway.markVanillaMenuAction(body);
        return body.containerMenu.getCarried().isEmpty()
                ? "WAIT"
                : "CONTAINER_TRANSACTION_FAILED";
    }

    private String tickDeliver(
            CompanionEntry entry,
            CompanionPlayer body,
            PrimitiveProgress progress) {
        ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
        if (owner == null) return "OWNER_OFFLINE";
        if (owner.serverLevel() != body.serverLevel()) return "WORLD_CHANGED";
        if (owner.distanceToSqr(body) > 16.0D) return "OWNER_OUT_OF_REACH";
        Item item = resolveItem(progress.parameters.itemId());
        if (item == null) return "ITEM_UNKNOWN";
        if (!progress.initialized) {
            progress.initialized = true;
            progress.baseline = owner.getInventory().countItem(item);
            int available = body.getInventory().countItem(item);
            if (available < progress.parameters.quantity() && !progress.parameters.allowPartial()) {
                return "ITEM_INSUFFICIENT";
            }
            progress.target = Math.min(available, progress.parameters.quantity());
            if (progress.target < 1) return "ITEM_INSUFFICIENT";
        }
        if (owner.getInventory().countItem(item) - progress.baseline >= progress.target) return null;
        if (!selectHotbarItem(body, item)) return "ITEM_INSUFFICIENT";
        int before = owner.getInventory().countItem(item);
        if (!body.drop(false)) return "DROP_FAILED";
        actionGateway.markVanillaDrop(body);
        body.serverLevel().getEntitiesOfClass(
                        ItemEntity.class,
                        body.getBoundingBox().inflate(3.0D),
                        entity -> entity.getAge() <= 2 && entity.getItem().is(item))
                .stream()
                .min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(body)))
                .ifPresent(entity -> {
                    entity.setTarget(owner.getUUID());
                    entity.setNoPickUpDelay();
                    entity.playerTouch(owner);
                });
        return owner.getInventory().countItem(item) > before ? "WAIT" : "DELIVERY_FAILED";
    }

    private String tickEat(CompanionPlayer body, PrimitiveProgress progress) {
        Item item = progress.parameters.itemId().isBlank()
                ? firstFood(body)
                : resolveItem(progress.parameters.itemId());
        if (item == null || !item.isEdible()) return "FOOD_MISSING";
        if (!progress.initialized) {
            if (!selectHotbarItem(body, item)) return "FOOD_MISSING";
            progress.initialized = true;
            progress.baseline = body.getFoodData().getFoodLevel();
            progress.itemBaseline = body.getInventory().countItem(item);
            var result = body.gameMode.useItem(
                    body,
                    body.serverLevel(),
                    body.getMainHandItem(),
                    InteractionHand.MAIN_HAND);
            actionGateway.markVanillaGameModeAction(body);
            if (!result.consumesAction()) return "FOOD_USE_REJECTED";
            return "WAIT";
        }
        if (body.getFoodData().getFoodLevel() > progress.baseline
                || body.getInventory().countItem(item) < progress.itemBaseline) {
            return null;
        }
        return body.isUsingItem() ? "WAIT" : "FOOD_NO_EFFECT";
    }

    private String tickDefend(
            CompanionEntry entry,
            CompanionPlayer body,
            PrimitiveProgress progress) {
        ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId);
        if (owner == null) return "OWNER_OFFLINE";
        if (owner.serverLevel() != body.serverLevel()) return "WORLD_CHANGED";
        Entity target = progress.entityId == null ? null : body.serverLevel().getEntity(progress.entityId);
        if (target == null && !progress.initialized) {
            progress.initialized = true;
            target = body.serverLevel().getEntities(
                            body,
                            owner.getBoundingBox().inflate(8.0D),
                            entity -> entity instanceof LivingEntity
                                    && entity instanceof Enemy
                                    && entity.isAlive())
                    .stream()
                    .min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(owner)))
                    .orElse(null);
            if (target == null) return null;
            progress.entityId = target.getUUID();
        }
        if (!(target instanceof LivingEntity living) || !living.isAlive()) return null;
        if (target.distanceToSqr(owner) > 100.0D) return null;
        if (body.distanceToSqr(target) > 9.0D) {
            Vec3 delta = target.position().subtract(body.position());
            actionGateway.applyMoveInput(
                    body,
                    (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)),
                    delta.y > 0.6D || body.horizontalCollision);
            return "WAIT";
        }
        actionGateway.stopInput(body);
        if (body.getAttackStrengthScale(0.0F) < 0.9F) return "WAIT";
        float health = living.getHealth();
        body.attack(living);
        actionGateway.markVanillaAttack(body);
        return !living.isAlive() || living.getHealth() < health ? "WAIT" : "ENTITY_ATTACK_NO_EFFECT";
    }

    private String tickRetreat(CompanionPlayer body, PrimitiveProgress progress) {
        if (!progress.initialized) {
            UUID threatId;
            try {
                threatId = UUID.fromString(progress.parameters.targetId());
            } catch (IllegalArgumentException invalid) {
                return "ENTITY_ID_INVALID";
            }
            Entity threat = body.serverLevel().getEntity(threatId);
            if (threat == null || !threat.isAlive() || threat == body) return "ENTITY_NOT_FOUND";
            if (body.distanceToSqr(threat) > 16.0D * 16.0D) return "ENTITY_OUT_OF_RANGE";
            if (!body.hasLineOfSight(threat)) return "ENTITY_NOT_VISIBLE";
            progress.initialized = true;
            progress.entityId = threatId;
            progress.startPosition = body.position();
        }
        Entity threat = body.serverLevel().getEntity(progress.entityId);
        double displacement = body.position().distanceToSqr(progress.startPosition);
        boolean clear = threat == null || !threat.isAlive() || threat.distanceToSqr(body) >= 36.0D;
        if (clear && displacement >= 9.0D) return null;
        if (threat == null) return "WAIT";
        Vec3 away = body.position().subtract(threat.position());
        if (away.lengthSqr() < 0.01D) away = new Vec3(1.0D, 0.0D, 0.0D);
        actionGateway.applyMoveInput(
                body,
                (float) Math.toDegrees(Math.atan2(-away.x, away.z)),
                body.horizontalCollision);
        return "WAIT";
    }

    private String lookAt(CompanionPlayer body, SkillParameters parameters) {
        if (!parameters.hasBlockTarget()) return "BLOCK_TARGET_MISSING";
        if (!sameDimension(body, parameters)) return "WORLD_CHANGED";
        Vec3 target = Vec3.atCenterOf(new BlockPos(parameters.x(), parameters.y(), parameters.z()));
        if (body.distanceToSqr(target) > 16.0D * 16.0D) return "TARGET_OUT_OF_RANGE";
        actionGateway.lookAt(body, target);
        Vec3 expected = target.subtract(body.getEyePosition()).normalize();
        return expected.dot(body.getViewVector(1.0F)) >= 0.995D ? null : "LOOK_VERIFICATION_FAILED";
    }

    private String interactBlock(CompanionPlayer body, SkillParameters parameters) {
        if (!parameters.hasBlockTarget()) return "BLOCK_TARGET_MISSING";
        if (!sameDimension(body, parameters)) return "WORLD_CHANGED";
        BlockPos target = new BlockPos(parameters.x(), parameters.y(), parameters.z());
        if (!body.serverLevel().hasChunkAt(target)) return "CHUNK_NOT_LOADED";
        Direction face;
        InteractionHand hand;
        try {
            face = Direction.valueOf(parameters.face());
            hand = InteractionHand.valueOf(parameters.hand());
        } catch (IllegalArgumentException invalid) {
            return "INTERACTION_ARGUMENT_INVALID";
        }
        if (parameters.capability().equals("PlaceBlock")) {
            ResourceLocation id = ResourceLocation.tryParse(parameters.itemId());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return "BLOCK_UNKNOWN";
            Item item = BuiltInRegistries.BLOCK.get(id).asItem();
            if (!(item instanceof BlockItem) || !selectHotbarItem(body, item)) return "ITEM_INSUFFICIENT";
            if (!body.serverLevel().getBlockState(target).canBeReplaced()) {
                return "PLACEMENT_TARGET_OCCUPIED";
            }
            BlockPos support = target.relative(face.getOpposite());
            if (body.serverLevel().getBlockState(support).canBeReplaced()) {
                return "PLACEMENT_SUPPORT_MISSING";
            }
            Vec3 hitLocation = Vec3.atCenterOf(support).add(
                    face.getStepX() * 0.5D,
                    face.getStepY() * 0.5D,
                    face.getStepZ() * 0.5D);
            if (body.distanceToSqr(hitLocation) > 25.0D) return "BLOCK_OUT_OF_REACH";
            BlockHitResult hit = new BlockHitResult(hitLocation, face, support, false);
            body.gameMode.useItemOn(
                    body,
                    body.serverLevel(),
                    body.getItemInHand(hand),
                    hand,
                    hit);
            actionGateway.markVanillaGameModeAction(body);
            return body.serverLevel().getBlockState(target).is(BuiltInRegistries.BLOCK.get(id))
                    ? null
                    : "UNCERTAIN_EFFECT";
        }
        Vec3 center = Vec3.atCenterOf(target);
        if (body.distanceToSqr(center) > 25.0D) return "BLOCK_OUT_OF_REACH";
        var visible = body.serverLevel().clip(new ClipContext(
                body.getEyePosition(),
                center,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                body));
        if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(target)) {
            return "BLOCK_NOT_VISIBLE";
        }
        int menuBefore = body.containerMenu.containerId;
        int inventoryBefore = inventoryDigest(body);
        var blockBefore = body.serverLevel().getBlockState(target);
        BlockHitResult hit = new BlockHitResult(center, face, target, false);
        var result = body.gameMode.useItemOn(
                body,
                body.serverLevel(),
                body.getItemInHand(hand),
                hand,
                hit);
        actionGateway.markVanillaGameModeAction(body);
        boolean observed = body.containerMenu.containerId != menuBefore
                || inventoryDigest(body) != inventoryBefore
                || !body.serverLevel().getBlockState(target).equals(blockBefore);
        return observed || result.consumesAction() ? null : "UNCERTAIN_EFFECT";
    }

    private String interactEntity(CompanionPlayer body, SkillParameters parameters) {
        UUID targetId;
        InteractionHand hand;
        try {
            targetId = UUID.fromString(parameters.targetId());
            hand = InteractionHand.valueOf(parameters.hand());
        } catch (IllegalArgumentException invalid) {
            return "ENTITY_ARGUMENT_INVALID";
        }
        Entity target = body.serverLevel().getEntity(targetId);
        if (target == null || !target.isAlive() || target == body) return "ENTITY_NOT_FOUND";
        if (body.distanceToSqr(target) > 25.0D) return "ENTITY_OUT_OF_REACH";
        if (!body.hasLineOfSight(target)) return "ENTITY_NOT_VISIBLE";
        if (parameters.capability().equals("AttackEntity")) {
            if (!(target instanceof LivingEntity living)) return "ENTITY_NOT_LIVING";
            float before = living.getHealth();
            body.attack(living);
            actionGateway.markVanillaAttack(body);
            return !living.isAlive() || living.getHealth() < before ? null : "ENTITY_ATTACK_NO_EFFECT";
        }
        int before = entityDigest(target);
        int inventoryBefore = inventoryDigest(body);
        int menuBefore = body.containerMenu.containerId;
        var result = body.interactOn(target, hand);
        actionGateway.markVanillaEntityInteraction(body);
        Entity after = body.serverLevel().getEntity(targetId);
        boolean observed = after == null
                || entityDigest(after) != before
                || inventoryDigest(body) != inventoryBefore
                || body.containerMenu.containerId != menuBefore;
        return observed || result.consumesAction() ? null : "UNCERTAIN_EFFECT";
    }

    private String menuAction(CompanionPlayer body, SkillParameters parameters) {
        if (!Set.of("CLICK", "QUICK_MOVE", "CLOSE").contains(parameters.menuAction())) {
            return "MENU_ACTION_INVALID";
        }
        MenuSessionTracker.Validation validation =
                MenuSessionTracker.validate(body, parameters.sessionToken());
        if (!validation.valid()) return validation.code();
        if (parameters.menuAction().equals("CLOSE")) {
            body.closeContainer();
            MenuSessionTracker.invalidate(body.getUUID());
            actionGateway.markVanillaMenuAction(body);
            return body.containerMenu == body.inventoryMenu ? null : "MENU_CLOSE_FAILED";
        }
        if (parameters.slot() == null
                || parameters.slot() < 0
                || parameters.slot() >= validation.menu().slots.size()
                || parameters.slot() > 127) {
            return "MENU_SLOT_INVALID";
        }
        int button = parameters.menuAction().equals("CLICK")
                ? parameters.button() == null ? -1 : parameters.button()
                : 0;
        if (button < 0 || button > 1) return "MENU_BUTTON_INVALID";
        int before = menuDigest(validation.menu());
        validation.menu().clicked(
                parameters.slot(),
                button,
                parameters.menuAction().equals("QUICK_MOVE") ? ClickType.QUICK_MOVE : ClickType.PICKUP,
                body);
        validation.menu().broadcastChanges();
        actionGateway.markVanillaMenuAction(body);
        return menuDigest(validation.menu()) != before ? null : "UNCERTAIN_EFFECT";
    }

    private String useItem(CompanionPlayer body, SkillParameters parameters) {
        ResourceLocation id = ResourceLocation.tryParse(parameters.itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return "ITEM_UNKNOWN";
        Item item = BuiltInRegistries.ITEM.get(id);
        if (!selectHotbarItem(body, item)) return "ITEM_INSUFFICIENT";
        int before = inventoryDigest(body);
        var result = body.gameMode.useItem(
                body,
                body.serverLevel(),
                body.getMainHandItem(),
                InteractionHand.MAIN_HAND);
        actionGateway.markVanillaGameModeAction(body);
        return body.isUsingItem() || inventoryDigest(body) != before || result.consumesAction()
                ? null
                : "UNCERTAIN_EFFECT";
    }

    private String dropItem(CompanionPlayer body, SkillParameters parameters) {
        ResourceLocation id = ResourceLocation.tryParse(parameters.itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return "ITEM_UNKNOWN";
        Item item = BuiltInRegistries.ITEM.get(id);
        if (!selectHotbarItem(body, item)) return "ITEM_INSUFFICIENT";
        int available = body.getMainHandItem().getCount();
        int count = Math.min(parameters.quantity(), available);
        if (count < parameters.quantity() && !parameters.allowPartial()) return "ITEM_INSUFFICIENT";
        for (int index = 0; index < count; index++) {
            if (!body.drop(false)) return "DROP_FAILED";
        }
        actionGateway.markVanillaDrop(body);
        return null;
    }

    private static boolean sameDimension(CompanionPlayer body, SkillParameters parameters) {
        return body.serverLevel().dimension().location().toString().equals(parameters.dimension());
    }

    private static boolean selectHotbarItem(CompanionPlayer body, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (body.getInventory().getItem(slot).is(item)) {
                body.getInventory().selected = slot;
                return true;
            }
        }
        int sourceInventory = -1;
        for (int slot = 9; slot < 36; slot++) {
            if (body.getInventory().getItem(slot).is(item)) {
                sourceInventory = slot;
                break;
            }
        }
        int targetHotbar = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (body.getInventory().getItem(slot).isEmpty()) {
                targetHotbar = slot;
                break;
            }
        }
        if (sourceInventory < 0
                || targetHotbar < 0
                || !body.inventoryMenu.getCarried().isEmpty()) {
            return false;
        }
        body.inventoryMenu.clicked(sourceInventory, 0, ClickType.PICKUP, body);
        body.inventoryMenu.clicked(36 + targetHotbar, 0, ClickType.PICKUP, body);
        if (!body.inventoryMenu.getCarried().isEmpty()) {
            body.inventoryMenu.clicked(sourceInventory, 0, ClickType.PICKUP, body);
            return false;
        }
        body.getInventory().selected = targetHotbar;
        return body.getMainHandItem().is(item);
    }

    private static Item resolveItem(String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        return id == null || !BuiltInRegistries.ITEM.containsKey(id)
                ? null
                : BuiltInRegistries.ITEM.get(id);
    }

    private static Item firstFood(CompanionPlayer body) {
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            Item item = body.getInventory().getItem(slot).getItem();
            if (item.isEdible()) return item;
        }
        return null;
    }

    private static int countStorageMenu(CompanionPlayer body, Item item) {
        int total = 0;
        for (Slot slot : body.containerMenu.slots) {
            if (slot.container != body.getInventory() && slot.getItem().is(item)) {
                total += slot.getItem().getCount();
            }
        }
        return total;
    }

    private static int findStorageSlot(CompanionPlayer body, Item item) {
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container != body.getInventory()
                    && slot.mayPickup(body)
                    && slot.getItem().is(item)) {
                return index;
            }
        }
        return -1;
    }

    private static int findInventorySlot(CompanionPlayer body, Item item) {
        ItemStack single = new ItemStack(item);
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container != body.getInventory() || !slot.mayPlace(single)) continue;
            ItemStack existing = slot.getItem();
            if (existing.isEmpty()
                    || existing.is(item) && existing.getCount() < slot.getMaxStackSize(existing)) {
                return index;
            }
        }
        return -1;
    }

    private static int findBodyItemSlot(CompanionPlayer body, Item item) {
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container == body.getInventory()
                    && slot.mayPickup(body)
                    && slot.getItem().is(item)) {
                return index;
            }
        }
        return -1;
    }

    private static int findStorageInsertSlot(CompanionPlayer body, Item item) {
        ItemStack single = new ItemStack(item);
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.slots.get(index);
            if (slot.container == body.getInventory() || !slot.mayPlace(single)) continue;
            ItemStack existing = slot.getItem();
            if (existing.isEmpty()
                    || existing.is(item) && existing.getCount() < slot.getMaxStackSize(existing)) {
                return index;
            }
        }
        return -1;
    }

    private static int inventoryDigest(CompanionPlayer body) {
        int hash = 1;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            hash = 31 * hash + stack.getItem().hashCode();
            hash = 31 * hash + stack.getCount();
            hash = 31 * hash + (stack.hasTag() ? stack.getTag().hashCode() : 0);
        }
        return hash;
    }

    private static int entityDigest(Entity entity) {
        int hash = Boolean.hashCode(entity.isAlive());
        hash = 31 * hash + entity.getPose().hashCode();
        hash = 31 * hash + Boolean.hashCode(entity.isPassenger());
        if (entity instanceof LivingEntity living) {
            hash = 31 * hash + Float.floatToIntBits(living.getHealth());
        }
        return hash;
    }

    private static int menuDigest(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        int hash = menu.containerId;
        hash = 31 * hash + menu.getCarried().hashCode();
        for (var slot : menu.slots) {
            hash = 31 * hash + slot.getItem().hashCode();
        }
        return hash;
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

    private static final class PrimitiveProgress {
        private final SkillParameters parameters;
        private final int startedTick;
        private final java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
        private boolean initialized;
        private int baseline;
        private int itemBaseline;
        private int target;
        private int actions;
        private float destroyProgress;
        private UUID entityId;
        private Vec3 startPosition;

        private PrimitiveProgress(SkillParameters parameters, int startedTick) {
            this.parameters = parameters;
            this.startedTick = startedTick;
        }
    }
}
