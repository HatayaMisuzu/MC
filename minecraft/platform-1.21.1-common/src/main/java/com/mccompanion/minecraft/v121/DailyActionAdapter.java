package com.mccompanion.minecraft.v121;

import com.mccompanion.core.body.daily.DailyActionCommand;
import com.mccompanion.core.body.daily.DailyActionEngine;
import com.mccompanion.core.body.daily.DailyActionKind;
import com.mccompanion.core.body.daily.DailyActionRequest;
import com.mccompanion.core.body.daily.DailyActionSnapshot;
import com.mccompanion.core.body.daily.GlidePathSampler;
import com.mccompanion.core.body.daily.navigation.DailyNavigator;
import com.mccompanion.core.body.daily.navigation.NavPoint;
import com.mccompanion.core.body.daily.navigation.NavigationPort;
import com.mccompanion.core.body.daily.navigation.Vec;
import com.mccompanion.core.navigation.GridPathPlanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The 1.21.1 boundary for the shared daily-action state machine.  This class owns
 * only API calls and live observations; phase selection and policy remain in
 * body-common/DailyActionEngine.
 */
final class DailyActionAdapter implements DailyActionEngine.Adapter {
    private static final int SCAN_RADIUS = 16;
    private static final Map<String, EquipmentSlot> EQUIPMENT = Map.of(
            "HEAD", EquipmentSlot.HEAD, "CHEST", EquipmentSlot.CHEST,
            "LEGS", EquipmentSlot.LEGS, "FEET", EquipmentSlot.FEET,
            "OFF_HAND", EquipmentSlot.OFFHAND, "MAIN_HAND", EquipmentSlot.MAINHAND);

    private final MinecraftServer server;
    private final PlayerActionGateway actionGateway;
    private final SurvivalNavigationAdapter navigation;
    private final DailyActionEngine engine = new DailyActionEngine();
    private final Map<UUID, String> sessions = new HashMap<>();
    private final Map<UUID, DailyActionEngine.Observation> observations = new HashMap<>();
    private final Map<UUID, int[]> bucketBaselines = new HashMap<>();
    private final Map<UUID, DailyActionCommand.Bucket> bucketCommands = new HashMap<>();
    private final Map<UUID, String> bucketBlockBaselines = new HashMap<>();
    private final Map<UUID, int[]> cropProgress = new HashMap<>();
    private final Map<UUID, CropRun> cropRuns = new HashMap<>();
    private final Map<UUID, Integer> cropSeedBeforeReplant = new HashMap<>();
    private final Map<UUID, int[]> breedProgress = new HashMap<>();
    private final Map<UUID, UUID[]> breedTargets = new HashMap<>();
    private final Map<UUID, Integer> breedFoodBaseline = new HashMap<>();
    private final Map<UUID, String> breedFoodItems = new HashMap<>();
    private final Map<UUID, Integer> babyBaselines = new HashMap<>();
    private final Map<UUID, Integer> tradeBaseline = new HashMap<>();
    private final Map<UUID, Integer> tradeOutputBaseline = new HashMap<>();
    private final Map<UUID, Integer> selectedTradeOffer = new HashMap<>();
    private final Map<UUID, String> tradeVillagers = new HashMap<>();
    private final Map<UUID, Integer> tradeMenuIds = new HashMap<>();
    private final Map<UUID, Integer> tradeInputABaseline = new HashMap<>();
    private final Map<UUID, Integer> tradeInputBBaseline = new HashMap<>();
    private final Map<UUID, Integer> enchantXpBaseline = new HashMap<>();
    private final Map<UUID, Integer> enchantItemDigest = new HashMap<>();
    private final Map<UUID, Integer> enchantLapisBaseline = new HashMap<>();
    private final Map<UUID, DailyActionRequest.Position> enchantStations = new HashMap<>();
    private final Map<UUID, Integer> enchantMenuIds = new HashMap<>();
    private final Map<UUID, Integer> enchantOptions = new HashMap<>();
    private final Map<UUID, Integer> fishBaseline = new HashMap<>();
    private final Map<UUID, String> fishingHookIds = new HashMap<>();
    private final Map<UUID, Integer> fishingRodDamageBaseline = new HashMap<>();
    private final Map<UUID, Boolean> fishingReelVerified = new HashMap<>();
    private final Map<UUID, Integer> fishingCastTicks = new HashMap<>();
    private final Map<UUID, Double> fishingHookY = new HashMap<>();
    private final Map<UUID, Boolean> brewLoaded = new HashMap<>();
    private final Map<UUID, Boolean> brewStarted = new HashMap<>();
    private final Map<UUID, Integer> brewResultBaseline = new HashMap<>();
    private final Map<UUID, DailyActionRequest.Position> brewStations = new HashMap<>();
    private final Map<UUID, Integer> brewMenuIds = new HashMap<>();
    private final Map<UUID, Integer> brewBottleCounts = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> brewInitialDigests = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> brewCompletedDigests = new HashMap<>();
    private final Map<UUID, Integer> brewResultsTaken = new HashMap<>();
    private final Map<UUID, DailyActionRequest.Position> glideTargets = new HashMap<>();
    private final Map<UUID, Double> glideBestDistance = new HashMap<>();
    private final Map<UUID, DailyActionCommand.Vehicle> vehicleCommands = new HashMap<>();
    private final Map<UUID, Vec3> vehicleProgressPosition = new HashMap<>();
    private final Map<UUID, Integer> vehicleProgressTick = new HashMap<>();
    private final Map<UUID, String> requestedItems = new HashMap<>();
    private final Map<UUID, DailyActionRequest> requests = new HashMap<>();
    private final Map<UUID, CompanionPlayer> bodies = new HashMap<>();
    private final Map<UUID, BodyNavigationPort> navigationPorts = new HashMap<>();
    private final Map<UUID, DailyNavigator> navigators = new HashMap<>();
    private final Map<UUID, String> navigationKeys = new HashMap<>();

    DailyActionAdapter(MinecraftServer server, PlayerActionGateway actionGateway,
                       SurvivalNavigationAdapter navigation) {
        this.server = server;
        this.actionGateway = actionGateway;
        this.navigation = navigation;
    }

    static boolean supports(String capability) {
        return switch (capability == null ? "" : capability) {
            case "EquipItem", "SleepAtBed", "UseWaterBucket", "UseVehicle", "Fish",
                    "FarmCrop", "BreedAnimals", "TradeWithVillager", "EnchantItem",
                    "BrewPotion", "GlideWithElytra" -> true;
            default -> false;
        };
    }

    void validate(CompanionPlayer body, SkillParameters parameters) {
        request(parameters, body);
        if (!engine.canStart() && !sessions.containsKey(body.getUUID())) {
            throw new IllegalArgumentException("DAILY_ACTION_SESSION_LIMIT");
        }
    }

    void start(CompanionEntry entry, CompanionPlayer body, SkillParameters parameters) {
        String id = entry.companionId + ":" + entry.runtimeBehaviorId;
        DailyActionRequest request = request(parameters, body);
        bind(body);
        String old = sessions.get(entry.companionId);
        if (old != null) {
            try { engine.cancel(old, "SUPERSEDED", this); }
            finally { engine.forget(old); }
        }
        removeNavigator(entry.companionId);
        clearFacts(entry.companionId);
        bodies.put(entry.companionId, body);
        sessions.put(entry.companionId, id);
        observations.remove(entry.companionId);
        requests.put(entry.companionId, request);
        bucketBaselines.remove(entry.companionId); cropProgress.remove(entry.companionId);
        breedProgress.remove(entry.companionId); breedTargets.remove(entry.companionId);
        breedFoodBaseline.remove(entry.companionId); breedFoodItems.remove(entry.companionId);
        babyBaselines.remove(entry.companionId); tradeBaseline.remove(entry.companionId);
        tradeOutputBaseline.remove(entry.companionId); selectedTradeOffer.remove(entry.companionId);
        tradeVillagers.remove(entry.companionId); tradeInputABaseline.remove(entry.companionId);
        tradeInputBBaseline.remove(entry.companionId);
        enchantXpBaseline.remove(entry.companionId); enchantItemDigest.remove(entry.companionId);
        enchantLapisBaseline.remove(entry.companionId); enchantStations.remove(entry.companionId);
        enchantOptions.remove(entry.companionId);
        fishBaseline.put(entry.companionId, fishCount(body));
        fishingHookIds.remove(entry.companionId);
        fishingReelVerified.remove(entry.companionId);
        fishingCastTicks.remove(entry.companionId);
        fishingHookY.remove(entry.companionId);
        fishingRodDamageBaseline.put(entry.companionId, body.getMainHandItem().is(Items.FISHING_ROD)
                ? body.getMainHandItem().getDamageValue() : 0);
        brewLoaded.remove(entry.companionId); brewStarted.remove(entry.companionId);
        brewResultBaseline.put(entry.companionId, count(Items.POTION));
        brewStations.remove(entry.companionId); brewBottleCounts.remove(entry.companionId);
        brewInitialDigests.remove(entry.companionId); brewCompletedDigests.remove(entry.companionId);
        brewResultsTaken.remove(entry.companionId);
        glideTargets.put(entry.companionId, request.target());
        glideBestDistance.remove(entry.companionId);
        requestedItems.put(entry.companionId, parameters.itemId());
        engine.start(id, request, server.getTickCount());
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    void resume(CompanionEntry entry, CompanionPlayer body) {
        String id = sessions.get(entry.companionId);
        if (id == null) throw new IllegalStateException("DAILY_ACTION_SESSION_MISSING");
        DailyActionEngine.Session resumed = engine.resume(id, server.getTickCount());
        observations.put(entry.companionId, resumed.observation());
        bodies.put(entry.companionId, body);
        DailyNavigator navigator = navigators.get(entry.companionId);
        if (navigator != null) navigator.resume(server.getTickCount());
        actionGateway.startBehavior(body, entry.mode, server.getTickCount());
    }

    boolean has(UUID companionId) { return sessions.containsKey(companionId); }

    com.mccompanion.minecraft.bridge.EntityEventTracker.TargetBinding entityEventTarget(UUID companionId) {
        String sessionId = sessions.get(companionId);
        if (sessionId == null) return null;
        try {
            DailyActionEngine.Session session = engine.inspect(sessionId);
            String targetId = switch (session.phase()) {
                case APPROACH_SECOND_ANIMAL, FEED_SECOND -> session.secondaryTargetId();
                default -> session.selectedTargetId();
            };
            if (targetId == null || targetId.isBlank()) return null;
            return new com.mccompanion.minecraft.bridge.EntityEventTracker.TargetBinding(
                    targetId, com.mccompanion.minecraft.bridge.EntityEventTracker.TargetKind.CURRENT);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    boolean tick(CompanionEntry entry, CompanionPlayer body) {
        String id = sessions.get(entry.companionId);
        if (id == null) return false;
        bind(body);
        bodies.put(entry.companionId, body);
        DailyActionEngine.TickResult result = engine.tick(id, snapshot(body), this);
        observations.put(entry.companionId, result.observation());
        return true;
    }

    DailyActionEngine.Observation observation(UUID id) { return observations.get(id); }

    void clearObservation(UUID id) { observations.remove(id); }

    boolean terminal(UUID id) {
        DailyActionEngine.Observation o = observations.get(id);
        return o != null && o.status() != DailyActionEngine.Status.RUNNING
                && o.status() != DailyActionEngine.Status.PAUSED;
    }

    void stop(UUID companionId, String reason, boolean suspension) {
        String id = sessions.get(companionId);
        if (id == null) return;
        CompanionPlayer body = bodies.get(companionId);
        if (body != null) bind(body);
        try {
            if (suspension) {
                DailyActionEngine.Session paused = engine.pause(id, reason, this);
                observations.put(companionId, paused.observation());
            }
            else {
                DailyActionEngine.Session cancelled = engine.cancel(id, reason, this);
                observations.put(companionId, cancelled.observation());
                engine.forget(id);
                sessions.remove(companionId);
                glideTargets.remove(companionId);
                requests.remove(companionId);
                removeNavigator(companionId);
                clearFacts(companionId);
            }
        } catch (RuntimeException ignored) {
            sessions.remove(companionId);
            if (!suspension && body != null) forceCleanup(body);
            removeNavigator(companionId);
            clearFacts(companionId);
        }
    }

    void forget(UUID companionId) {
        String id = sessions.remove(companionId);
        CompanionPlayer body = bodies.get(companionId);
        if (body != null) bind(body);
        if (id != null) {
            try { engine.cancel(id, "FORGOTTEN", this); }
            catch (IllegalArgumentException ignored) { if (body != null) forceCleanup(body); }
            finally { engine.forget(id); }
        } else if (body != null) forceCleanup(body);
        observations.remove(companionId);
        requests.remove(companionId);
        removeNavigator(companionId);
        clearFacts(companionId);
        glideTargets.remove(companionId);
    }

    private void forceCleanup(CompanionPlayer body) {
        actionGateway.stopInput(body);
        DailyNavigator navigator = navigators.get(body.getUUID());
        if (navigator != null) navigator.cancel();
        if (body.getVehicle() instanceof Boat boat) {
            actionGateway.applyVehicleInput(body, boat, boat.getYRot(), false, false, false, false);
        }
        if (body.getVehicle() != null) body.stopRiding();
        if (body.fishing != null) {
            try { reelFishingHook(body); }
            catch (RuntimeException ignored) { }
        }
        if (body.isSleeping()) body.stopSleeping();
        if (body.isFallFlying()) body.stopFallFlying();
        if (body.containerMenu != body.inventoryMenu) {
            try { returnMenuInputs(body); }
            catch (RuntimeException ignored) { }
            finally { body.closeContainer(); }
        }
    }

    @Override public DailyActionCommand.CommandResult execute(DailyActionCommand command) {
        return executeBound(command);
    }

    /* The engine supplies commands without a body reference.  The active body is
       bound for one tick by bind(), keeping the shared contract loader-neutral. */
    private CompanionPlayer activeBody;
    private void bind(CompanionPlayer body) { activeBody = body; }

    private DailyActionCommand.CommandResult executeBound(DailyActionCommand command) {
        if (activeBody == null) return DailyActionCommand.CommandResult.uncertain("BODY_NOT_BOUND");
        try {
            if (command instanceof DailyActionCommand.Navigate n) return navigate(n.target());
            if (command instanceof DailyActionCommand.NavigateEntity n) return navigateEntity(n);
            if (command instanceof DailyActionCommand.StopMovement) {
                actionGateway.stopInput(activeBody); return DailyActionCommand.CommandResult.success();
            }
            if (command instanceof DailyActionCommand.Equip e) return equip(e);
            if (command instanceof DailyActionCommand.Bed b) return bed(b);
            if (command instanceof DailyActionCommand.Bucket b) return bucket(b);
            if (command instanceof DailyActionCommand.Vehicle v) return vehicle(v);
            if (command instanceof DailyActionCommand.Fishing f) return fishing(f);
            if (command instanceof DailyActionCommand.Crop c) return crop(c);
            if (command instanceof DailyActionCommand.Breed b) return breed(b);
            if (command instanceof DailyActionCommand.Trade t) return trade(t);
            if (command instanceof DailyActionCommand.Enchant e) return enchant(e);
            if (command instanceof DailyActionCommand.Brew b) return brew(b);
            if (command instanceof DailyActionCommand.Glide g) return glide(g);
            return DailyActionCommand.CommandResult.rejected("UNSUPPORTED_DAILY_COMMAND");
        } catch (RuntimeException failure) {
            return DailyActionCommand.CommandResult.uncertain("VANILLA_EXCEPTION:" + failure.getClass().getSimpleName());
        }
    }

    @Override public DailyActionCommand.CommandResult maintain(DailyActionCommand command) {
        return executeBound(command);
    }

    @Override public void cleanup(DailyActionEngine.Session session, String reason) {
        if (activeBody == null) return;
        actionGateway.stopInput(activeBody);
        DailyNavigator navigator = navigators.get(activeBody.getUUID());
        if (navigator != null) {
            if (session.status() == DailyActionEngine.Status.PAUSED) navigator.pause(server.getTickCount());
            else navigator.cancel();
        }
        if (activeBody.fishing != null && session.status() != DailyActionEngine.Status.PAUSED) {
            reelFishingHook(activeBody);
        }
        if (activeBody.getVehicle() instanceof Boat boat) actionGateway.applyVehicleInput(
                activeBody, boat, boat.getYRot(), false, false, false, false);
        if (session.request().kind() == DailyActionKind.USE_VEHICLE
                && session.status() != DailyActionEngine.Status.COMPLETE
                && session.status() != DailyActionEngine.Status.PAUSED
                && activeBody.getVehicle() != null) activeBody.stopRiding();
        if (session.request().kind() == DailyActionKind.GLIDE_WITH_ELYTRA
                && session.status() != DailyActionEngine.Status.PAUSED && activeBody.isFallFlying()) {
            activeBody.stopFallFlying();
        }
        if (session.request().kind() == DailyActionKind.SLEEP_AT_BED
                && session.status() != DailyActionEngine.Status.PAUSED
                && session.status() != DailyActionEngine.Status.COMPLETE && activeBody.isSleeping()) {
            activeBody.stopSleeping();
        }
        if (session.status() != DailyActionEngine.Status.PAUSED
                && activeBody.containerMenu != activeBody.inventoryMenu) {
            try { returnMenuInputs(activeBody); }
            catch (RuntimeException ignored) { }
            finally { activeBody.closeContainer(); }
        }
    }

    private DailyActionCommand.CommandResult navigate(DailyActionRequest.Position target) {
        if (target == null || !target.dimension().equals(dimension(activeBody))) return reject("TARGET_MISSING");
        String key = "block:" + target;
        return navigate(key, () -> new DailyNavigator.Goal(
                new NavPoint(target.x(), target.y(), target.z()), target.dimension()));
    }

    private void reelFishingHook(CompanionPlayer body) {
        bind(body);
        if (!body.getMainHandItem().is(Items.FISHING_ROD)) {
            DailyActionCommand.CommandResult selected = ensureMainHand(Items.FISHING_ROD);
            if (!selected.accepted()) throw new IllegalStateException("FISHING_ROD_CLEANUP_MISSING");
        }
        body.gameMode.useItem(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND);
        actionGateway.markVanillaGameModeAction(body);
        if (body.fishing != null) throw new IllegalStateException("FISHING_HOOK_CLEANUP_UNVERIFIED");
    }

    private DailyActionCommand.CommandResult navigateEntity(DailyActionCommand.NavigateEntity command) {
        Entity initial = entity(command.targetId(), activeBody);
        if (initial == null || !initial.isAlive()) return reject("TARGET_NOT_FOUND");
        String key = "entity:" + command.targetId();
        return navigate(key, () -> {
            Entity current = entity(command.targetId(), activeBody);
            if (current == null || !current.isAlive()) throw new IllegalStateException("TARGET_LOST");
            BlockPos position = current.blockPosition();
            return new DailyNavigator.Goal(new NavPoint(position.getX(), position.getY(), position.getZ()),
                    dimension(activeBody));
        });
    }

    private DailyActionCommand.CommandResult navigate(String key, Supplier<DailyNavigator.Goal> goal) {
        UUID id = activeBody.getUUID();
        BodyNavigationPort port = navigationPorts.computeIfAbsent(id,
                ignored -> new BodyNavigationPort(activeBody));
        port.body = activeBody;
        DailyNavigator navigator = navigators.computeIfAbsent(id,
                ignored -> new DailyNavigator(port));
        if (!key.equals(navigationKeys.get(id))) {
            navigator.start(goal, server.getTickCount());
            navigationKeys.put(id, key);
        }
        DailyNavigator.NavigationResult result = navigator.tick(server.getTickCount());
        return switch (result.status()) {
            case RUNNING, PAUSED -> success(result.code());
            case ARRIVED -> success("AT_TARGET");
            case TARGET_UNLOADED, BUDGET_EXCEEDED, UNREACHABLE, STUCK, TIMEOUT, WORLD_CHANGED, CANCELLED -> reject("NAVIGATION_" + result.code());
            case IDLE -> uncertain("NAVIGATION_IDLE");
        };
    }

    private DailyActionCommand.CommandResult equip(DailyActionCommand.Equip command) {
        String destination = command.destination().toUpperCase();
        if ("UNEQUIP".equals(command.action())) {
            EquipmentSlot slot = EQUIPMENT.get(destination);
            if (slot == null) return reject("EQUIPMENT_SLOT_INVALID");
            if (activeBody.getItemBySlot(slot).isEmpty()) return success("ALREADY_UNEQUIPPED");
            int menuSlot = "MAIN_HAND".equals(destination)
                    ? 36 + activeBody.getInventory().selected : equipmentMenuSlot(destination);
            int target = firstEmptyInventoryMenuSlot(activeBody);
            return target < 0 ? reject("INVENTORY_FULL") : moveMenu(activeBody, menuSlot, target, "UNEQUIPPED");
        }
        Item item = item(command.itemId());
        if (item == null) return reject("ITEM_NOT_FOUND");
        int source = command.sourceSlot() >= 0 ? inventoryMenuSlot(command.sourceSlot()) : findItemMenuSlot(item);
        if (source < 0) return reject("ITEM_NOT_FOUND");
        ItemStack sourceStack = activeBody.inventoryMenu.getSlot(source).getItem();
        if (!sourceStack.is(item)) return reject("EQUIPMENT_SOURCE_CHANGED");
        if (!command.stackDigest().isBlank()
                && !command.stackDigest().equals(Integer.toString(stackDigest(sourceStack)))) {
            return reject("EQUIPMENT_SOURCE_CHANGED");
        }
        if (sourceStack.isDamageableItem() && sourceStack.getDamageValue() >= sourceStack.getMaxDamage() - 1) {
            return reject("EQUIPMENT_UNUSABLE");
        }
        if ("AUTO".equals(destination)) destination = autoEquipment(item);
        if ("MAIN_HAND".equals(destination)) {
            int inventorySlot = inventorySlotFromMenu(source);
            int hotbar = inventorySlot >= 0 && inventorySlot < 9
                    ? inventorySlot : activeBody.getInventory().getSuitableHotbarSlot();
            if (hotbar < 0 || hotbar > 8) return reject("HOTBAR_UNAVAILABLE");
            if (inventorySlot < 0 || inventorySlot >= 9) {
                activeBody.inventoryMenu.clicked(source, hotbar, ClickType.SWAP, activeBody);
                actionGateway.markVanillaMenuAction(activeBody);
            }
            actionGateway.selectHotbarSlot(activeBody, hotbar);
            return activeBody.getMainHandItem().is(item)
                    ? success("EQUIPPED") : uncertain("EQUIPMENT_NOT_VERIFIED");
        }
        if ("OFF_HAND".equals(destination)) return moveMenu(activeBody, source, 45, "EQUIPPED");
        if (!EQUIPMENT.containsKey(destination)) return reject("EQUIPMENT_SLOT_INVALID");
        return moveMenu(activeBody, source, equipmentMenuSlot(destination), "EQUIPPED");
    }

    private DailyActionCommand.CommandResult bed(DailyActionCommand.Bed command) {
        if ("WAKE".equals(command.action())) {
            activeBody.stopSleeping();
            actionGateway.markVanillaGameModeAction(activeBody);
            return !activeBody.isSleeping() ? success("AWAKE") : uncertain("WAKE_NOT_VERIFIED");
        }
        if (command.target() == null) return reject("BED_NOT_FOUND");
        BlockPos target = block(command.target());
        if (!(activeBody.serverLevel().getBlockState(target).getBlock() instanceof BedBlock)) return reject("BED_LOST");
        if (!activeBody.serverLevel().dimensionType().bedWorks()) return reject("BED_WRONG_DIMENSION");
        var result = activeBody.startSleepInBed(target);
        actionGateway.markVanillaGameModeAction(activeBody);
        if (result.left().isPresent()) return reject("BED_" + result.left().orElseThrow().name());
        return success(activeBody.isSleeping() ? "SLEEPING" : "SLEEP_REQUESTED");
    }

    private DailyActionCommand.CommandResult bucket(DailyActionCommand.Bucket command) {
        if (command.target() == null) return reject("TARGET_MISSING");
        boolean filling = "FILL_BUCKET".equals(command.action());
        Item required = filling ? Items.BUCKET : Items.WATER_BUCKET;
        DailyActionCommand.CommandResult selected = ensureMainHand(required);
        if (!selected.accepted()) return selected;
        BlockPos target = block(command.target());
        Direction face = direction(command.direction());
        BlockState before = activeBody.serverLevel().getBlockState(target);
        if (filling && (!before.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                || !before.getFluidState().isSource())) return reject("WATER_SOURCE_INVALID");
        if (!filling && !before.canBeReplaced()) return reject("WATER_TARGET_INVALID");
        if (!activeBody.mayUseItemAt(target, face, activeBody.getMainHandItem())) {
            return reject("POSITION_NOT_ALLOWED");
        }
        UUID id = activeBody.getUUID();
        bucketBaselines.put(id, new int[]{count(Items.BUCKET), count(Items.WATER_BUCKET)});
        bucketCommands.put(id, command);
        bucketBlockBaselines.put(id, blockAndFluidId(target));
        Vec3 look = filling ? Vec3.atCenterOf(target)
                : Vec3.atCenterOf(target.relative(face.getOpposite())).add(
                face.getStepX() * .5D, face.getStepY() * .5D, face.getStepZ() * .5D);
        actionGateway.lookAt(activeBody, look);
        InteractionResult result = activeBody.gameMode.useItem(activeBody, activeBody.serverLevel(),
                activeBody.getMainHandItem(), InteractionHand.MAIN_HAND);
        actionGateway.markVanillaGameModeAction(activeBody);
        return result.consumesAction() ? success("BUCKET_USED") : uncertain("BUCKET_USE_REJECTED");
    }

    private DailyActionCommand.CommandResult vehicle(DailyActionCommand.Vehicle command) {
        if ("DISEMBARK_VEHICLE".equals(command.action())) { activeBody.stopRiding(); return activeBody.getVehicle() == null ? success("DISEMBARKED") : uncertain("DISEMBARK_NOT_VERIFIED"); }
        Entity target = entity(command.targetId(), activeBody);
        if (target == null && activeBody.getVehicle() != null) target = activeBody.getVehicle();
        if (target == null) return reject("VEHICLE_NOT_FOUND");
        if (!(target instanceof Boat) && !(target instanceof AbstractMinecart)) {
            return reject("VEHICLE_TYPE_UNSUPPORTED");
        }
        if ("BOARD_VEHICLE".equals(command.action())) {
            InteractionResult interaction = activeBody.interactOn(target, InteractionHand.MAIN_HAND);
            actionGateway.markVanillaEntityInteraction(activeBody);
            if (!interaction.consumesAction() && activeBody.getVehicle() != target
                    && target instanceof VehicleEntity vehicle) {
                // Some server-side test/player connections do not route the initial
                // ServerPlayer interactOn callback through the vehicle's interaction
                // hook. Retry the same vanilla interaction exactly once, only after
                // observing that no passenger was mounted.
                vehicle.interact(activeBody, InteractionHand.MAIN_HAND);
                actionGateway.markVanillaEntityInteraction(activeBody);
            }
            return activeBody.getVehicle() == target ? success("BOARDED") : interaction.consumesAction() ? success("MOUNT_REQUESTED") : uncertain("MOUNT_NOT_VERIFIED");
        }
        if (!"TRAVEL_VEHICLE".equals(command.action())) return success("VEHICLE_OBSERVED");
        if (activeBody.getVehicle() != target) return reject("VEHICLE_CHANGED");
        vehicleCommands.put(activeBody.getUUID(), command);
        if (target instanceof Boat boat) {
            if (command.target() == null) return reject("VEHICLE_DESTINATION_MISSING");
            Vec3 delta = vec(command.target()).subtract(boat.position());
            float desiredYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            float difference = net.minecraft.util.Mth.wrapDegrees(desiredYaw - boat.getYRot());
            actionGateway.applyVehicleInput(activeBody, boat, desiredYaw,
                    difference < -4.0F, difference > 4.0F, true, false);
            return success("VEHICLE_MOVING");
        }
        if (target instanceof AbstractMinecart) return success("MINECART_RIDING");
        return reject("VEHICLE_TYPE_UNSUPPORTED");
    }

    private DailyActionCommand.CommandResult fishing(DailyActionCommand.Fishing command) {
        if ("CAST_LINE".equals(command.action())) {
            if (!activeBody.getMainHandItem().is(Items.FISHING_ROD)) return reject("FISHING_ROD_MISSING");
            fishingRodDamageBaseline.put(activeBody.getUUID(), activeBody.getMainHandItem().getDamageValue());
            if (command.waterTarget() != null) actionGateway.lookAt(activeBody, vec(command.waterTarget()));
            activeBody.gameMode.useItem(activeBody, activeBody.serverLevel(), activeBody.getMainHandItem(), InteractionHand.MAIN_HAND);
            actionGateway.markVanillaGameModeAction(activeBody);
            if (activeBody.fishing != null) {
                fishingHookIds.put(activeBody.getUUID(), activeBody.fishing.getUUID().toString());
                fishingCastTicks.put(activeBody.getUUID(), server.getTickCount());
                fishingHookY.put(activeBody.getUUID(), activeBody.fishing.getY());
                return success("LINE_CAST");
            }
            return uncertain("CAST_NOT_VERIFIED");
        }
        if ("WAIT_BITE".equals(command.action())) return biting() ? success("BITE_OBSERVED") : success("WAITING_BITE");
        if ("REEL_LINE".equals(command.action())) {
            if (activeBody.fishing == null) return reject("FISHING_HOOK_MISSING");
            activeBody.gameMode.useItem(activeBody, activeBody.serverLevel(), activeBody.getMainHandItem(), InteractionHand.MAIN_HAND);
            actionGateway.markVanillaGameModeAction(activeBody);
            boolean retrieved = activeBody.fishing == null;
            if (retrieved) fishingReelVerified.put(activeBody.getUUID(), true);
            return retrieved ? success("LINE_RETRIEVED") : uncertain("REEL_NOT_VERIFIED");
        }
        if ("VERIFY_LOOT".equals(command.action())) {
            ItemEntity nearest = activeBody.serverLevel().getEntitiesOfClass(
                            ItemEntity.class, activeBody.getBoundingBox().inflate(8.0D),
                            entity -> entity.isAlive() && isFish(entity.getItem()))
                    .stream().min(java.util.Comparator.comparingDouble(activeBody::distanceToSqr))
                    .orElse(null);
            // Vanilla fishing already pulls the caught item toward the player.  Do not
            // blindly chase a drop into water (which can trigger the safety reflex and
            // drown the body); only perform the real pickup when it is within reach.
            if (nearest != null && nearest.distanceToSqr(activeBody) <= 2.25D) {
                actionGateway.stopInput(activeBody);
                nearest.playerTouch(activeBody);
            }
        }
        return success("FISHING_OBSERVED");
    }

    private DailyActionCommand.CommandResult crop(DailyActionCommand.Crop command) {
        if (command.target() == null) return reject("CROP_NOT_FOUND");
        BlockPos p = block(command.target());
        if ("HARVEST_CROP".equals(command.action())) {
            BlockState before = activeBody.serverLevel().getBlockState(p);
            if (!(before.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(before)) {
                return reject("CROP_NOT_MATURE");
            }
            String cropId = id(before.getBlock());
            if (!command.cropId().isBlank() && !command.cropId().equals(cropId)) {
                return reject("CROP_CHANGED");
            }
            String seed = cropSeed(before);
            String harvest = cropHarvest(before);
            cropRuns.put(activeBody.getUUID(), new CropRun(command.target(), cropId, seed, harvest,
                    countId(harvest), countId(seed)));
            boolean ok = activeBody.gameMode.destroyBlock(p);
            actionGateway.markVanillaGameModeAction(activeBody);
            if (ok) cropProgress.put(activeBody.getUUID(), new int[]{1, 0, 0});
            return ok && activeBody.serverLevel().getBlockState(p).isAir()
                    ? success("CROP_HARVESTED") : uncertain("HARVEST_NOT_VERIFIED");
        }
        if ("PICKUP_CROP".equals(command.action())) {
            CropRun run = cropRuns.get(activeBody.getUUID());
            Item harvest = run == null ? null : item(run.harvestItem());
            Item seed = run == null ? null : item(run.seedItem());
            ItemEntity nearest = activeBody.serverLevel().getEntitiesOfClass(
                            ItemEntity.class, activeBody.getBoundingBox().inflate(8.0D),
                            entity -> entity.isAlive() && (entity.getItem().is(harvest)
                                    || entity.getItem().is(seed)))
                    .stream().min(java.util.Comparator.comparingDouble(activeBody::distanceToSqr))
                    .orElse(null);
            if (nearest != null && nearest.distanceToSqr(activeBody) > 2.25D) {
                Vec3 delta = nearest.position().subtract(activeBody.position());
                actionGateway.applyMoveInput(activeBody,
                        (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)),
                        delta.y > .6D || activeBody.horizontalCollision);
            } else if (nearest != null) {
                actionGateway.stopInput(activeBody);
                nearest.playerTouch(activeBody);
            }
            boolean picked = run != null && countId(run.harvestItem()) > run.harvestBefore();
            if (picked) cropProgress.computeIfAbsent(activeBody.getUUID(), ignored -> new int[]{0,0,0})[1]=1;
            return success(picked ? "DROPS_PICKED_UP" : "WAITING_FOR_DROPS");
        }
        if ("REPLANT_CROP".equals(command.action())) {
            CropRun run = cropRuns.get(activeBody.getUUID());
            String seedId = run == null ? command.seedItem() : run.seedItem();
            Item seed = item(seedId); if (seed == null) return reject("SEED_MISSING");
            if (!activeBody.serverLevel().getBlockState(p.below()).is(Blocks.FARMLAND)) {
                return reject("FARMLAND_INVALID");
            }
            DailyActionCommand.CommandResult selected = ensureMainHand(seed);
            if (!selected.accepted()) return reject("SEED_MISSING");
            cropSeedBeforeReplant.put(activeBody.getUUID(), count(seed));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p.below()), Direction.UP, p.below(), false);
            InteractionResult result = activeBody.gameMode.useItemOn(activeBody, activeBody.serverLevel(), activeBody.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            actionGateway.markVanillaGameModeAction(activeBody);
            BlockState planted = activeBody.serverLevel().getBlockState(p);
            boolean verified = result.consumesAction() && planted.getBlock() instanceof CropBlock plantedCrop
                    && plantedCrop.getAge(planted) == 0;
            if (verified) cropProgress.computeIfAbsent(activeBody.getUUID(), ignored -> new int[]{0,0,0})[2]=1;
            return verified ? success("CROP_REPLANTED") : uncertain("REPLANT_NOT_VERIFIED");
        }
        return success("CROP_OBSERVED");
    }

    private DailyActionCommand.CommandResult breed(DailyActionCommand.Breed command) {
        Animal first = animal(command.firstId(), activeBody), second = animal(command.secondId(), activeBody);
        if (first == null || second == null) return reject("ANIMALS_INSUFFICIENT");
        if (!"FEED_FIRST".equals(command.action()) && !"FEED_SECOND".equals(command.action())) {
            return success("BREEDING_OBSERVED");
        }
        Item food = item(command.foodItem()); if (food == null) return reject("BREEDING_FOOD_MISSING");
        DailyActionCommand.CommandResult selected = ensureMainHand(food);
        if (!selected.accepted()) return reject("BREEDING_FOOD_MISSING");
        Animal target = "FEED_SECOND".equals(command.action()) ? second : first;
        if (target.isBaby() || !target.isFood(activeBody.getMainHandItem()) || !target.canFallInLove()) {
            return reject("ANIMAL_NOT_BREEDABLE");
        }
        UUID id = activeBody.getUUID();
        breedTargets.putIfAbsent(id, new UUID[]{first.getUUID(), second.getUUID()});
        breedFoodItems.putIfAbsent(id, command.foodItem());
        breedFoodBaseline.putIfAbsent(id, count(food));
        babyBaselines.putIfAbsent(id, babyCount(activeBody, first.getType()));
        int beforeFood = count(food);
        InteractionResult result = activeBody.interactOn(target, InteractionHand.MAIN_HAND);
        actionGateway.markVanillaEntityInteraction(activeBody);
        boolean verified = result.consumesAction() && target.isInLove() && count(food) < beforeFood;
        if (verified) {
            int[] progress=breedProgress.computeIfAbsent(id, ignored -> new int[]{0,0});
            if(target==first)progress[0]=1; else progress[1]=1;
        }
        return verified ? success("ANIMAL_FED") : uncertain("FEED_NOT_VERIFIED");
    }

    private DailyActionCommand.CommandResult trade(DailyActionCommand.Trade command) {
        Entity target = entity(command.villagerId(), activeBody);
        if ("OPEN_TRADE".equals(command.action())) {
            if (target == null) return reject("VILLAGER_NOT_FOUND");
            InteractionResult result = activeBody.interactOn(target, InteractionHand.MAIN_HAND);
            actionGateway.markVanillaEntityInteraction(activeBody);
            if (!(activeBody.containerMenu instanceof MerchantMenu)) return uncertain("TRADE_MENU_NOT_VERIFIED");
            if (result.consumesAction()) {
                tradeVillagers.put(activeBody.getUUID(), target.getUUID().toString());
                tradeMenuIds.put(activeBody.getUUID(), activeBody.containerMenu.containerId);
            }
            return result.consumesAction() ? success("TRADE_OPEN_REQUESTED") : uncertain("TRADE_OPEN_REJECTED");
        }
        if (!(activeBody.containerMenu instanceof MerchantMenu menu)) return reject("TRADE_MENU_INVALIDATED");
        if (menu.getOffers().isEmpty()) return reject("TRADE_OFFER_NOT_FOUND");
        if (command.offerIndex() < 0 || command.offerIndex() >= menu.getOffers().size()) {
            return reject("TRADE_OFFER_NOT_FOUND");
        }
        int offer = command.offerIndex();
        menu.setSelectionHint(offer);
        selectedTradeOffer.put(activeBody.getUUID(), offer);
        MerchantOffer selected = menu.getOffers().get(offer);
        UUID bodyId = activeBody.getUUID();
        tradeBaseline.putIfAbsent(bodyId, selected.getUses());
        tradeOutputBaseline.putIfAbsent(bodyId, count(selected.getResult().getItem()));
        tradeInputABaseline.putIfAbsent(bodyId, count(selected.getCostA().getItem()));
        tradeInputBBaseline.putIfAbsent(bodyId, selected.getCostB().isEmpty()
                ? 0 : count(selected.getCostB().getItem()));
        if ("SELECT_TRADE".equals(command.action())) {
            if (selected.isOutOfStock()) return reject("TRADE_DISABLED");
            menu.tryMoveItems(offer);
            menu.broadcastChanges();
            actionGateway.markVanillaMenuAction(activeBody);
            return selected.satisfiedBy(menu.getSlot(0).getItem(), menu.getSlot(1).getItem())
                    ? success("TRADE_INPUTS_LOADED") : reject("TRADE_RESOURCES_INSUFFICIENT");
        }
        if ("EXECUTE_TRADE".equals(command.action())) {
            if (inventoryCapacity(activeBody, selected.getResult()) < selected.getResult().getCount()) {
                return reject("INVENTORY_FULL");
            }
            menu.clicked(2, 0, ClickType.QUICK_MOVE, activeBody);
            menu.broadcastChanges();
            actionGateway.markVanillaMenuAction(activeBody);
            return selected.getUses() > tradeBaseline.getOrDefault(bodyId, selected.getUses())
                    && count(selected.getResult().getItem())
                    > tradeOutputBaseline.getOrDefault(bodyId, count(selected.getResult().getItem()))
                    ? success("TRADE_EXECUTED") : uncertain("TRADE_NOT_VERIFIED");
        }
        return success("TRADE_MENU_UPDATED");
    }

    private DailyActionCommand.CommandResult enchant(DailyActionCommand.Enchant command) {
        if (!(activeBody.containerMenu instanceof EnchantmentMenu menu)) {
            if ("OPEN_ENCHANT".equals(command.action()) && command.station() != null) {
                BlockPos p = block(command.station());
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false);
                InteractionResult result = activeBody.gameMode.useItemOn(activeBody, activeBody.serverLevel(), activeBody.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                actionGateway.markVanillaGameModeAction(activeBody);
                if (result.consumesAction() && activeBody.containerMenu instanceof EnchantmentMenu) {
                    enchantStations.put(activeBody.getUUID(), command.station());
                    enchantMenuIds.put(activeBody.getUUID(), activeBody.containerMenu.containerId);
                }
                return result.consumesAction() ? success("ENCHANT_OPEN_REQUESTED") : uncertain("ENCHANT_OPEN_REJECTED");
            }
            return reject("ENCHANTMENT_MENU_INVALIDATED");
        }
        if ("SELECT_ENCHANT".equals(command.action())) {
            if (command.option() < 0 || command.option() >= menu.costs.length) return reject("ENCHANT_OPTION_INVALID");
            UUID id = activeBody.getUUID();
            enchantOptions.put(id, command.option());
            enchantXpBaseline.putIfAbsent(id, activeBody.experienceLevel);
            enchantLapisBaseline.putIfAbsent(id, totalLapis(activeBody));
            if (menu.getSlot(0).getItem().isEmpty()) {
                Item requested = item(requestedItem(activeBody));
                int source = requested == null ? -1 : findOpenMenuItemSlot(activeBody, requested);
                if (source < 0 || !moveCount(activeBody, source, 0, 1)) return reject("ENCHANT_ITEM_MISSING");
            }
            if (menu.getSlot(1).getItem().isEmpty()) {
                int source = findOpenMenuItemSlot(activeBody, Items.LAPIS_LAZULI);
                int required = command.option() + 1;
                if (source < 0 || !moveCount(activeBody, source, 1, required)) return reject("LAPIS_INSUFFICIENT");
            }
            enchantItemDigest.putIfAbsent(id, stackDigest(menu.getSlot(0).getItem()));
            menu.broadcastChanges();
            actionGateway.markVanillaMenuAction(activeBody);
            return success("ENCHANT_INPUTS_LOADED");
        }
        if ("APPLY_ENCHANT".equals(command.action())) {
            return menu.clickMenuButton(activeBody, command.option()) ? success("ENCHANT_APPLIED") : reject("ENCHANT_OPTION_INVALID");
        }
        return success("ENCHANTMENT_OBSERVED");
    }

    private DailyActionCommand.CommandResult brew(DailyActionCommand.Brew command) {
        if (!(activeBody.containerMenu instanceof BrewingStandMenu menu)) {
            if ("OPEN_BREW".equals(command.action()) && command.station() != null) {
                BlockPos p = block(command.station());
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false);
                InteractionResult result = activeBody.gameMode.useItemOn(activeBody, activeBody.serverLevel(), activeBody.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                actionGateway.markVanillaGameModeAction(activeBody);
                if (result.consumesAction() && activeBody.containerMenu instanceof BrewingStandMenu) {
                    brewStations.put(activeBody.getUUID(), command.station());
                    brewMenuIds.put(activeBody.getUUID(), activeBody.containerMenu.containerId);
                }
                return result.consumesAction() ? success("BREW_OPEN_REQUESTED") : uncertain("BREW_OPEN_REJECTED");
            }
            return reject("BREWING_MENU_INVALIDATED");
        }
        if ("LOAD_BREW".equals(command.action())) {
            UUID id = activeBody.getUUID();
            int bottles = Math.max(1, Math.min(3, command.bottleCount()));
            brewBottleCounts.put(id, bottles);
            for (int target = 0; target < bottles; target++) if (menu.getSlot(target).getItem().isEmpty()) {
                int source = findOpenMenuItemSlot(activeBody, Items.POTION);
                if (source < 0 || !moveCount(activeBody, source, target, 1)) return reject("BREWING_BOTTLES_MISSING");
            }
            Map<Integer, String> initial = new HashMap<>();
            for (int slot = 0; slot < bottles; slot++) {
                ItemStack bottle = menu.getSlot(slot).getItem();
                if (bottle.isEmpty()) return reject("BREWING_BOTTLES_MISSING");
                initial.put(slot, Integer.toString(stackDigest(bottle)));
            }
            brewInitialDigests.putIfAbsent(id, Map.copyOf(initial));
            Item ingredient = item(command.itemId()); if (ingredient == null) return reject("BREWING_MATERIALS_INSUFFICIENT");
            int source = findOpenMenuItemSlot(activeBody, ingredient), target = 3;
            if (source < 0 || !moveCount(activeBody, source, target, 1)) return reject("BREWING_MATERIALS_INSUFFICIENT");
            if (menu.getSlot(4).getItem().isEmpty()) {
                source = findOpenMenuItemSlot(activeBody, Items.BLAZE_POWDER);
                if (source < 0 || !moveCount(activeBody, source, 4, 1)) return reject("BREWING_FUEL_MISSING");
            }
            menu.broadcastChanges();
            actionGateway.markVanillaMenuAction(activeBody);
            brewLoaded.put(id, true);
        }
        if ("TAKE_BREW".equals(command.action())) {
            UUID id = activeBody.getUUID();
            int bottles = brewBottleCounts.getOrDefault(id, Math.max(1, command.bottleCount()));
            Map<Integer, String> completed = new HashMap<>();
            for (int slot = 0; slot < bottles; slot++) {
                ItemStack result = menu.getSlot(slot).getItem();
                if (!result.isEmpty()) completed.put(slot, Integer.toString(stackDigest(result)));
            }
            Map<Integer, String> expected = brewCompletedDigests.getOrDefault(id, Map.of());
            if (!expected.isEmpty() && !expected.equals(completed)) return reject("BREWING_CONTENT_CHANGED");
            completed.clear();
            int taken = 0;
            for (int slot = 0; slot < bottles; slot++) {
                ItemStack result = menu.getSlot(slot).getItem();
                if (result.isEmpty()) continue;
                String digest = Integer.toString(stackDigest(result));
                completed.put(slot, digest);
                int before = countInventoryDigest(activeBody, digest);
                menu.clicked(slot, 0, ClickType.QUICK_MOVE, activeBody);
                menu.broadcastChanges();
                if (menu.getSlot(slot).getItem().isEmpty()
                        && countInventoryDigest(activeBody, digest) > before) taken++;
            }
            brewCompletedDigests.put(id, Map.copyOf(completed));
            brewResultsTaken.put(id, taken);
            actionGateway.markVanillaMenuAction(activeBody);
            if (taken < bottles) return uncertain("BREW_RESULTS_NOT_VERIFIED");
        }
        return success("BREWING_OBSERVED");
    }

    private DailyActionCommand.CommandResult glide(DailyActionCommand.Glide command) {
        if ("EQUIP_ELYTRA".equals(command.action())) return equip(new DailyActionCommand.Equip(
                "EQUIP", command.elytraItemId(), command.sourceSlot(), "CHEST", command.stackDigest()));
        if ("START_GLIDE".equals(command.action())) {
            boolean started = activeBody.tryToStartFallFlying();
            actionGateway.markVanillaGameModeAction(activeBody);
            return started ? success("GLIDE_STARTED") : reject("GLIDE_START_UNSAFE");
        }
        Vec3 target = command.target() == null ? activeBody.position() : vec(command.target());
        Vec3 delta = target.subtract(activeBody.position());
        if (!activeBody.isFallFlying() && !activeBody.onGround()) return reject("GLIDE_ENDED_EARLY");
        if (activeBody.isFallFlying()) {
            actionGateway.lookAt(activeBody, target);
            actionGateway.applyMoveInput(activeBody,
                    (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)), false);
        }
        return success("GLIDE_CONTROLLED");
    }

    private DailyActionRequest request(SkillParameters p, CompanionPlayer body) {
        DailyActionKind kind = switch (p.capability()) {
            case "EquipItem" -> DailyActionKind.EQUIP_ITEM; case "SleepAtBed" -> DailyActionKind.SLEEP_AT_BED;
            case "UseWaterBucket" -> DailyActionKind.USE_WATER_BUCKET; case "UseVehicle" -> DailyActionKind.USE_VEHICLE;
            case "Fish" -> DailyActionKind.FISH; case "FarmCrop" -> DailyActionKind.FARM_CROP;
            case "BreedAnimals" -> DailyActionKind.BREED_ANIMALS; case "TradeWithVillager" -> DailyActionKind.TRADE_WITH_VILLAGER;
            case "EnchantItem" -> DailyActionKind.ENCHANT_ITEM; case "BrewPotion" -> DailyActionKind.BREW_POTION;
            case "GlideWithElytra" -> DailyActionKind.GLIDE_WITH_ELYTRA; default -> throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
        };
        String dimension = p.hasBlockTarget() ? p.dimension() : dimension(body);
        DailyActionRequest.Position target = p.hasBlockTarget() ? new DailyActionRequest.Position(dimension, p.x(), p.y(), p.z()) : null;
        int radius = kind == DailyActionKind.SLEEP_AT_BED ? Math.max(1, Math.min(16, p.quantity()))
                : p.button() == null ? 16 : Math.max(1, Math.min(16, p.button()));
        int maxTicks = p.durationTicks() == null || p.durationTicks() <= 0
                ? 20 * 60 * 5 : Math.min(20 * 60 * 30, p.durationTicks() + 20 * 20);
        return new DailyActionRequest(kind, p.itemId(), p.targetId(), p.secondaryTargetId(), dimension, target,
                action(p), p.hand(), p.face(), p.slot() == null ? -1 : p.slot(), p.quantity(),
                p.durationTicks() == null ? 0 : p.durationTicks(), maxTicks, radius);
    }

    private static String action(SkillParameters p) { return switch (p.capability()) {
        case "EquipItem" -> p.menuAction().isBlank() ? (p.hand().equals("AUTO") ? "EQUIP" : "EQUIP") : p.menuAction();
        case "SleepAtBed" -> p.menuAction().isBlank() ? "SLEEP" : p.menuAction();
        case "UseWaterBucket" -> p.menuAction().isBlank() ? (p.itemId().equals("minecraft:water_bucket") ? "EMPTY" : "FILL") : p.menuAction();
        case "UseVehicle" -> p.menuAction().isBlank() ? "TRAVEL" : p.menuAction();
        default -> switch (p.capability()) { case "Fish" -> "FISH"; case "FarmCrop" -> "HARVEST_REPLANT"; case "BreedAnimals" -> "BREED"; case "TradeWithVillager" -> "TRADE"; case "EnchantItem" -> "ENCHANT"; case "BrewPotion" -> "BREW"; case "GlideWithElytra" -> "GLIDE"; default -> ""; };
    }; }

    private DailyActionSnapshot snapshot(CompanionPlayer body) {
        bind(body);
        String dimension = dimension(body);
        DailyActionRequest request = requests.get(body.getUUID());
        BlockState toolTarget = targetBlockState(request);
        Map<String, DailyActionSnapshot.ItemFact> inventory = new HashMap<>();
        List<DailyActionSnapshot.ItemCandidate> items = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = body.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String itemId = id(stack.getItem());
            String digest = Integer.toString(stackDigest(stack));
            var fact = new DailyActionSnapshot.ItemFact(itemId, stack.getCount(), stack.getDamageValue(),
                    stack.getMaxDamage(), Map.of("digest", digest));
            inventory.merge(itemId, fact, (first, second) -> new DailyActionSnapshot.ItemFact(
                    itemId, first.count() + second.count(), 0, 0, Map.of()));
            double speed = toolTarget == null ? 1.0D : stack.getDestroySpeed(toolTarget);
            boolean correctTool = toolTarget != null && stack.isCorrectToolForDrops(toolTarget);
            Map<String, String> traits = new HashMap<>();
            traits.put("equipmentSlot", autoEquipment(stack.getItem()));
            traits.put("componentDigest", digest);
            if (request != null && request.targetBlockId() != null) {
                traits.put("targetBlock", request.targetBlockId());
            }
            String weaponType = weaponType(stack.getItem());
            if (!weaponType.isBlank()) traits.put("weaponType", weaponType);
            items.add(new DailyActionSnapshot.ItemCandidate(i, itemId, stack.getCount(),
                    stack.getDamageValue(), stack.getMaxDamage(), correctTool, speed,
                    weaponScore(stack.getItem()), traits));
        }
        Map<String, DailyActionSnapshot.ItemFact> equipment = new HashMap<>();
        for (var entry : EQUIPMENT.entrySet()) {
            ItemStack stack = body.getItemBySlot(entry.getValue());
            if (!stack.isEmpty()) equipment.put(entry.getKey(), new DailyActionSnapshot.ItemFact(
                    id(stack.getItem()), stack.getCount(), stack.getDamageValue(), stack.getMaxDamage(),
                    Map.of("digest", Integer.toString(stackDigest(stack)))));
        }
        List<DailyActionSnapshot.BedCandidate> beds = new ArrayList<>();
        List<DailyActionSnapshot.CropCandidate> crops = new ArrayList<>();
        BlockPos origin = body.blockPosition();
        int radius = request == null ? SCAN_RADIUS : Math.max(1, Math.min(SCAN_RADIUS, request.radius()));
        boolean scanBeds = request != null && request.kind() == DailyActionKind.SLEEP_AT_BED;
        boolean scanCrops = request != null && request.kind() == DailyActionKind.FARM_CROP;
        if (scanCrops && request.target() != null
                && dimension.equals(request.target().dimension())
                && body.serverLevel().hasChunkAt(block(request.target()))) {
            origin = block(request.target());
        }
        if (scanBeds || scanCrops) for (BlockPos position : BlockPos.betweenClosed(
                origin.offset(-radius, -3, -radius), origin.offset(radius, 3, radius))) {
            if (!body.serverLevel().hasChunkAt(position)) continue;
            BlockState state = body.serverLevel().getBlockState(position);
            if (scanBeds && state.getBlock() instanceof BedBlock) {
                boolean occupied = state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
                boolean bedWorks = body.serverLevel().dimensionType().bedWorks();
                beds.add(new DailyActionSnapshot.BedCandidate(toPos(position, dimension), occupied,
                        bedWorks && !occupied, !bedWorks ? "BED_WRONG_DIMENSION"
                        : occupied ? "BED_OCCUPIED" : ""));
            }
            if (scanCrops && state.getBlock() instanceof CropBlock crop) {
                crops.add(new DailyActionSnapshot.CropCandidate(toPos(position, dimension),
                        id(crop), crop.isMaxAge(state), cropSeed(state)));
            }
        }
        if (scanBeds) addExplicitBedCandidate(body, request, dimension, beds);
        List<DailyActionSnapshot.VehicleCandidate> vehicles = new ArrayList<>();
        boolean scanVehicles = request != null && request.kind() == DailyActionKind.USE_VEHICLE;
        if (scanVehicles) for (Entity entity : body.serverLevel().getEntitiesOfClass(
                Entity.class, body.getBoundingBox().inflate(radius), Entity::isAlive)) {
            if (entity instanceof Boat || entity instanceof AbstractMinecart) {
                vehicles.add(new DailyActionSnapshot.VehicleCandidate(entity.getUUID(),
                        id(entity.getType()), toPos(entity.blockPosition(), dimension), true));
            }
        }
        if (scanVehicles) addExplicitVehicleCandidate(body, request, dimension, vehicles);
        List<DailyActionSnapshot.AnimalCandidate> animals = new ArrayList<>();
        List<DailyActionSnapshot.EntityCandidate> entities = new ArrayList<>();
        boolean scanAnimals = request != null && request.kind() == DailyActionKind.BREED_ANIMALS;
        boolean scanEntities = request != null && request.kind() == DailyActionKind.TRADE_WITH_VILLAGER;
        if (scanAnimals || scanEntities) for (LivingEntity entity : body.serverLevel().getEntitiesOfClass(
                LivingEntity.class, body.getBoundingBox().inflate(radius), Entity::isAlive)) {
            String type = id(entity.getType());
            if (scanEntities) entities.add(new DailyActionSnapshot.EntityCandidate(entity.getUUID(), type,
                    toPos(entity.blockPosition(), dimension), entity.isAlive(), body.hasLineOfSight(entity)));
            if (scanAnimals && entity instanceof Animal animal) {
                String food = breedingFood(animal, body);
                animals.add(new DailyActionSnapshot.AnimalCandidate(animal.getUUID(), type,
                        toPos(animal.blockPosition(), dimension), food, !food.isBlank(),
                        !animal.isBaby(), animal.isInLove(), animal.isAlive()));
            }
        }
        if (scanAnimals) addExplicitAnimalCandidates(body, request, dimension, animals);
        if (scanEntities) addExplicitEntityCandidate(body, request.targetId(), dimension, entities);
        Entity ridden = body.getVehicle();
        DailyActionSnapshot.VehicleFact vehicle;
        if (ridden instanceof Boat || ridden instanceof AbstractMinecart) {
            Vec3 previous = vehicleProgressPosition.get(body.getUUID());
            int now = server.getTickCount();
            if (previous == null || previous.distanceToSqr(ridden.position()) > .04D) {
                vehicleProgressPosition.put(body.getUUID(), ridden.position());
                vehicleProgressTick.put(body.getUUID(), now);
            }
            boolean stuck = vehicleCommands.containsKey(body.getUUID())
                    && now - vehicleProgressTick.getOrDefault(body.getUUID(), now) > 80;
            vehicle = new DailyActionSnapshot.VehicleFact(true, ridden.getUUID(), id(ridden.getType()),
                    toPos(ridden.blockPosition(), dimension), true, stuck,
                    body.getHealth() > 4.0F && !body.isInLava() && ridden.isAlive());
        } else {
            vehicle = new DailyActionSnapshot.VehicleFact(false, null, "", null,
                    false, false, true);
        }
        DailyActionRequest.Position selectedTarget = selectedTarget(body.getUUID());
        DailyActionSnapshot.BedCandidate selectedBed = beds.stream()
                .filter(value -> selectedTarget != null && selectedTarget.equals(value.position()))
                .findFirst().orElse(beds.isEmpty() ? null : beds.get(0));
        DailyActionSnapshot.BedFact bed = selectedBed == null
                ? new DailyActionSnapshot.BedFact(false, null, false, "BED_NOT_FOUND")
                : new DailyActionSnapshot.BedFact(true, selectedBed.position(),
                selectedBed.usable(), selectedBed.problem());
        DailyActionCommand.Bucket bucketCommand = bucketCommands.get(body.getUUID());
        DailyActionRequest.Position bucketTarget = bucketCommand == null && request != null
                ? request.target() : bucketCommand == null ? null : bucketCommand.target();
        String bucketAction = bucketCommand == null && request != null ? request.action()
                : bucketCommand == null ? "" : bucketCommand.action().replace("_BUCKET", "");
        String bucketDirection = bucketCommand == null && request != null ? request.direction()
                : bucketCommand == null ? "" : bucketCommand.direction();
        int[] bucketBefore = bucketBaselines.get(body.getUUID());
        String currentBucketBlock = bucketTarget == null ? "" : blockAndFluidId(block(bucketTarget));
        boolean bucketChanged = bucketBefore != null && bucketTarget != null
                && (bucketBefore[0] != count(Items.BUCKET) || bucketBefore[1] != count(Items.WATER_BUCKET))
                && !currentBucketBlock.equals(bucketBlockBaselines.getOrDefault(body.getUUID(), currentBucketBlock));
        BlockState bucketState = bucketTarget == null ? null : body.serverLevel().getBlockState(block(bucketTarget));
        Direction bucketFace = direction(bucketDirection);
        boolean bucketAllowed = bucketTarget == null || body.mayUseItemAt(
                block(bucketTarget), bucketFace, body.getMainHandItem());
        boolean sourceValid = bucketState != null && bucketState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                && bucketState.getFluidState().isSource();
        boolean placementValid = bucketState != null && bucketState.canBeReplaced();
        DailyActionSnapshot.BucketFact bucket = new DailyActionSnapshot.BucketFact(bucketChanged,
                currentBucketBlock, count(Items.BUCKET), count(Items.WATER_BUCKET), bucketAllowed,
                sourceValid, placementValid, bucketAllowed ? "" : "POSITION_NOT_ALLOWED",
                bucketTarget, bucketAction, bucketDirection);
        int lootCount = Math.max(0, fishCount(body)
                - fishBaseline.getOrDefault(body.getUUID(), fishCount(body)));
        String hookId = body.fishing == null
                ? fishingHookIds.getOrDefault(body.getUUID(), "") : body.fishing.getUUID().toString();
        ItemStack rod = body.getMainHandItem();
        int rodDamageDelta = rod.is(Items.FISHING_ROD)
                ? Math.max(0, rod.getDamageValue() - fishingRodDamageBaseline.getOrDefault(body.getUUID(), rod.getDamageValue())) : 0;
        boolean hookAlive = body.fishing != null && body.fishing.isAlive();
        DailyActionSnapshot.FishFact fish = new DailyActionSnapshot.FishFact(
                rod.is(Items.FISHING_ROD), hookAlive, biting(), hookAlive,
                lootCount, !hookAlive && fishingReelVerified.getOrDefault(body.getUUID(), false), hookId,
                rod.isEmpty() ? "" : Integer.toString(stackDigest(rod)), rodDamageDelta);
        CropRun cropRun = cropRuns.get(body.getUUID());
        int[] cropState = cropProgress.get(body.getUUID());
        DailyActionSnapshot.CropFact crop = null;
        if (cropRun != null) {
            BlockState current = body.serverLevel().getBlockState(block(cropRun.target()));
            boolean cropBlock = current.getBlock() instanceof CropBlock;
            int age = cropBlock ? ((CropBlock) current.getBlock()).getAge(current) : -1;
            boolean sameCrop = cropBlock && cropRun.cropId().equals(
                    BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString());
            int harvestedCount = Math.max(0, countId(cropRun.harvestItem()) - cropRun.harvestBefore());
            int seedConsumed = Math.max(0, cropSeedBeforeReplant.getOrDefault(body.getUUID(),
                    countId(cropRun.seedItem())) - countId(cropRun.seedItem()));
            boolean harvested = cropState != null && cropState[0] > 0 && !cropBlock;
            boolean pickedUp = cropState != null && cropState[1] > 0 && harvestedCount > 0;
            boolean replanted = cropState != null && cropState[2] > 0 && sameCrop;
            crop = new DailyActionSnapshot.CropFact(true, cropRun.target(), false,
                    harvested, pickedUp, replanted, age, cropRun.seedItem(), harvestedCount,
                    cropRun.cropId(), cropRun.harvestItem(), harvestedCount, seedConsumed);
        }
        int[] breedState = breedProgress.get(body.getUUID());
        UUID[] parents = breedTargets.get(body.getUUID());
        Animal firstParent = parents == null ? null : animal(parents[0].toString(), body);
        int currentBabies = firstParent == null ? 0 : babyCount(body, firstParent.getType());
        int babyBase = babyBaselines.getOrDefault(body.getUUID(), currentBabies);
        String breedingFood = breedFoodItems.getOrDefault(body.getUUID(), "");
        int foodConsumed = breedingFood.isBlank() ? 0 : Math.max(0,
                breedFoodBaseline.getOrDefault(body.getUUID(), count(item(breedingFood)))
                        - count(item(breedingFood)));
        DailyActionSnapshot.BreedFact breed = new DailyActionSnapshot.BreedFact(
                breedState != null && breedState[0] > 0, breedState != null && breedState[1] > 0,
                currentBabies > babyBase, Math.max(0, currentBabies - babyBase),
                parents == null ? null : parents[0], parents == null ? null : parents[1], foodConsumed);
        DailyActionSnapshot.MenuFact menu = menuFact(body);
        DailyActionRequest.Position glideTarget = glideTargets.get(body.getUUID());
        double glideDistance = glideTarget == null ? Double.POSITIVE_INFINITY
                : body.position().distanceToSqr(vec(glideTarget));
        double bestGlide = glideBestDistance.merge(body.getUUID(), glideDistance, Math::min);
        boolean glideReached = glideTarget != null && glideDistance <= 9.0D;
        boolean terrainSafe = safeGlideTerrain(body, glideTarget,
                currentPhase(body.getUUID()) == com.mccompanion.core.body.daily.DailyActionPhase.LAND);
        ItemStack chest = body.getItemBySlot(EquipmentSlot.CHEST);
        boolean elytraUsable = chest.is(Items.ELYTRA)
                && (!chest.isDamageableItem() || chest.getDamageValue() < chest.getMaxDamage() - 1);
        DailyActionSnapshot.GlideFact glide = new DailyActionSnapshot.GlideFact(
                !body.onGround() && !body.isInWater() && body.getDeltaMovement().y < 0.0D && elytraUsable,
                glideReached, terrainSafe, glideDistance, bestGlide,
                terrainSafe ? "" : "GLIDE_TERRAIN_UNSAFE");
        return new DailyActionSnapshot(server.getTickCount(), body.isAlive(), dimension,
                toPos(body.blockPosition(),dimension), inventory, equipment, bed, bucket, vehicle,
                fish, crop, breed, menu, body.experienceLevel, body.isSleeping(), body.onGround(),
                body.isFallFlying(), items, beds, vehicles, crops, animals, entities,
                tradeFact(body), enchantFact(body), brewFact(body), glide);
    }

    private DailyActionSnapshot.TradeFact tradeFact(CompanionPlayer body) {
        if (!(body.containerMenu instanceof MerchantMenu menu) || menu.getOffers().isEmpty()) return null;
        int offerIndex = Math.min(selectedTradeOffer.getOrDefault(body.getUUID(), 0),
                menu.getOffers().size() - 1);
        MerchantOffer offer = menu.getOffers().get(offerIndex);
        ItemStack paymentA = menu.getSlot(0).getItem(), paymentB = menu.getSlot(1).getItem();
        ItemStack costA = offer.getCostA(), costB = offer.getCostB();
        UUID bodyId = body.getUUID();
        int output = Math.max(0, count(offer.getResult().getItem())
                - tradeOutputBaseline.getOrDefault(bodyId, count(offer.getResult().getItem())));
        int consumedA = Math.max(0, tradeInputABaseline.getOrDefault(bodyId, count(costA.getItem()))
                - count(costA.getItem()));
        int consumedB = costB.isEmpty() ? 0 : Math.max(0,
                tradeInputBBaseline.getOrDefault(bodyId, count(costB.getItem())) - count(costB.getItem()));
        boolean resources = offer.satisfiedBy(paymentA, paymentB);
        String villagerId = tradeVillagers.getOrDefault(bodyId, "");
        Entity villager = entity(villagerId, body);
        boolean valid = menu.stillValid(body) && villager != null && villager.isAlive()
                && menu.containerId == tradeMenuIds.getOrDefault(bodyId, -1);
        return new DailyActionSnapshot.TradeFact(valid, villagerId,
                offerIndex, offer.isOutOfStock(), offer.getUses(), id(costA.getItem()),
                costB.isEmpty() ? "" : id(costB.getItem()),
                id(offer.getResult().getItem()), resources,
                inventoryCapacity(body, offer.getResult()) >= offer.getResult().getCount(), resources,
                consumedA, consumedB, output, costA.getCount(),
                costB.isEmpty() ? 0 : costB.getCount(), offer.getResult().getCount());
    }
    private DailyActionSnapshot.EnchantFact enchantFact(CompanionPlayer body) {
        if (!(body.containerMenu instanceof EnchantmentMenu menu)) return null;
        UUID id = body.getUUID();
        int option = Math.max(0, Math.min(enchantOptions.getOrDefault(id, 0), menu.costs.length - 1));
        ItemStack target = menu.getSlot(0).getItem();
        DailyActionRequest.Position station = enchantStations.get(id);
        boolean valid = menu.stillValid(body) && station != null
                && menu.containerId == enchantMenuIds.getOrDefault(id, -1)
                && body.serverLevel().getBlockState(block(station)).is(Blocks.ENCHANTING_TABLE);
        int xpBase = enchantXpBaseline.getOrDefault(id, body.experienceLevel);
        int lapisBase = enchantLapisBaseline.getOrDefault(id, totalLapis(body));
        return new DailyActionSnapshot.EnchantFact(valid, station,
                target.isEmpty() ? "" : id(target.getItem()), option,
                menu.costs[option], totalLapis(body), !target.isEmpty()
                && menu.getGoldCount() >= option + 1, Integer.toString(stackDigest(target)),
                Math.max(0, xpBase - body.experienceLevel),
                Math.max(0, lapisBase - totalLapis(body)));
    }
    private DailyActionSnapshot.BrewFact brewFact(CompanionPlayer body) {
        if (!(body.containerMenu instanceof BrewingStandMenu menu)) return null;
        UUID id = body.getUUID();
        int requested = brewBottleCounts.getOrDefault(id, 1);
        Map<Integer, String> bottles = new HashMap<>();
        int loaded = 0;
        for (int slot = 0; slot < requested; slot++) {
            ItemStack bottle = menu.getSlot(slot).getItem();
            if (!bottle.isEmpty()) {
                loaded++;
                bottles.put(slot, Integer.toString(stackDigest(bottle)));
            }
        }
        int ticks = menu.getBrewingTicks();
        if (ticks > 0) brewStarted.put(id, true);
        if (brewStarted.getOrDefault(id, false) && ticks == 0 && loaded == requested
                && !bottles.equals(brewInitialDigests.getOrDefault(id, Map.of()))) {
            brewCompletedDigests.putIfAbsent(id, Map.copyOf(bottles));
        }
        DailyActionRequest.Position station = brewStations.get(id);
        boolean valid = menu.stillValid(body) && station != null
                && menu.containerId == brewMenuIds.getOrDefault(id, -1)
                && body.serverLevel().getBlockState(block(station)).is(Blocks.BREWING_STAND);
        Map<Integer, String> completed = brewCompletedDigests.getOrDefault(id, Map.of());
        Map<Integer, String> observed = brewStarted.getOrDefault(id, false) && ticks == 0
                && !completed.isEmpty() ? completed : bottles;
        int taken = brewResultsTaken.getOrDefault(id, 0);
        Item ingredient = item(requestedItem(body));
        boolean materials = loaded + count(Items.POTION) >= requested
                && (!menu.getSlot(3).getItem().isEmpty() || ingredient != null && count(ingredient) > 0);
        boolean fuel = menu.getFuel() > 0 || count(Items.BLAZE_POWDER) > 0
                || brewStarted.getOrDefault(id, false);
        ItemStack ingredientStack = menu.getSlot(3).getItem();
        String observedIngredient = ingredientStack.isEmpty()
                ? requestedItem(body) : id(ingredientStack.getItem());
        return new DailyActionSnapshot.BrewFact(valid, station, observedIngredient, requested,
                menu.getFuel(), materials, fuel,
                loaded >= requested && !menu.getSlot(3).getItem().isEmpty(), ticks,
                observed, taken, taken);
    }
    private DailyActionSnapshot.MenuFact menuFact(CompanionPlayer body) { if(body.containerMenu==body.inventoryMenu)return null; String type=body.containerMenu.getClass().getSimpleName().toUpperCase(); int offer=selectedTradeOffer.getOrDefault(body.getUUID(),0),uses=0,cost=0,lapis=0,brew=0,result=0; boolean enchantChanged=false,brewReady=false; String output=""; Map<String,String>d=new HashMap<>(); if(body.containerMenu instanceof MerchantMenu m&&!m.getOffers().isEmpty()){MerchantOffer o=m.getOffers().get(Math.min(offer,m.getOffers().size()-1));uses=o.getUses();output=id(o.getResult().getItem());d.put("inputsReady",Boolean.toString(o.satisfiedBy(m.getSlot(0).getItem(),m.getSlot(1).getItem())));d.put("tradeVerified",Boolean.toString(uses>tradeBaseline.getOrDefault(body.getUUID(),uses)));d.put("resourcesAvailable",Boolean.toString(o.satisfiedBy(m.getSlot(0).getItem(),m.getSlot(1).getItem())));d.put("inventorySpace",Boolean.toString(firstEmptyInventoryMenuSlot(body)>=0));} if(body.containerMenu instanceof EnchantmentMenu e){cost=e.costs[0];lapis=e.getGoldCount();d.put("inputsReady",Boolean.toString(!e.getSlot(0).getItem().isEmpty()));int digest=stackDigest(e.getSlot(0).getItem());d.put("itemDigest",Integer.toString(digest));enchantChanged=enchantXpBaseline.containsKey(body.getUUID())&&body.experienceLevel<enchantXpBaseline.get(body.getUUID())&&digest!=enchantItemDigest.getOrDefault(body.getUUID(),digest); } if(body.containerMenu instanceof BrewingStandMenu b){brew=b.getBrewingTicks();if(brew>0)brewStarted.put(body.getUUID(),true);brewReady=brewStarted.getOrDefault(body.getUUID(),false)&&brew==0;d.put("materialsAvailable",Boolean.toString(!b.getSlot(3).getItem().isEmpty()));d.put("fuelAvailable",Boolean.toString(b.getFuel()>0));d.put("brewVerified",Boolean.toString(brewLoaded.getOrDefault(body.getUUID(),false)&&brewReady));} return new DailyActionSnapshot.MenuFact(true,type,offer,false,"","",output,uses,64,cost,lapis,enchantChanged,brew,brewReady,result,d); }

    private DailyActionCommand.CommandResult success(String code){return new DailyActionCommand.CommandResult(true,false,code,Map.of());} private DailyActionCommand.CommandResult reject(String code){return DailyActionCommand.CommandResult.rejected(code);} private DailyActionCommand.CommandResult uncertain(String code){return DailyActionCommand.CommandResult.uncertain(code);}
    private Item item(String id){if(id==null||id.isBlank())return null; ResourceLocation key=ResourceLocation.tryParse(id);return key==null||!BuiltInRegistries.ITEM.containsKey(key)?null:BuiltInRegistries.ITEM.get(key);} private static String id(Object value){if(value instanceof Item i)return BuiltInRegistries.ITEM.getKey(i).toString(); if(value instanceof net.minecraft.world.entity.EntityType<?> t)return BuiltInRegistries.ENTITY_TYPE.getKey(t).toString(); if(value instanceof Block b)return BuiltInRegistries.BLOCK.getKey(b).toString(); return "";}
    private int count(Item i){int n=0;for(int s=0;s<36;s++)if(activeBody.getInventory().getItem(s).is(i))n+=activeBody.getInventory().getItem(s).getCount();return n;} private int findItemHotbar(Item i){for(int s=0;s<9;s++)if(activeBody.getInventory().getItem(s).is(i))return s;return -1;} private int findItemMenuSlot(Item i){for(int s=0;s<36;s++)if(activeBody.getInventory().getItem(s).is(i))return inventoryMenuSlot(s);return -1;} private int inventoryMenuSlot(int s){return s<9?36+s:s;} private int inventorySlotFromMenu(int s){return s>=36&&s<45?s-36:s;} private int equipmentMenuSlot(String d){return switch(d){case "HEAD"->5;case "CHEST"->6;case "LEGS"->7;case "FEET"->8;case "OFF_HAND"->45;default->-1;};} private int firstEmptyInventoryMenuSlot(CompanionPlayer b){for(int s=0;s<36;s++)if(b.getInventory().getItem(s).isEmpty())return inventoryMenuSlot(s);return -1;}
    private DailyActionCommand.CommandResult moveMenu(CompanionPlayer b,int source,int target,String code){if(source<0||target<0||!b.inventoryMenu.getCarried().isEmpty())return reject("INVENTORY_MENU_INVALID");b.inventoryMenu.clicked(source,0,ClickType.PICKUP,b);b.inventoryMenu.clicked(target,0,ClickType.PICKUP,b);b.inventoryMenu.clicked(source,0,ClickType.PICKUP,b);actionGateway.markVanillaMenuAction(b);return b.inventoryMenu.getCarried().isEmpty()?success(code):uncertain("EQUIPMENT_NOT_VERIFIED");}
    private boolean moveCount(CompanionPlayer b,int source,int target,int quantity){if(source<0||target<0||quantity<1||!b.containerMenu.getCarried().isEmpty()||b.containerMenu.getSlot(source).getItem().getCount()<quantity||!b.containerMenu.getSlot(target).getItem().isEmpty())return false;b.containerMenu.clicked(source,0,ClickType.PICKUP,b);for(int i=0;i<quantity;i++)b.containerMenu.clicked(target,1,ClickType.PICKUP,b);b.containerMenu.clicked(source,0,ClickType.PICKUP,b);return b.containerMenu.getCarried().isEmpty()&&b.containerMenu.getSlot(target).getItem().getCount()==quantity;}
    private String autoEquipment(Item i){return i instanceof ArmorItem armor ? switch (armor.getEquipmentSlot()) { case HEAD -> "HEAD"; case CHEST -> "CHEST"; case LEGS -> "LEGS"; case FEET -> "FEET"; default -> "MAIN_HAND"; } : i == Items.ELYTRA ? "CHEST" : i == Items.SHIELD ? "OFF_HAND" : "MAIN_HAND";}
    private Entity entity(String raw,CompanionPlayer b){try{UUID u=UUID.fromString(raw);return b.serverLevel().getEntity(u);}catch(Exception ignored){return null;}} private Animal animal(String raw,CompanionPlayer b){Entity e=entity(raw,b);return e instanceof Animal a?a:null;} private DailyActionRequest.Position pos(Entity e){return toPos(e.blockPosition(),dimension(activeBody));} private DailyActionRequest.Position toPos(BlockPos p,String d){return new DailyActionRequest.Position(d,p.getX(),p.getY(),p.getZ());} private BlockPos block(DailyActionRequest.Position p){return new BlockPos(p.x(),p.y(),p.z());} private Vec3 vec(DailyActionRequest.Position p){return new Vec3(p.x()+.5,p.y(),p.z()+.5);} private Direction direction(String d){try{return Direction.valueOf(d.toUpperCase());}catch(Exception e){return Direction.UP;}} private String dimension(CompanionPlayer b){return b.serverLevel().dimension().location().toString();}
    private boolean biting(){if(activeBody==null||activeBody.fishing==null)return false;FishingHook hook=activeBody.fishing;UUID id=activeBody.getUUID();int age=server.getTickCount()-fishingCastTicks.getOrDefault(id,server.getTickCount());double previousY=fishingHookY.getOrDefault(id,hook.getY());fishingHookY.put(id,hook.getY());double downward=previousY-hook.getY();Vec3 motion=hook.getDeltaMovement();return hook.getHookedIn()!=null||(age>=20&&hook.isOpenWaterFishing()&&(downward>.08D||motion.y<-.08D));}
    private int fishCount(CompanionPlayer b){int n=0;for(int s=0;s<36;s++){ItemStack x=b.getInventory().getItem(s);if(x.is(Items.COD)||x.is(Items.SALMON)||x.is(Items.TROPICAL_FISH)||x.is(Items.PUFFERFISH))n+=x.getCount();}return n;} private int stackDigest(ItemStack x){return x.isEmpty()?0:31*id(x.getItem()).hashCode()+x.getComponents().hashCode()+x.getCount();}
    private int babyCount(CompanionPlayer b){return b.serverLevel().getEntitiesOfClass(Animal.class,b.getBoundingBox().inflate(5),Animal::isBaby).size();}
    private String requestedItem(CompanionPlayer b){return requestedItems.getOrDefault(b.getUUID(),"");}

    private BlockState targetBlockState(DailyActionRequest request) {
        if (request == null || !"BEST_TOOL".equals(request.action())
                || request.targetBlockId().isBlank()) return null;
        ResourceLocation key = ResourceLocation.tryParse(request.targetBlockId());
        return key == null || !BuiltInRegistries.BLOCK.containsKey(key)
                ? null : BuiltInRegistries.BLOCK.get(key).defaultBlockState();
    }

    private static String weaponType(Item item) {
        if (item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem) return "RANGED";
        if (item instanceof SwordItem || item instanceof AxeItem) return "MELEE";
        return "";
    }

    private static double weaponScore(Item item) {
        if (item instanceof TridentItem) return 9.0D;
        if (item instanceof AxeItem) return 8.0D;
        if (item instanceof SwordItem) return 7.0D;
        if (item instanceof CrossbowItem) return 6.5D;
        if (item instanceof BowItem) return 6.0D;
        return 0.0D;
    }

    private String breedingFood(Animal animal, CompanionPlayer body) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.isEmpty() && animal.isFood(stack)) return id(stack.getItem());
        }
        return "";
    }

    private void addExplicitVehicleCandidate(CompanionPlayer body, DailyActionRequest request,
                                             String dimension,
                                             List<DailyActionSnapshot.VehicleCandidate> candidates) {
        if (request == null || request.targetId().isBlank()
                || candidates.stream().anyMatch(candidate -> candidate.id().toString().equals(request.targetId()))) return;
        Entity target = entity(request.targetId(), body);
        if ((target instanceof Boat || target instanceof AbstractMinecart) && target.isAlive()) {
            candidates.add(new DailyActionSnapshot.VehicleCandidate(target.getUUID(), id(target.getType()),
                    toPos(target.blockPosition(), dimension), true));
        }
    }

    private void addExplicitAnimalCandidates(CompanionPlayer body, DailyActionRequest request,
                                             String dimension,
                                             List<DailyActionSnapshot.AnimalCandidate> candidates) {
        if (request == null) return;
        for (String targetId : List.of(request.targetId(), request.secondaryTargetId())) {
            if (targetId.isBlank()
                    || candidates.stream().anyMatch(candidate -> candidate.id().toString().equals(targetId))) continue;
            Entity target = entity(targetId, body);
            if (!(target instanceof Animal animal) || !animal.isAlive()) continue;
            String food = breedingFood(animal, body);
            candidates.add(new DailyActionSnapshot.AnimalCandidate(animal.getUUID(), id(animal.getType()),
                    toPos(animal.blockPosition(), dimension), food, !food.isBlank(), !animal.isBaby(),
                    animal.isInLove(), true));
        }
    }

    private void addExplicitEntityCandidate(CompanionPlayer body, String targetId, String dimension,
                                            List<DailyActionSnapshot.EntityCandidate> candidates) {
        if (targetId == null || targetId.isBlank()
                || candidates.stream().anyMatch(candidate -> candidate.id().toString().equals(targetId))) return;
        Entity target = entity(targetId, body);
        if (target instanceof LivingEntity living && living.isAlive()) {
            candidates.add(new DailyActionSnapshot.EntityCandidate(living.getUUID(), id(living.getType()),
                    toPos(living.blockPosition(), dimension), true, body.hasLineOfSight(living)));
        }
    }

    private static void addExplicitBedCandidate(
            CompanionPlayer body,
            DailyActionRequest request,
            String dimension,
            List<DailyActionSnapshot.BedCandidate> beds) {
        if (request == null || request.kind() != DailyActionKind.SLEEP_AT_BED
                || request.target() == null || !dimension.equals(request.target().dimension())
                || beds.stream().anyMatch(candidate -> request.target().equals(candidate.position()))) {
            return;
        }
        BlockPos position = new BlockPos(request.target().x(), request.target().y(), request.target().z());
        if (!body.serverLevel().hasChunkAt(position)) return;
        BlockState state = body.serverLevel().getBlockState(position);
        if (!(state.getBlock() instanceof BedBlock)) return;
        boolean occupied = state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
        boolean bedWorks = body.serverLevel().dimensionType().bedWorks();
        beds.add(new DailyActionSnapshot.BedCandidate(request.target(), occupied,
                bedWorks && !occupied, !bedWorks ? "BED_WRONG_DIMENSION"
                : occupied ? "BED_OCCUPIED" : ""));
    }

    private static String cropSeed(BlockState state) {
        if (state.is(Blocks.WHEAT)) return "minecraft:wheat_seeds";
        if (state.is(Blocks.BEETROOTS)) return "minecraft:beetroot_seeds";
        if (state.is(Blocks.CARROTS)) return "minecraft:carrot";
        if (state.is(Blocks.POTATOES)) return "minecraft:potato";
        return "";
    }

    private static String cropHarvest(BlockState state) {
        if (state.is(Blocks.WHEAT)) return "minecraft:wheat";
        if (state.is(Blocks.BEETROOTS)) return "minecraft:beetroot";
        if (state.is(Blocks.CARROTS)) return "minecraft:carrot";
        if (state.is(Blocks.POTATOES)) return "minecraft:potato";
        return "";
    }

    private DailyActionCommand.CommandResult ensureMainHand(Item item) {
        if (item == null) return reject("ITEM_NOT_FOUND");
        if (activeBody.getMainHandItem().is(item)) return success("ITEM_ALREADY_SELECTED");
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = activeBody.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            return equip(new DailyActionCommand.Equip("EQUIP", id(item), slot,
                    "MAIN_HAND", Integer.toString(stackDigest(stack))));
        }
        return reject("ITEM_NOT_FOUND");
    }

    private static int findOpenMenuItemSlot(CompanionPlayer body, Item item) {
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.getSlot(index);
            if (slot.container == body.getInventory() && slot.getItem().is(item)) return index;
        }
        return -1;
    }

    private static int inventoryCapacity(CompanionPlayer body, ItemStack incoming) {
        int capacity = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = body.getInventory().getItem(slot);
            if (current.isEmpty()) capacity += incoming.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(current, incoming)) {
                capacity += Math.max(0, current.getMaxStackSize() - current.getCount());
            }
        }
        return capacity;
    }

    private static int totalLapis(CompanionPlayer body) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) if (body.getInventory().getItem(slot).is(Items.LAPIS_LAZULI)) {
            total += body.getInventory().getItem(slot).getCount();
        }
        if (body.containerMenu instanceof EnchantmentMenu menu) total += menu.getGoldCount();
        return total;
    }

    private static int inventoryItemCount(CompanionPlayer body) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) total += body.getInventory().getItem(slot).getCount();
        return total;
    }

    private static boolean isFish(ItemStack stack) {
        return stack.is(Items.COD) || stack.is(Items.SALMON)
                || stack.is(Items.TROPICAL_FISH) || stack.is(Items.PUFFERFISH);
    }

    private int countInventoryDigest(CompanionPlayer body, String digest) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.isEmpty() && digest.equals(Integer.toString(stackDigest(stack)))) total += stack.getCount();
        }
        return total;
    }

    private String blockAndFluidId(BlockPos position) {
        BlockState state = activeBody.serverLevel().getBlockState(position);
        return state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                ? "minecraft:water" : id(state.getBlock());
    }

    private static void returnMenuInputs(CompanionPlayer body) {
        returnCarried(body);
        int[] slots = body.containerMenu instanceof MerchantMenu ? new int[]{0, 1}
                : body.containerMenu instanceof EnchantmentMenu ? new int[]{0, 1}
                : body.containerMenu instanceof BrewingStandMenu ? new int[]{0, 1, 2, 3, 4}
                : new int[0];
        for (int slot : slots) {
            if (slot < body.containerMenu.slots.size() && !body.containerMenu.getSlot(slot).getItem().isEmpty()) {
                body.containerMenu.clicked(slot, 0, ClickType.QUICK_MOVE, body);
            }
        }
        returnCarried(body);
    }

    private static void returnCarried(CompanionPlayer body) {
        if (body.containerMenu.getCarried().isEmpty()) return;
        for (int index = 0; index < body.containerMenu.slots.size(); index++) {
            Slot slot = body.containerMenu.getSlot(index);
            if (slot.container == body.getInventory() && slot.getItem().isEmpty()) {
                body.containerMenu.clicked(index, 0, ClickType.PICKUP, body);
                break;
            }
        }
        if (!body.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("MENU_CURSOR_NOT_RECOVERED");
    }

    private int countId(String itemId) {
        Item value = item(itemId);
        return value == null ? 0 : count(value);
    }

    private int babyCount(CompanionPlayer body, net.minecraft.world.entity.EntityType<?> type) {
        return body.serverLevel().getEntitiesOfClass(Animal.class, body.getBoundingBox().inflate(12.0D),
                value -> value.isBaby() && value.getType() == type).size();
    }

    private static boolean safeGlideTerrain(CompanionPlayer body, DailyActionRequest.Position target,
                                            boolean landing) {
        if (target == null) return false;
        String dimension = body.serverLevel().dimension().location().toString();
        DailyActionRequest.Position start = new DailyActionRequest.Position(dimension,
                body.blockPosition().getX(), body.blockPosition().getY(), body.blockPosition().getZ());
        Vec3 targetCenter = Vec3.atCenterOf(new BlockPos(target.x(), target.y(), target.z()));
        List<DailyActionRequest.Position> samples = landing || body.position().distanceToSqr(targetCenter) <= 9.0D
                ? List.of() : GlidePathSampler.between(start, target, 64);
        for (DailyActionRequest.Position sample : samples) {
            BlockPos pathBlock = new BlockPos(sample.x(), sample.y(), sample.z());
            if (!body.serverLevel().hasChunkAt(pathBlock)) return false;
            for (BlockPos occupied : List.of(pathBlock, pathBlock.above())) {
                BlockState state = body.serverLevel().getBlockState(occupied);
                if (!state.getCollisionShape(body.serverLevel(), occupied).isEmpty()
                        || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                        || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                        || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                        || state.is(Blocks.POWDER_SNOW)) return false;
            }
        }
        BlockPos position = new BlockPos(target.x(), target.y(), target.z());
        if (!body.serverLevel().hasChunkAt(position)) return false;
        for (BlockPos check : List.of(position, position.below())) {
            BlockState state = body.serverLevel().getBlockState(check);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                    || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.POWDER_SNOW)) return false;
        }
        BlockState targetState = body.serverLevel().getBlockState(position);
        BlockState support = body.serverLevel().getBlockState(position.below());
        return targetState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                || !support.getCollisionShape(body.serverLevel(), position.below()).isEmpty();
    }

    private DailyActionRequest.Position selectedTarget(UUID companionId) {
        String session = sessions.get(companionId);
        if (session == null) return null;
        try { return engine.inspect(session).selectedTarget(); }
        catch (IllegalArgumentException missing) { return null; }
    }

    private com.mccompanion.core.body.daily.DailyActionPhase currentPhase(UUID companionId) {
        String session = sessions.get(companionId);
        if (session == null) return null;
        try { return engine.inspect(session).phase(); }
        catch (IllegalArgumentException missing) { return null; }
    }

    private record CropRun(DailyActionRequest.Position target, String cropId, String seedItem,
                           String harvestItem, int harvestBefore, int seedBefore) { }

    private void removeNavigator(UUID id) {
        DailyNavigator navigator = navigators.remove(id);
        if (navigator != null) navigator.cancel();
        navigationPorts.remove(id);
        navigationKeys.remove(id);
    }

    private void clearFacts(UUID id) {
        bucketBaselines.remove(id); bucketCommands.remove(id); bucketBlockBaselines.remove(id);
        cropProgress.remove(id); cropRuns.remove(id); cropSeedBeforeReplant.remove(id);
        breedProgress.remove(id); breedTargets.remove(id); breedFoodBaseline.remove(id);
        breedFoodItems.remove(id); babyBaselines.remove(id);
        tradeBaseline.remove(id); tradeOutputBaseline.remove(id); selectedTradeOffer.remove(id);
        tradeVillagers.remove(id); tradeInputABaseline.remove(id); tradeInputBBaseline.remove(id);
        tradeMenuIds.remove(id);
        enchantXpBaseline.remove(id); enchantItemDigest.remove(id); enchantLapisBaseline.remove(id);
        enchantStations.remove(id); enchantOptions.remove(id);
        enchantMenuIds.remove(id);
        fishBaseline.remove(id); fishingHookIds.remove(id); fishingRodDamageBaseline.remove(id);
        fishingReelVerified.remove(id);
        fishingCastTicks.remove(id);
        fishingHookY.remove(id);
        brewLoaded.remove(id); brewStarted.remove(id); brewResultBaseline.remove(id);
        brewStations.remove(id); brewBottleCounts.remove(id); brewInitialDigests.remove(id);
        brewCompletedDigests.remove(id); brewResultsTaken.remove(id);
        brewMenuIds.remove(id);
        glideTargets.remove(id); glideBestDistance.remove(id); requestedItems.remove(id);
        vehicleCommands.remove(id); vehicleProgressPosition.remove(id); vehicleProgressTick.remove(id);
        bodies.remove(id);
    }

    private final class BodyNavigationPort implements NavigationPort {
        private CompanionPlayer body;

        private BodyNavigationPort(CompanionPlayer body) { this.body = body; }

        @Override public Vec currentPosition() {
            Vec3 position = body.position();
            return new Vec(position.x, position.y, position.z);
        }

        @Override public String worldKey() { return dimension(body); }

        @Override public GridPathPlanner.Plan plan(NavPoint from, NavPoint target) {
            return navigation.plan(body, new Vec3(target.x() + .5D, target.y(), target.z() + .5D));
        }

        @Override public boolean traversable(NavPoint from, NavPoint to) {
            return navigation.remainsTraversable(body, from.plannerPoint(), to.plannerPoint());
        }

        @Override public boolean openDoor(NavPoint point) {
            return !navigation.requiresPassageOpening(body, point.plannerPoint())
                    || navigation.openDoorIfNeeded(body, point.plannerPoint());
        }

        @Override public void applyMove(Vec direction, boolean jump) {
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
            actionGateway.applyMoveInput(body, yaw, jump || body.horizontalCollision);
        }

        @Override public void stop() { actionGateway.stopInput(body); }
    }
}
