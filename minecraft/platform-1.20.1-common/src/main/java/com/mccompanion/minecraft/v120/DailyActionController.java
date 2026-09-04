package com.mccompanion.minecraft.v120;

import com.mccompanion.core.body.daily.DailyActionCommand;
import com.mccompanion.core.body.daily.DailyActionEngine;
import com.mccompanion.core.body.daily.DailyActionKind;
import com.mccompanion.core.body.daily.DailyActionRequest;
import com.mccompanion.core.body.daily.DailyActionSnapshot;
import com.mccompanion.core.body.daily.DailyActionSnapshot.AnimalCandidate;
import com.mccompanion.core.body.daily.DailyActionSnapshot.BedCandidate;
import com.mccompanion.core.body.daily.DailyActionSnapshot.CropCandidate;
import com.mccompanion.core.body.daily.DailyActionSnapshot.EntityCandidate;
import com.mccompanion.core.body.daily.DailyActionSnapshot.ItemCandidate;
import com.mccompanion.core.body.daily.DailyActionSnapshot.ItemFact;
import com.mccompanion.core.body.daily.DailyActionSnapshot.VehicleCandidate;
import com.mccompanion.core.body.daily.GlidePathSampler;
import com.mccompanion.core.body.daily.navigation.NavPoint;
import com.mccompanion.core.body.daily.navigation.DailyNavigator;
import com.mccompanion.core.body.daily.navigation.NavigationPort;
import com.mccompanion.core.body.daily.navigation.Vec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The 1.20.1 boundary for the shared daily-action state machine.  The state machine owns
 * phase selection and durable progress; this class only translates facts and commands to
 * ServerPlayer/vanilla menu operations.
 */
final class DailyActionController {
    private final MinecraftServer server;
    private final PlayerActionGateway gateway;
    private final DailyActionEngine engine = new DailyActionEngine();
    private final Map<UUID, String> sessions = new HashMap<>();
    private final Map<UUID, Facts> facts = new HashMap<>();
    private final SurvivalNavigationAdapter navigation = new SurvivalNavigationAdapter();
    private final Map<UUID, BodyNavigationPort> navigationPorts = new HashMap<>();
    private final Map<UUID, DailyNavigator> navigators = new HashMap<>();
    private final Map<UUID, String> navigationKeys = new HashMap<>();

    DailyActionController(MinecraftServer server, PlayerActionGateway gateway) {
        this.server = server;
        this.gateway = gateway;
    }

    private static BlockState targetBlockState(DailyActionRequest request) {
        if(request==null||!request.action().equals("BEST_TOOL")||request.targetBlockId().isBlank())return null;var key=net.minecraft.resources.ResourceLocation.tryParse(request.targetBlockId());return key==null||!BuiltInRegistries.BLOCK.containsKey(key)?null:BuiltInRegistries.BLOCK.get(key).defaultBlockState();
    }
    private static String stackDigest(ItemStack stack){return stack.isEmpty()?"":Integer.toString(java.util.Objects.hash(BuiltInRegistries.ITEM.getKey(stack.getItem()),stack.getCount(),stack.getDamageValue(),stack.hasTag()?stack.getTag():null));}
    private static double weaponScore(Item item){if(item instanceof TridentItem)return 9;if(item instanceof AxeItem)return 8;if(item instanceof SwordItem)return 7;if(item instanceof CrossbowItem)return 6.5;if(item instanceof BowItem)return 6;return 0;}
    private static String autoEquipment(Item item){if(item instanceof ArmorItem armor)return switch(armor.getEquipmentSlot()){case HEAD->"HEAD";case CHEST->"CHEST";case LEGS->"LEGS";case FEET->"FEET";default->"MAIN_HAND";};return item==Items.ELYTRA?"CHEST":item==Items.SHIELD?"OFF_HAND":"MAIN_HAND";}
    private static String breedingFood(Animal animal,CompanionPlayer body){for(int slot=0;slot<36;slot++){ItemStack stack=body.getInventory().getItem(slot);if(!stack.isEmpty()&&animal.isFood(stack))return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();}return "";}
    private static String harvestFor(Block block){if(block==Blocks.WHEAT)return "minecraft:wheat";if(block==Blocks.BEETROOTS)return "minecraft:beetroot";if(block==Blocks.CARROTS)return "minecraft:carrot";if(block==Blocks.POTATOES)return "minecraft:potato";return "";}
    private static String blockAndFluidId(ServerLevel level,BlockPos position){BlockState state=level.getBlockState(position);return state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)?"minecraft:water":BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();}
    private static int countItem(CompanionPlayer body,String itemId){Item item=Facts.resolve(itemId);return item==null?0:body.getInventory().countItem(item);}
    private static int babyCount(CompanionPlayer body,net.minecraft.world.entity.EntityType<?> type){return body.serverLevel().getEntitiesOfClass(Animal.class,body.getBoundingBox().inflate(12),value->value.isBaby()&&value.getType()==type).size();}
    private static boolean safeGlideTerrain(CompanionPlayer body,DailyActionRequest.Position target,boolean landing){if(target==null)return false;String dimension=body.serverLevel().dimension().location().toString();DailyActionRequest.Position start=new DailyActionRequest.Position(dimension,body.blockPosition().getX(),body.blockPosition().getY(),body.blockPosition().getZ());Vec3 targetCenter=Vec3.atCenterOf(new BlockPos(target.x(),target.y(),target.z()));List<DailyActionRequest.Position> samples=landing||body.position().distanceToSqr(targetCenter)<=9?List.of():GlidePathSampler.between(start,target,64);for(DailyActionRequest.Position sample:samples){BlockPos path=new BlockPos(sample.x(),sample.y(),sample.z());if(!body.serverLevel().hasChunkAt(path))return false;for(BlockPos occupied:List.of(path,path.above())){BlockState state=body.serverLevel().getBlockState(occupied);if(!state.getCollisionShape(body.serverLevel(),occupied).isEmpty()||state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)||state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)||state.is(Blocks.CACTUS)||state.is(Blocks.MAGMA_BLOCK)||state.is(Blocks.POWDER_SNOW))return false;}}BlockPos p=new BlockPos(target.x(),target.y(),target.z());if(!body.serverLevel().hasChunkAt(p))return false;for(BlockPos check:List.of(p,p.below())){BlockState state=body.serverLevel().getBlockState(check);if(state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)||state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)||state.is(Blocks.CACTUS)||state.is(Blocks.MAGMA_BLOCK)||state.is(Blocks.POWDER_SNOW))return false;}return body.serverLevel().getBlockState(p).getFluidState().is(net.minecraft.tags.FluidTags.WATER)||!body.serverLevel().getBlockState(p.below()).getCollisionShape(body.serverLevel(),p.below()).isEmpty();}
    private static Entity entity(ServerLevel level,String id){try{return level.getEntity(UUID.fromString(id));}catch(Exception ignored){return null;}}
    private static UUID parseUuid(String value){try{return UUID.fromString(value);}catch(Exception ignored){return null;}}
    private static Direction directionValue(String value){try{return Direction.valueOf(value==null?"UP":value.toUpperCase(Locale.ROOT));}catch(Exception ignored){return Direction.UP;}}
    private DailyActionRequest.Position selectedTarget(UUID companionId){String session=sessions.get(companionId);if(session==null)return null;try{return engine.inspect(session).selectedTarget();}catch(IllegalArgumentException ignored){return null;}}
    private com.mccompanion.core.body.daily.DailyActionPhase currentPhase(UUID companionId){String session=sessions.get(companionId);if(session==null)return null;try{return engine.inspect(session).phase();}catch(IllegalArgumentException ignored){return null;}}

    private static int findOpenMenuItemSlot(CompanionPlayer body,Item item){if(item==null)return-1;for(int index=0;index<body.containerMenu.slots.size();index++){Slot slot=body.containerMenu.getSlot(index);if(slot.container==body.getInventory()&&slot.getItem().is(item))return index;}return-1;}
    private static boolean moveCount(CompanionPlayer body,int source,int target,int quantity){if(source<0||target<0||quantity<1||!body.containerMenu.getCarried().isEmpty()||body.containerMenu.getSlot(source).getItem().getCount()<quantity||!body.containerMenu.getSlot(target).getItem().isEmpty())return false;body.containerMenu.clicked(source,0,ClickType.PICKUP,body);for(int i=0;i<quantity;i++)body.containerMenu.clicked(target,1,ClickType.PICKUP,body);body.containerMenu.clicked(source,0,ClickType.PICKUP,body);return body.containerMenu.getCarried().isEmpty()&&body.containerMenu.getSlot(target).getItem().getCount()==quantity;}
    private static int totalLapis(CompanionPlayer body){int total=body.getInventory().countItem(Items.LAPIS_LAZULI);if(body.containerMenu instanceof EnchantmentMenu menu)total+=menu.getGoldCount();return total;}
    private static int inventoryCapacity(CompanionPlayer body,ItemStack incoming){int capacity=0;for(int slot=0;slot<36;slot++){ItemStack current=body.getInventory().getItem(slot);if(current.isEmpty())capacity+=incoming.getMaxStackSize();else if(ItemStack.isSameItemSameTags(current,incoming))capacity+=Math.max(0,current.getMaxStackSize()-current.getCount());}return capacity;}
    private static int countInventoryDigest(CompanionPlayer body,String digest){int total=0;for(int slot=0;slot<36;slot++){ItemStack stack=body.getInventory().getItem(slot);if(!stack.isEmpty()&&stackDigest(stack).equals(digest))total+=stack.getCount();}return total;}

    static boolean supports(String capability) {
        return switch (capability == null ? "" : capability) {
            case "EquipItem", "SleepAtBed", "UseWaterBucket", "UseVehicle", "Fish", "FarmCrop",
                    "BreedAnimals", "TradeWithVillager", "EnchantItem", "BrewPotion", "GlideWithElytra" -> true;
            default -> false;
        };
    }

    void validate(CompanionPlayer body, SkillParameters parameters) {
        request(parameters, body);
        if (!engine.canStart() && !sessions.containsKey(body.getUUID())) {
            throw new IllegalArgumentException("DAILY_ACTION_SESSION_LIMIT");
        }
    }

    void start(UUID companionId, String behaviorId, CompanionPlayer body, SkillParameters p) {
        String session = sessionId(companionId);
        String old = sessions.get(companionId);
        if(old!=null){try{engine.cancel(old,"SUPERSEDED",new Adapter(body));}finally{engine.remove(old);}}
        removeNavigator(companionId);
        DailyActionRequest request = request(p, body);
        engine.start(session, request, server.getTickCount());
        sessions.put(companionId, session);
        facts.put(companionId, new Facts(p, body, request));
    }

    DailyActionEngine.Observation resume(UUID companionId, CompanionPlayer body) {
        String session = sessions.get(companionId);
        DailyActionEngine.Observation observation = session == null ? null
                : engine.resume(session, server.getTickCount()).observation();
        DailyNavigator navigator = navigators.get(companionId);
        if (navigator != null) navigator.resume(server.getTickCount());
        return observation;
    }

    DailyActionEngine.Observation pause(UUID companionId, CompanionPlayer body, String reason) {
        String session = sessions.get(companionId);
        return session == null ? null : engine.pause(session, reason, new Adapter(body)).observation();
    }

    DailyActionEngine.Observation cancel(UUID companionId, CompanionPlayer body, String reason) {
        String session = sessions.remove(companionId);
        Facts activeFacts = facts.remove(companionId);
        CompanionPlayer cleanupBody = body != null ? body : activeFacts == null ? null : activeFacts.body;
        DailyActionEngine.Observation observation = null;
        if (session != null) {
            try {
                DailyActionEngine.Session cancelled = cleanupBody == null
                        ? engine.cancel(session, reason)
                        : engine.cancel(session, reason, new Adapter(cleanupBody));
                observation = cancelled.observation();
            }
            finally { engine.remove(session); }
        }
        removeNavigator(companionId);
        return observation;
    }

    DailyActionEngine.TickResult tick(UUID companionId, CompanionPlayer body) {
        String session = sessions.get(companionId);
        if (session == null) throw new IllegalStateException("DAILY_ACTION_SESSION_MISSING");
        DailyActionEngine.TickResult result = engine.tick(session, snapshot(body), new Adapter(body));
        if (result.session().status() != DailyActionEngine.Status.RUNNING
                && result.session().status() != DailyActionEngine.Status.PAUSED) {
            sessions.remove(companionId);
            facts.remove(companionId);
            engine.remove(session);
            removeNavigator(companionId);
        }
        return result;
    }

    boolean active(UUID companionId) { return sessions.containsKey(companionId); }

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

    private static String sessionId(UUID companionId) { return "daily:" + companionId; }

    private static DailyActionRequest request(SkillParameters p, CompanionPlayer body) {
        DailyActionKind kind = switch (p.capability()) {
            case "EquipItem" -> DailyActionKind.EQUIP_ITEM;
            case "SleepAtBed" -> DailyActionKind.SLEEP_AT_BED;
            case "UseWaterBucket" -> DailyActionKind.USE_WATER_BUCKET;
            case "UseVehicle" -> DailyActionKind.USE_VEHICLE;
            case "Fish" -> DailyActionKind.FISH;
            case "FarmCrop" -> DailyActionKind.FARM_CROP;
            case "BreedAnimals" -> DailyActionKind.BREED_ANIMALS;
            case "TradeWithVillager" -> DailyActionKind.TRADE_WITH_VILLAGER;
            case "EnchantItem" -> DailyActionKind.ENCHANT_ITEM;
            case "BrewPotion" -> DailyActionKind.BREW_POTION;
            case "GlideWithElytra" -> DailyActionKind.GLIDE_WITH_ELYTRA;
            default -> throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
        };
        String dimension = p.hasBlockTarget() ? p.dimension()
                : body.serverLevel().dimension().location().toString();
        DailyActionRequest.Position target = p.hasBlockTarget()
                ? new DailyActionRequest.Position(dimension, p.x(), p.y(), p.z()) : null;
        String action = p.menuAction();
        // Runtime macro normalization uses `action`; older callers used menuAction.
        if (p.itemId().startsWith("ACTION:")) {
            action = p.itemId().substring("ACTION:".length());
        }
        int radius = kind == DailyActionKind.SLEEP_AT_BED ? Math.max(1, Math.min(16, p.quantity()))
                : p.button() == null ? 16 : Math.max(1, Math.min(16, p.button()));
        int maxTicks = p.durationTicks() == null || p.durationTicks() <= 0
                ? 20 * 60 * 5 : Math.min(20 * 60 * 30, p.durationTicks() + 20 * 20);
        return new DailyActionRequest(kind, p.itemId(), p.targetId(), p.secondaryTargetId(), dimension,
                target, action, p.hand(), p.face(), p.slot() == null ? -1 : p.slot(), p.quantity(),
                p.durationTicks() == null ? 0 : p.durationTicks(), maxTicks, radius);
    }

    private DailyActionSnapshot snapshot(CompanionPlayer body) {
        UUID companionId = body.getUUID();
        Facts factState = facts.get(companionId);
        if (factState != null) factState.body = body;
        ServerLevel level = body.serverLevel();
        String dimension = level.dimension().location().toString();
        DailyActionRequest.Position position = new DailyActionRequest.Position(dimension,
                body.blockPosition().getX(), body.blockPosition().getY(), body.blockPosition().getZ());
        Map<String, ItemFact> inventory = new HashMap<>();
        List<ItemCandidate> items = new ArrayList<>();
        BlockState toolTarget = targetBlockState(factState == null ? null : factState.request);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            inventory.merge(id, new ItemFact(id, stack.getCount(), stack.getDamageValue(),
                    stack.getMaxDamage(), Map.of("digest", stackDigest(stack))), (a, b) -> new ItemFact(id, a.count() + b.count(),
                    Math.min(a.damage(), b.damage()), Math.max(a.maxDamage(), b.maxDamage()), Map.of()));
            double destroySpeed = toolTarget == null ? 1.0D : stack.getDestroySpeed(toolTarget);
            boolean correctTool = toolTarget != null && stack.isCorrectToolForDrops(toolTarget);
            Map<String, String> traits = new HashMap<>();
            traits.put("equipmentSlot", autoEquipment(stack.getItem()));
            traits.put("componentDigest", stackDigest(stack));
            if (factState != null && !factState.request.targetBlockId().isBlank()) {
                traits.put("targetBlock", factState.request.targetBlockId());
            }
            String weapon = weaponType(stack);
            if (!weapon.isBlank()) traits.put("weaponType", weapon);
            items.add(new ItemCandidate(slot, id, stack.getCount(), stack.getDamageValue(), stack.getMaxDamage(),
                    correctTool, destroySpeed, weaponScore(stack.getItem()), traits));
        }
        Map<String, ItemFact> equipment = new HashMap<>();
        putEquipment(equipment, "HEAD", body.getItemBySlot(EquipmentSlot.HEAD));
        putEquipment(equipment, "CHEST", body.getItemBySlot(EquipmentSlot.CHEST));
        putEquipment(equipment, "LEGS", body.getItemBySlot(EquipmentSlot.LEGS));
        putEquipment(equipment, "FEET", body.getItemBySlot(EquipmentSlot.FEET));
        putEquipment(equipment, "OFF_HAND", body.getOffhandItem());
        putEquipment(equipment, "MAIN_HAND", body.getMainHandItem());
        List<BedCandidate> beds = new ArrayList<>();
        List<CropCandidate> crops = new ArrayList<>();
        BlockPos origin = body.blockPosition();
        int radius = factState == null ? 16 : Math.max(1, Math.min(16, factState.request.radius()));
        boolean scanBeds = factState != null && factState.request.kind() == DailyActionKind.SLEEP_AT_BED;
        boolean scanCrops = factState != null && factState.request.kind() == DailyActionKind.FARM_CROP;
        if (scanCrops && factState.request.target() != null
                && dimension.equals(factState.request.target().dimension())
                && level.hasChunkAt(new BlockPos(factState.request.target().x(),
                factState.request.target().y(), factState.request.target().z()))) {
            origin = new BlockPos(factState.request.target().x(), factState.request.target().y(),
                    factState.request.target().z());
        }
        if (scanBeds || scanCrops) for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius),
                origin.offset(radius, 4, radius))) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (scanBeds && state.getBlock() instanceof BedBlock) {
                boolean occupied = state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
                boolean bedWorks = level.dimensionType().bedWorks();
                beds.add(new BedCandidate(new DailyActionRequest.Position(dimension, pos.getX(), pos.getY(), pos.getZ()),
                        occupied, bedWorks && !occupied, !bedWorks ? "BED_WRONG_DIMENSION"
                        : occupied ? "BED_OCCUPIED" : ""));
            }
            if (scanCrops && state.getBlock() instanceof CropBlock crop) {
                crops.add(new CropCandidate(new DailyActionRequest.Position(dimension, pos.getX(), pos.getY(), pos.getZ()),
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), crop.isMaxAge(state),
                        seedFor(state.getBlock())));
            }
        }
        if (scanBeds) addExplicitBedCandidate(body, factState.request, dimension, beds);
        List<VehicleCandidate> vehicles = new ArrayList<>();
        List<AnimalCandidate> animals = new ArrayList<>();
        List<EntityCandidate> entities = new ArrayList<>();
        boolean scanVehicles = factState != null && factState.request.kind() == DailyActionKind.USE_VEHICLE;
        boolean scanAnimals = factState != null && factState.request.kind() == DailyActionKind.BREED_ANIMALS;
        boolean scanEntities = factState != null && factState.request.kind() == DailyActionKind.TRADE_WITH_VILLAGER;
        if (scanVehicles || scanAnimals || scanEntities) for (Entity entity : level.getEntities(
                body, body.getBoundingBox().inflate(radius), Entity::isAlive)) {
            DailyActionRequest.Position ep = new DailyActionRequest.Position(dimension,
                    entity.blockPosition().getX(), entity.blockPosition().getY(), entity.blockPosition().getZ());
            if (scanVehicles && (entity instanceof Boat || entity instanceof AbstractMinecart)) vehicles.add(new VehicleCandidate(entity.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), ep, entity.isAlive()));
            if (scanAnimals && entity instanceof Animal animal) {
                String food = breedingFood(animal, body);
                animals.add(new AnimalCandidate(entity.getUUID(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), ep, food,
                        !food.isBlank(), !animal.isBaby(), animal.isInLove(), animal.isAlive()));
            }
            if (scanEntities) entities.add(new EntityCandidate(entity.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    ep, entity.isAlive(), body.hasLineOfSight(entity)));
        }
        if (scanVehicles) addExplicitVehicleCandidate(body, factState.request, dimension, vehicles);
        if (scanAnimals) addExplicitAnimalCandidates(body, factState.request, dimension, animals);
        if (scanEntities) addExplicitEntityCandidate(body, factState.request.targetId(), dimension, entities);
        DailyActionSnapshot.VehicleFact vehicle = null;
        if (body.getVehicle() != null) {
            Entity ridden = body.getVehicle();
            int now = server.getTickCount();
            if (factState != null && (factState.vehiclePosition == null
                    || factState.vehiclePosition.distanceToSqr(ridden.position()) > .04D)) {
                factState.vehiclePosition = ridden.position(); factState.vehicleProgressTick = now;
            }
            boolean stuck = factState != null && factState.request.kind() == DailyActionKind.USE_VEHICLE
                    && now - factState.vehicleProgressTick > 80;
            vehicle = new DailyActionSnapshot.VehicleFact(true, ridden.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(ridden.getType()).toString(),
                    new DailyActionRequest.Position(dimension, ridden.blockPosition().getX(),
                            ridden.blockPosition().getY(), ridden.blockPosition().getZ()),
                    true, stuck, body.getHealth() > 4.0F && !body.isInLava() && ridden.isAlive());
        }
        BlockPos bucketPos = factState != null && factState.target != null
                ? new BlockPos(factState.target.x(), factState.target.y(), factState.target.z()) : origin;
        BlockState bucketState = level.getBlockState(bucketPos);
        String bucketBlock = blockAndFluidId(level, bucketPos);
        boolean bucketChanged = factState != null && (!bucketBlock.equals(factState.initialTargetBlock)
                || count(inventory, "minecraft:bucket") != factState.initialBucket
                || count(inventory, "minecraft:water_bucket") != factState.initialWaterBucket);
        Direction bucketFace = directionValue(factState == null ? "UP" : factState.request.direction());
        boolean allowed = factState == null || factState.target == null
                || body.mayUseItemAt(bucketPos, bucketFace, body.getMainHandItem());
        DailyActionSnapshot.BucketFact bucket = new DailyActionSnapshot.BucketFact(bucketChanged, bucketBlock,
                count(inventory, "minecraft:bucket"), count(inventory, "minecraft:water_bucket"), allowed,
                bucketState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                        && bucketState.getFluidState().isSource(), bucketState.canBeReplaced(),
                allowed ? "" : "POSITION_NOT_ALLOWED", factState == null ? null : factState.target,
                factState == null ? "" : factState.request.action(),
                factState == null ? "" : factState.request.direction());
        DailyActionSnapshot.MenuFact menu = menuFact(body, inventory);
        FishingHook hook = body.fishing;
        boolean hookAlive = hook != null && hook.isAlive();
        int hookAge = factState == null ? 0 : server.getTickCount() - factState.fishingCastTick;
        double previousHookY = factState == null || !factState.fishingHookYObserved
                ? hook == null ? 0.0D : hook.getY() : factState.fishingHookY;
        double hookDrop = hook == null ? 0.0D : previousHookY - hook.getY();
        if (factState != null && hook != null) {
            factState.fishingHookY = hook.getY();
            factState.fishingHookYObserved = true;
        }
        boolean biting = hook != null && (hook.getHookedIn() != null
                || hookAge >= 20 && hook.isOpenWaterFishing()
                && (hookDrop > .08D || hook.getDeltaMovement().y < -.08D));
        int lootCount = factState == null ? 0 : Math.max(0,
                fishCount(body) - factState.initialInventoryItems);
        String hookId = hook == null ? factState == null ? "" : factState.fishingHookId
                : hook.getUUID().toString();
        int rodDamage = body.getMainHandItem().is(Items.FISHING_ROD)
                ? Math.max(0, body.getMainHandItem().getDamageValue()
                - (factState == null ? body.getMainHandItem().getDamageValue() : factState.fishingRodDamage)) : 0;
        DailyActionSnapshot.FishFact fish = new DailyActionSnapshot.FishFact(
                body.getMainHandItem().is(Items.FISHING_ROD), hookAlive, biting,
                hookAlive, lootCount, factState != null && !hookAlive && factState.fishingReelVerified,
                hookId, body.getMainHandItem().isEmpty() ? "" : stackDigest(body.getMainHandItem()), rodDamage);
        CropCandidate chosenCrop = factState == null || factState.target == null ? null : crops.stream()
                .filter(value -> value.position().x() == factState.target.x()
                        && value.position().y() == factState.target.y()
                        && value.position().z() == factState.target.z()).findFirst().orElse(null);
        DailyActionSnapshot.CropFact crop = cropFact(level, factState, chosenCrop, inventory);
        Animal first = factState == null ? null : animal(level, factState.firstId);
        Animal second = factState == null ? null : animal(level, factState.secondId);
        int babyCount = factState == null ? 0 : (int) level.getEntitiesOfClass(Animal.class, body.getBoundingBox().inflate(12), value ->
                value.isBaby() && first != null && value.getType() == first.getType()).size();
        int babyDelta = factState == null ? 0 : Math.max(0, babyCount - factState.initialBabies);
        DailyActionSnapshot.BreedFact breed = new DailyActionSnapshot.BreedFact(
                factState != null && factState.firstFed, factState != null && factState.secondFed,
                babyDelta > 0, babyDelta,
                factState == null ? null : factState.firstId, factState == null ? null : factState.secondId,
                factState == null ? 0 : Math.max(0, factState.initialFood - count(inventory, factState.foodItem)));
        boolean targetReached = factState != null && factState.target != null
                && body.position().distanceToSqr(Vec3.atCenterOf(new BlockPos(factState.target.x(), factState.target.y(), factState.target.z()))) <= 9;
        double glideDistance=factState==null||factState.target==null?Double.POSITIVE_INFINITY:body.position().distanceToSqr(Vec3.atCenterOf(new BlockPos(factState.target.x(),factState.target.y(),factState.target.z())));if(factState!=null)factState.bestDistance=Math.min(factState.bestDistance,glideDistance);ItemStack chest=body.getItemBySlot(EquipmentSlot.CHEST);boolean terrainSafe=factState!=null&&safeGlideTerrain(body,factState.target,currentPhase(companionId)==com.mccompanion.core.body.daily.DailyActionPhase.LAND);
        DailyActionSnapshot.GlideFact glide = new DailyActionSnapshot.GlideFact(!body.onGround() && !body.isInWater()&&body.getDeltaMovement().y<0&&chest.is(Items.ELYTRA)&&(!chest.isDamageableItem()||chest.getDamageValue()<chest.getMaxDamage()-1),
                targetReached, terrainSafe, glideDistance,
                factState == null ? Double.MAX_VALUE : factState.bestDistance, terrainSafe?"":"GLIDE_TERRAIN_UNSAFE");
        DailyActionRequest.Position selectedTarget=selectedTarget(companionId);BedCandidate selectedBed=beds.stream().filter(value->selectedTarget!=null&&selectedTarget.equals(value.position())).findFirst().orElse(beds.isEmpty()?null:beds.get(0));DailyActionSnapshot.BedFact bed=selectedBed==null?new DailyActionSnapshot.BedFact(false,null,false,"BED_NOT_FOUND"):new DailyActionSnapshot.BedFact(true,selectedBed.position(),selectedBed.usable(),selectedBed.problem());
        return new DailyActionSnapshot(server.getTickCount(), body.isAlive(), dimension, position, inventory,
                equipment, bed, bucket, vehicle, fish, crop, breed, menu, body.experienceLevel, body.isSleeping(),
                body.onGround(), body.isFallFlying(), items, beds, vehicles, crops, animals, entities,
                tradeFact(body, factState), enchantFact(body, factState), brewFact(body, factState), glide);
    }

    private static DailyActionSnapshot.CropFact cropFact(ServerLevel level, Facts facts, CropCandidate candidate,
                                                           Map<String, ItemFact> inventory) {
        if (facts == null || facts.activeTarget == null || facts.cropId.isBlank()) return null;
        BlockPos pos = new BlockPos(facts.activeTarget.x(), facts.activeTarget.y(), facts.activeTarget.z());
        BlockState state = level.getBlockState(pos);
        boolean isCrop = state.getBlock() instanceof CropBlock crop;
        int age = isCrop ? ((CropBlock) state.getBlock()).getAge(state) : -1;
        boolean sameCrop = isCrop && facts.cropId.equals(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        int harvested = Math.max(0, count(inventory, facts.harvestItem) - facts.harvestBefore);
        int seedConsumed = Math.max(0, facts.seedBeforeReplant - count(inventory, facts.seedItem));
        return new DailyActionSnapshot.CropFact(true, facts.activeTarget, false,
                !isCrop, facts.pickedUp && harvested > 0, facts.replantVerified && sameCrop,
                age, facts.seedItem, harvested, facts.cropId, facts.harvestItem,
                harvested, seedConsumed);
    }

    private static DailyActionSnapshot.TradeFact tradeFact(CompanionPlayer body, Facts facts) {
        if (facts == null || !(body.containerMenu instanceof MerchantMenu menu)
                || menu.getOffers().isEmpty() || facts.tradeOffer < 0
                || facts.tradeOffer >= menu.getOffers().size()) return null;
        var offer = menu.getOffers().get(facts.tradeOffer);
        ItemStack paymentA = menu.getSlot(0).getItem();
        ItemStack paymentB = menu.getSlot(1).getItem();
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        String inputA = BuiltInRegistries.ITEM.getKey(costA.getItem()).toString();
        String inputB = costB.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(costB.getItem()).toString();
        String output = BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).toString();
        int consumedA = Math.max(0, facts.tradeInputA - countItem(body, inputA));
        int consumedB = inputB.isBlank() ? 0 : Math.max(0, facts.tradeInputB - countItem(body, inputB));
        int received = Math.max(0, countItem(body, output) - facts.tradeOutput);
        Entity villager = entity(body.serverLevel(), facts.tradeVillagerId);
        boolean valid = menu.stillValid(body) && villager != null && villager.isAlive()
                && menu.containerId == facts.tradeMenuId;
        boolean inputsReady = offer.satisfiedBy(paymentA, paymentB);
        return new DailyActionSnapshot.TradeFact(valid, facts.tradeVillagerId, facts.tradeOffer,
                offer.isOutOfStock(), offer.getUses(), inputA, inputB, output, inputsReady,
                inventoryCapacity(body, offer.getResult()) >= offer.getResult().getCount(), inputsReady,
                consumedA, consumedB, received, costA.getCount(),
                costB.isEmpty() ? 0 : costB.getCount(), offer.getResult().getCount());
    }

    private static DailyActionSnapshot.EnchantFact enchantFact(CompanionPlayer body, Facts facts) {
        if (facts == null || !(body.containerMenu instanceof EnchantmentMenu menu)
                || facts.enchantStation == null) return null;
        int option = Math.max(0, Math.min(facts.enchantOption, menu.costs.length - 1));
        ItemStack item = menu.getSlot(0).getItem();
        BlockPos station = new BlockPos(facts.enchantStation.x(), facts.enchantStation.y(),
                facts.enchantStation.z());
        boolean valid = menu.stillValid(body) && menu.containerId == facts.enchantMenuId
                && body.serverLevel().getBlockState(station).is(Blocks.ENCHANTING_TABLE);
        return new DailyActionSnapshot.EnchantFact(valid, facts.enchantStation,
                item.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),
                option, menu.costs[option], totalLapis(body),
                !item.isEmpty() && menu.getGoldCount() >= option + 1, stackDigest(item),
                Math.max(0, facts.enchantXp - body.experienceLevel),
                Math.max(0, facts.enchantLapis - totalLapis(body)));
    }

    private static DailyActionSnapshot.BrewFact brewFact(CompanionPlayer body, Facts facts) {
        if (facts == null || !(body.containerMenu instanceof BrewingStandMenu menu)
                || facts.brewStation == null) return null;
        int requested = Math.max(1, facts.brewBottles);
        Map<Integer, String> bottles = new HashMap<>();
        int loaded = 0;
        for (int slot = 0; slot < requested; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty()) { loaded++; bottles.put(slot, stackDigest(stack)); }
        }
        int ticks = menu.getBrewingTicks();
        if (ticks > 0) facts.brewStarted = true;
        if (facts.brewStarted && ticks == 0 && loaded == requested
                && facts.brewCompleted.isEmpty() && !bottles.equals(facts.brewInitial)) {
            facts.brewCompleted = Map.copyOf(bottles);
        }
        Map<Integer, String> observed = facts.brewStarted && ticks == 0 && !facts.brewCompleted.isEmpty()
                ? facts.brewCompleted : bottles;
        BlockPos station = new BlockPos(facts.brewStation.x(), facts.brewStation.y(), facts.brewStation.z());
        boolean valid = menu.stillValid(body) && menu.containerId == facts.brewMenuId
                && body.serverLevel().getBlockState(station).is(Blocks.BREWING_STAND);
        ItemStack ingredientStack = menu.getSlot(3).getItem();
        String observedIngredient = ingredientStack.isEmpty() ? facts.parameters.itemId()
                : BuiltInRegistries.ITEM.getKey(ingredientStack.getItem()).toString();
        Item ingredient = Facts.resolve(facts.parameters.itemId());
        boolean materials = loaded + countItem(body, "minecraft:potion") >= requested
                && (!ingredientStack.isEmpty()
                || ingredient != null && body.getInventory().countItem(ingredient) > 0);
        boolean fuel = menu.getFuel() > 0 || body.getInventory().countItem(Items.BLAZE_POWDER) > 0
                || facts.brewStarted;
        return new DailyActionSnapshot.BrewFact(valid, facts.brewStation, observedIngredient, requested,
                menu.getFuel(), materials, fuel, loaded >= requested && !ingredientStack.isEmpty(),
                ticks, observed, facts.brewTaken, facts.brewTaken);
    }

    private static Animal animal(ServerLevel level, UUID id) {
        Entity value = id == null ? null : level.getEntity(id); return value instanceof Animal animal ? animal : null;
    }

    private static int factStateOrZero(Facts facts, String ignored) { return facts == null ? 0 : facts.initialBabies; }


    private static DailyActionSnapshot.MenuFact menuFact(CompanionPlayer body, Map<String, ItemFact> inventory) {
        if (body.containerMenu == body.inventoryMenu) return null;
        String name = body.containerMenu.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        String type = name.contains("MERCHANT") ? "MERCHANT" : name.contains("ENCHANT") ? "ENCHANTMENT"
                : name.contains("BREW") ? "BREWING" : name;
        Map<String, String> details = new HashMap<>();
        details.put("valid", "true");
        details.put("inputsReady", Boolean.toString(!body.containerMenu.getSlot(0).getItem().isEmpty()));
        int offer=0,uses=0,maxUses=0,cost=0,lapis=0,brewTicks=0,resultCount=0;String output="";boolean disabled=false,brewReady=false;
        if(body.containerMenu instanceof MerchantMenu menu&&!menu.getOffers().isEmpty()){var selected=menu.getOffers().get(0);uses=selected.getUses();maxUses=selected.getMaxUses();disabled=selected.isOutOfStock();output=BuiltInRegistries.ITEM.getKey(selected.getResult().getItem()).toString();details.put("resourcesAvailable",Boolean.toString(selected.satisfiedBy(menu.getSlot(0).getItem(),menu.getSlot(1).getItem())));}
        if(body.containerMenu instanceof EnchantmentMenu menu){cost=menu.costs[0];lapis=totalLapis(body);}
        if(body.containerMenu instanceof BrewingStandMenu menu){brewTicks=menu.getBrewingTicks();resultCount=(int)java.util.stream.IntStream.range(0,3).filter(slot->!menu.getSlot(slot).getItem().isEmpty()).count();brewReady=brewTicks==0&&resultCount>0;details.put("materialsAvailable",Boolean.toString(!menu.getSlot(3).getItem().isEmpty()));details.put("fuelAvailable",Boolean.toString(menu.getFuel()>0));}
        return new DailyActionSnapshot.MenuFact(true,type,offer,disabled,"","",output,uses,maxUses,cost,lapis,false,brewTicks,brewReady,resultCount,details);
    }

    private static String id(ItemStack stack) { return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }


    private static int count(Map<String, ItemFact> values, String id) {
        ItemFact fact = values.get(id); return fact == null ? 0 : fact.count();
    }

    private static void putEquipment(Map<String, ItemFact> result, String slot, ItemStack stack) {
        if (!stack.isEmpty()) result.put(slot, new ItemFact(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), stack.getDamageValue(), stack.getMaxDamage(),
                Map.of("digest", stackDigest(stack))));
    }

    private static String weaponType(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
                ? "RANGED" : item instanceof SwordItem || item instanceof AxeItem ? "MELEE" : "";
    }

    private static String seedFor(Block block) {
        if(block==Blocks.WHEAT)return "minecraft:wheat_seeds";if(block==Blocks.BEETROOTS)return "minecraft:beetroot_seeds";if(block==Blocks.CARROTS)return "minecraft:carrot";if(block==Blocks.POTATOES)return "minecraft:potato";return "";
    }

    private static void addExplicitVehicleCandidate(CompanionPlayer body, DailyActionRequest request,
                                                    String dimension, List<VehicleCandidate> candidates) {
        if (request.targetId().isBlank() || candidates.stream()
                .anyMatch(candidate -> candidate.id().toString().equals(request.targetId()))) return;
        Entity target = entity(body.serverLevel(), request.targetId());
        if ((target instanceof Boat || target instanceof AbstractMinecart) && target.isAlive()) {
            candidates.add(new VehicleCandidate(target.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                    new DailyActionRequest.Position(dimension, target.blockPosition().getX(),
                            target.blockPosition().getY(), target.blockPosition().getZ()), true));
        }
    }

    private static void addExplicitAnimalCandidates(CompanionPlayer body, DailyActionRequest request,
                                                    String dimension, List<AnimalCandidate> candidates) {
        for (String targetId : List.of(request.targetId(), request.secondaryTargetId())) {
            if (targetId.isBlank() || candidates.stream()
                    .anyMatch(candidate -> candidate.id().toString().equals(targetId))) continue;
            Entity target = entity(body.serverLevel(), targetId);
            if (!(target instanceof Animal animal) || !animal.isAlive()) continue;
            String food = breedingFood(animal, body);
            candidates.add(new AnimalCandidate(animal.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString(),
                    new DailyActionRequest.Position(dimension, animal.blockPosition().getX(),
                            animal.blockPosition().getY(), animal.blockPosition().getZ()),
                    food, !food.isBlank(), !animal.isBaby(), animal.isInLove(), true));
        }
    }

    private static void addExplicitEntityCandidate(CompanionPlayer body, String targetId,
                                                   String dimension, List<EntityCandidate> candidates) {
        if (targetId.isBlank() || candidates.stream()
                .anyMatch(candidate -> candidate.id().toString().equals(targetId))) return;
        Entity target = entity(body.serverLevel(), targetId);
        if (target instanceof LivingEntity living && living.isAlive()) {
            candidates.add(new EntityCandidate(living.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString(),
                    new DailyActionRequest.Position(dimension, living.blockPosition().getX(),
                            living.blockPosition().getY(), living.blockPosition().getZ()),
                    true, body.hasLineOfSight(living)));
        }
    }

    private static void addExplicitBedCandidate(CompanionPlayer body, DailyActionRequest request,
                                                String dimension, List<BedCandidate> beds) {
        if (request == null || request.kind() != DailyActionKind.SLEEP_AT_BED
                || request.target() == null || !dimension.equals(request.target().dimension())
                || beds.stream().anyMatch(candidate -> request.target().equals(candidate.position()))) return;
        BlockPos position = new BlockPos(request.target().x(), request.target().y(), request.target().z());
        if (!body.serverLevel().hasChunkAt(position)) return;
        BlockState state = body.serverLevel().getBlockState(position);
        if (!(state.getBlock() instanceof BedBlock)) return;
        boolean occupied = state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
        boolean bedWorks = body.serverLevel().dimensionType().bedWorks();
        beds.add(new BedCandidate(request.target(), occupied, bedWorks && !occupied,
                !bedWorks ? "BED_WRONG_DIMENSION" : occupied ? "BED_OCCUPIED" : ""));
    }

    private static final class Facts {
        final SkillParameters parameters;
        final DailyActionRequest request;
        CompanionPlayer body;
        final DailyActionRequest.Position target;
        DailyActionRequest.Position activeTarget;
        final String initialTargetBlock;
        final int initialBucket;
        final int initialWaterBucket;
        int initialSeed;
        int initialFood;
        final int initialInventoryItems;
        int initialBabies;
        String foodItem;
        String seedItem;
        String cropId = "";
        String harvestItem = "";
        int harvestBefore;
        int seedBeforeReplant;
        UUID firstId;
        UUID secondId;
        String fishingHookId = "";
        int fishingRodDamage;
        boolean fishingReelVerified;
        int fishingCastTick;
        double fishingHookY;
        boolean fishingHookYObserved;
        String tradeVillagerId = "";
        int tradeMenuId = -1;
        int tradeOffer = -1;
        int tradeUses;
        int tradeInputA;
        int tradeInputB;
        int tradeOutput;
        DailyActionRequest.Position enchantStation;
        int enchantMenuId = -1;
        int enchantOption;
        int enchantXp;
        int enchantLapis;
        String enchantDigest = "";
        DailyActionRequest.Position brewStation;
        int brewMenuId = -1;
        int brewBottles;
        Map<Integer, String> brewInitial = Map.of();
        Map<Integer, String> brewCompleted = Map.of();
        int brewTaken;
        boolean brewStarted;
        Vec3 vehiclePosition;
        int vehicleProgressTick;
        boolean firstFed;
        boolean secondFed;
        boolean pickedUp;
        boolean replantVerified;
        double bestDistance = Double.MAX_VALUE;

        Facts(SkillParameters parameters, CompanionPlayer body, DailyActionRequest request) {
            this.parameters = parameters;
            this.request = request;
            this.body = body;
            this.target = request.target();
            this.activeTarget = this.target;
            BlockPos pos = target == null ? body.blockPosition() : new BlockPos(target.x(), target.y(), target.z());
            this.initialTargetBlock = blockAndFluidId(body.serverLevel(), pos);
            this.initialBucket = body.getInventory().countItem(net.minecraft.world.item.Items.BUCKET);
            this.initialWaterBucket = body.getInventory().countItem(net.minecraft.world.item.Items.WATER_BUCKET);
            this.seedItem = "";
            this.initialSeed = 0;
            this.foodItem = "";
            this.initialFood = 0;
            this.initialInventoryItems = fishCount(body);
            this.firstId = uuid(parameters.targetId());
            this.secondId = uuid(parameters.secondaryTargetId());
            this.initialBabies = (int) body.serverLevel().getEntitiesOfClass(Animal.class, body.getBoundingBox().inflate(12),
                    value -> value.isBaby()).size();
            this.fishingRodDamage = body.getMainHandItem().is(Items.FISHING_ROD)
                    ? body.getMainHandItem().getDamageValue() : 0;
            this.vehicleProgressTick = body.serverLevel().getServer().getTickCount();
        }

        private static UUID uuid(String value) { try { return value == null || value.isBlank() ? null : UUID.fromString(value); } catch (Exception e) { return null; } }
        private static Item resolve(String value) { var id = net.minecraft.resources.ResourceLocation.tryParse(value); return id == null||!BuiltInRegistries.ITEM.containsKey(id) ? null : BuiltInRegistries.ITEM.get(id); }
    }

    private final class Adapter implements DailyActionEngine.Adapter {
        private final CompanionPlayer body;
        Adapter(CompanionPlayer body) { this.body = body; }

        @Override public DailyActionCommand.CommandResult execute(DailyActionCommand command) {
            try {
                if (command instanceof DailyActionCommand.Navigate navigate) return navigate(navigate.target());
                if (command instanceof DailyActionCommand.NavigateEntity navigate) return navigateEntity(navigate);
                if (command instanceof DailyActionCommand.Equip equip) return equip(equip);
                if (command instanceof DailyActionCommand.Bed bed) return bed(bed);
                if (command instanceof DailyActionCommand.Bucket bucket) return bucket(bucket);
                if (command instanceof DailyActionCommand.Vehicle vehicle) return vehicle(vehicle);
                if (command instanceof DailyActionCommand.Crop crop) return crop(crop);
                if (command instanceof DailyActionCommand.Breed breed) return breed(breed);
                if (command instanceof DailyActionCommand.Trade trade) return trade(trade);
                if (command instanceof DailyActionCommand.Enchant enchant) return enchant(enchant);
                if (command instanceof DailyActionCommand.Brew brew) return brew(brew);
                if (command instanceof DailyActionCommand.Glide glide) return glide(glide);
                if (command instanceof DailyActionCommand.Fishing fishing) return fishing(fishing);
                return DailyActionCommand.CommandResult.rejected("COMMAND_UNSUPPORTED");
            } catch (RuntimeException failure) {
                return DailyActionCommand.CommandResult.uncertain("VANILLA_ACTION_ERROR");
            }
        }

        @Override public DailyActionCommand.CommandResult maintain(DailyActionCommand command) {
            return execute(command);
        }

        @Override public void cleanup(DailyActionEngine.Session session, String reason) {
            gateway.stopInput(body);
            DailyNavigator navigator = navigators.get(body.getUUID());
            if (navigator != null) {
                if (session.status() == DailyActionEngine.Status.PAUSED) navigator.pause(server.getTickCount());
                else navigator.cancel();
            }
            if (body.getVehicle() instanceof Boat boat) gateway.applyVehicleInput(body,boat,boat.getYRot(),false,false,false,false);
            if (session.request().kind() == DailyActionKind.USE_VEHICLE
                    && session.status() != DailyActionEngine.Status.COMPLETE
                    && session.status() != DailyActionEngine.Status.PAUSED && body.getVehicle() != null) body.stopRiding();
            if (session.request().kind() == DailyActionKind.GLIDE_WITH_ELYTRA
                    && session.status() != DailyActionEngine.Status.PAUSED && body.isFallFlying()) body.stopFallFlying();
            if (session.request().kind() == DailyActionKind.SLEEP_AT_BED
                    && session.status() != DailyActionEngine.Status.PAUSED
                    && session.status() != DailyActionEngine.Status.COMPLETE && body.isSleeping()) body.stopSleeping();
            if (body.fishing != null && session.status() != DailyActionEngine.Status.PAUSED) {
                if (!ensureMainHand(Items.FISHING_ROD)) {
                    throw new IllegalStateException("FISHING_ROD_CLEANUP_MISSING");
                }
                body.gameMode.useItem(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND);
                gateway.markVanillaGameModeAction(body);
                if (body.fishing != null) {
                    throw new IllegalStateException("FISHING_HOOK_CLEANUP_UNVERIFIED");
                }
            }
            if (session.status() != DailyActionEngine.Status.PAUSED && body.containerMenu != body.inventoryMenu) {
                try { returnMenuInputs(body); }
                catch (RuntimeException ignored) { }
                finally { body.closeContainer(); }
            }
        }

        private DailyActionCommand.CommandResult navigate(DailyActionRequest.Position target) {
            if (target == null || !target.dimension().equals(body.serverLevel().dimension().location().toString()))
                return DailyActionCommand.CommandResult.rejected("TARGET_INVALID");
            return navigateShared(body, "block:" + target, () -> new DailyNavigator.Goal(
                    new NavPoint(target.x(), target.y(), target.z()), target.dimension()));
        }

        private DailyActionCommand.CommandResult navigateEntity(DailyActionCommand.NavigateEntity command) {
            Entity initial = entity(command.targetId());
            if (initial == null || !initial.isAlive()) return DailyActionCommand.CommandResult.rejected("ENTITY_NOT_FOUND");
            return navigateShared(body, "entity:" + command.targetId(), () -> {
                Entity current = entity(command.targetId());
                if (current == null || !current.isAlive()) throw new IllegalStateException("ENTITY_LOST");
                BlockPos position = current.blockPosition();
                return new DailyNavigator.Goal(new NavPoint(position.getX(), position.getY(), position.getZ()),
                        body.serverLevel().dimension().location().toString());
            });
        }

        private Entity entity(String id) { try { return body.serverLevel().getEntity(UUID.fromString(id)); } catch (Exception e) { return null; } }

        private DailyActionCommand.CommandResult equip(DailyActionCommand.Equip cmd) {
            if (cmd.action().equals("UNEQUIP")) {
                int destination = cmd.destination().equals("MAIN_HAND")
                        ? 36 + body.getInventory().selected : equipmentMenuSlot(cmd.destination());
                if (destination < 0 || body.containerMenu != body.inventoryMenu) return DailyActionCommand.CommandResult.rejected("EQUIPMENT_SLOT_INVALID");
                int target = firstEmptyInventoryMenuSlot(); if (target < 0) return DailyActionCommand.CommandResult.rejected("INVENTORY_FULL");
                body.inventoryMenu.clicked(destination, 0, ClickType.PICKUP, body); body.inventoryMenu.clicked(target, 0, ClickType.PICKUP, body);if(!body.inventoryMenu.getCarried().isEmpty())body.inventoryMenu.clicked(destination,0,ClickType.PICKUP,body);
                gateway.markVanillaMenuAction(body); return body.inventoryMenu.getCarried().isEmpty()?DailyActionCommand.CommandResult.success():DailyActionCommand.CommandResult.uncertain("UNEQUIP_NOT_VERIFIED");
            }
            if (cmd.sourceSlot() < 0) return DailyActionCommand.CommandResult.rejected("ITEM_NOT_FOUND");
            int source = inventoryMenuSlot(cmd.sourceSlot());
            if (source < 0 || source >= body.inventoryMenu.slots.size()) return DailyActionCommand.CommandResult.rejected("ITEM_SLOT_INVALID");
            ItemStack sourceStack=body.inventoryMenu.getSlot(source).getItem();if(sourceStack.isEmpty()||!BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString().equals(cmd.itemId()))return DailyActionCommand.CommandResult.rejected("EQUIPMENT_SOURCE_CHANGED");if(!cmd.stackDigest().isBlank()&&!cmd.stackDigest().equals(stackDigest(sourceStack)))return DailyActionCommand.CommandResult.rejected("EQUIPMENT_SOURCE_CHANGED");if(sourceStack.isDamageableItem()&&sourceStack.getDamageValue()>=sourceStack.getMaxDamage()-1)return DailyActionCommand.CommandResult.rejected("EQUIPMENT_UNUSABLE");
            String requested=cmd.destination().equals("AUTO")?autoEquipment(sourceStack.getItem()):cmd.destination();
            if(requested.equals("MAIN_HAND")){int inventorySlot=cmd.sourceSlot(),hotbar=inventorySlot<9?inventorySlot:body.getInventory().getSuitableHotbarSlot();if(inventorySlot>=9){body.inventoryMenu.clicked(source,hotbar,ClickType.SWAP,body);gateway.markVanillaMenuAction(body);}gateway.selectHotbarSlot(body,hotbar);return body.getMainHandItem().is(sourceStack.getItem())?DailyActionCommand.CommandResult.success():DailyActionCommand.CommandResult.uncertain("EQUIP_NOT_VERIFIED");}
            int destination = equipmentMenuSlot(requested);if(destination<0)return DailyActionCommand.CommandResult.rejected("EQUIPMENT_SLOT_INVALID");
            body.inventoryMenu.clicked(source, 0, ClickType.PICKUP, body); body.inventoryMenu.clicked(destination, 0, ClickType.PICKUP, body);
            if (!body.inventoryMenu.getCarried().isEmpty()) body.inventoryMenu.clicked(source, 0, ClickType.PICKUP, body);
            gateway.markVanillaMenuAction(body); return DailyActionCommand.CommandResult.success();
        }

        private DailyActionCommand.CommandResult bed(DailyActionCommand.Bed cmd) {
            if (cmd.action().equals("WAKE")) { body.stopSleeping(); return DailyActionCommand.CommandResult.success(); }
            if (cmd.target() == null) return DailyActionCommand.CommandResult.rejected("BED_NOT_FOUND");
            BlockPos pos = new BlockPos(cmd.target().x(), cmd.target().y(), cmd.target().z());
            if (!(body.serverLevel().getBlockState(pos).getBlock() instanceof BedBlock)) return DailyActionCommand.CommandResult.rejected("BED_LOST");
            if(!body.serverLevel().dimensionType().bedWorks())return DailyActionCommand.CommandResult.rejected("BED_WRONG_DIMENSION");
            var result = body.startSleepInBed(pos); gateway.markVanillaGameModeAction(body);
            return result.left().isPresent() ? DailyActionCommand.CommandResult.rejected("BED_"+result.left().orElseThrow().name()) : DailyActionCommand.CommandResult.success();
        }

        private DailyActionCommand.CommandResult bucket(DailyActionCommand.Bucket cmd) {
            if (cmd.target() == null) return DailyActionCommand.CommandResult.rejected("TARGET_MISSING");
            Item wanted = cmd.action().equals("FILL_BUCKET") ? net.minecraft.world.item.Items.BUCKET : net.minecraft.world.item.Items.WATER_BUCKET;
            if (!ensureMainHand(wanted)) return DailyActionCommand.CommandResult.rejected("BUCKET_MISSING");
            BlockPos pos = new BlockPos(cmd.target().x(), cmd.target().y(), cmd.target().z());
            Direction face = direction(cmd.direction());
            BlockState state = body.serverLevel().getBlockState(pos);
            if (cmd.action().equals("FILL_BUCKET") && (!state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                    || !state.getFluidState().isSource())) return DailyActionCommand.CommandResult.rejected("WATER_SOURCE_INVALID");
            if (cmd.action().equals("EMPTY_BUCKET") && !state.canBeReplaced())
                return DailyActionCommand.CommandResult.rejected("WATER_TARGET_INVALID");
            if (!body.mayUseItemAt(pos, face, body.getMainHandItem()))
                return DailyActionCommand.CommandResult.rejected("POSITION_NOT_ALLOWED");
            Vec3 look = cmd.action().equals("FILL_BUCKET") ? Vec3.atCenterOf(pos)
                    : Vec3.atCenterOf(pos.relative(face.getOpposite())).add(
                    face.getStepX()*.5D,face.getStepY()*.5D,face.getStepZ()*.5D);
            gateway.lookAt(body,look);
            var result = body.gameMode.useItem(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND);
            gateway.markVanillaGameModeAction(body); return result.consumesAction() ? DailyActionCommand.CommandResult.success() : DailyActionCommand.CommandResult.uncertain("BUCKET_USE_UNVERIFIED");
        }

        private DailyActionCommand.CommandResult vehicle(DailyActionCommand.Vehicle cmd) {
            if (cmd.action().equals("DISEMBARK_VEHICLE")) { body.stopRiding(); gateway.markVanillaEntityInteraction(body); return DailyActionCommand.CommandResult.success(); }
            Entity target = entity(cmd.targetId());
            if(target==null&&body.getVehicle()!=null)target=body.getVehicle();
            if (target == null) return DailyActionCommand.CommandResult.rejected("VEHICLE_NOT_FOUND");
            if (!(target instanceof Boat) && !(target instanceof AbstractMinecart))
                return DailyActionCommand.CommandResult.rejected("VEHICLE_TYPE_UNSUPPORTED");
            if (cmd.action().equals("BOARD_VEHICLE")) { InteractionResult result = body.interactOn(target, InteractionHand.MAIN_HAND); gateway.markVanillaEntityInteraction(body); return result.consumesAction() ? DailyActionCommand.CommandResult.success() : DailyActionCommand.CommandResult.uncertain("VEHICLE_MOUNT_UNVERIFIED"); }
            if(cmd.action().equals("TRAVEL_VEHICLE")&&body.getVehicle()!=target)return DailyActionCommand.CommandResult.rejected("VEHICLE_CHANGED");
            if (cmd.action().equals("TRAVEL_VEHICLE") && target instanceof Boat boat) {if(cmd.target()==null)return DailyActionCommand.CommandResult.rejected("VEHICLE_DESTINATION_MISSING");Vec3 delta=Vec3.atCenterOf(new BlockPos(cmd.target().x(),cmd.target().y(),cmd.target().z())).subtract(boat.position());float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),diff=net.minecraft.util.Mth.wrapDegrees(yaw-boat.getYRot());gateway.applyVehicleInput(body,boat,yaw,diff<-4,diff>4,true,false);return DailyActionCommand.CommandResult.success(); }
            if(cmd.action().equals("TRAVEL_VEHICLE")&&target instanceof AbstractMinecart)return DailyActionCommand.CommandResult.success();
            return DailyActionCommand.CommandResult.success();
        }

        private DailyActionCommand.CommandResult crop(DailyActionCommand.Crop cmd) {
            if (cmd.target() == null) return DailyActionCommand.CommandResult.rejected("CROP_TARGET_MISSING");
            BlockPos pos = new BlockPos(cmd.target().x(), cmd.target().y(), cmd.target().z());
            BlockState state = body.serverLevel().getBlockState(pos);
            if (cmd.action().equals("HARVEST_CROP") && state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                Facts f=findFacts(); if(f!=null){f.activeTarget=cmd.target();f.cropId=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();f.seedItem=seedFor(state.getBlock());f.harvestItem=harvestFor(state.getBlock());f.harvestBefore=countItem(body,f.harvestItem);}
                if (!body.gameMode.destroyBlock(pos)) return DailyActionCommand.CommandResult.rejected("CROP_HARVEST_FAILED");
                gateway.markVanillaGameModeAction(body); return DailyActionCommand.CommandResult.success();
            }
            if (cmd.action().equals("PICKUP_CROP")) {
                Facts f=findFacts(); Item harvest=f==null?null:resolve(f.harvestItem),seed=f==null?null:resolve(f.seedItem);
                ItemEntity nearest=body.serverLevel().getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(8.0D),value->value.isAlive()&&(value.getItem().is(harvest)||value.getItem().is(seed))).stream().min(java.util.Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
                if(nearest!=null&&nearest.distanceToSqr(body)<=2.25D){gateway.stopInput(body);nearest.playerTouch(body);}else if(nearest!=null){Vec3 delta=nearest.position().subtract(body.position());gateway.applyMoveInput(body,(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),delta.y>.6D||body.horizontalCollision);}
                boolean changed=f!=null&&countItem(body,f.harvestItem)>f.harvestBefore;
                if(changed)gateway.stopInput(body);if(f!=null)f.pickedUp=changed;
                return DailyActionCommand.CommandResult.success();
            }
            if (cmd.action().equals("REPLANT_CROP")) {
                Facts f = findFacts();
                String seedId = f == null ? cmd.seedItem() : f.seedItem;
                Item item = resolve(seedId);
                if (!body.serverLevel().getBlockState(pos.below()).is(Blocks.FARMLAND)) {
                    return DailyActionCommand.CommandResult.rejected("FARMLAND_INVALID");
                }
                if (item == null || !ensureMainHand(item)) {
                    return DailyActionCommand.CommandResult.rejected("SEED_MISSING");
                }
                if (f != null) {
                    f.seedBeforeReplant = countItem(body, seedId);
                }
                var result = body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false));
                BlockState planted = body.serverLevel().getBlockState(pos);
                boolean verified = result.consumesAction()
                        && planted.getBlock() instanceof CropBlock plantedCrop
                        && plantedCrop.getAge(planted) == 0;
                if (verified && f != null) {
                    f.replantVerified = true;
                }
                gateway.markVanillaGameModeAction(body);
                return verified ? DailyActionCommand.CommandResult.success()
                        : DailyActionCommand.CommandResult.uncertain("REPLANT_UNVERIFIED");
            }
            return DailyActionCommand.CommandResult.success();
        }
        private DailyActionCommand.CommandResult breed(DailyActionCommand.Breed cmd) {
            if(!cmd.action().equals("FEED_FIRST")&&!cmd.action().equals("FEED_SECOND"))return DailyActionCommand.CommandResult.success();
            Entity target = entity(cmd.action().equals("FEED_SECOND") ? cmd.secondId() : cmd.firstId());
            if (!(target instanceof Animal animal) || !animal.isAlive()) return DailyActionCommand.CommandResult.rejected("ANIMAL_NOT_FOUND");
            Item food = resolve(cmd.foodItem()); if (food == null || !ensureMainHand(food)) return DailyActionCommand.CommandResult.rejected("BREEDING_FOOD_MISSING");
            if(animal.isBaby()||!animal.isFood(body.getMainHandItem())||!animal.canFallInLove())return DailyActionCommand.CommandResult.rejected("ANIMAL_NOT_BREEDABLE");
            Facts f=findFacts();if(f!=null&&f.foodItem.isBlank()){f.foodItem=cmd.foodItem();f.initialFood=countItem(body,cmd.foodItem());f.firstId=parseUuid(cmd.firstId());f.secondId=parseUuid(cmd.secondId());Animal first=animal(body.serverLevel(),f.firstId);if(first!=null)f.initialBabies=babyCount(body,first.getType());}
            int before=countItem(body,cmd.foodItem());
            InteractionResult result = body.interactOn(animal, InteractionHand.MAIN_HAND); gateway.markVanillaEntityInteraction(body);
            if (result.consumesAction()&&animal.isInLove()&&countItem(body,cmd.foodItem())<before) { if (f != null) { if (cmd.action().equals("FEED_SECOND")) f.secondFed = true; else f.firstFed = true; } return DailyActionCommand.CommandResult.success(); }
            return DailyActionCommand.CommandResult.uncertain("BREED_FEED_UNVERIFIED");
        }
        private DailyActionCommand.CommandResult trade(DailyActionCommand.Trade cmd) {
            if (cmd.action().equals("OPEN_TRADE")) {
                Entity target = entity(cmd.villagerId());
                if (target == null) return DailyActionCommand.CommandResult.rejected("VILLAGER_NOT_FOUND");
                InteractionResult result = body.interactOn(target, InteractionHand.MAIN_HAND);
                gateway.markVanillaEntityInteraction(body);
                Facts f=findFacts();if(result.consumesAction()&&f!=null){f.tradeVillagerId=target.getUUID().toString();f.tradeOffer=cmd.offerIndex();if(body.containerMenu instanceof MerchantMenu menu&&cmd.offerIndex()>=0&&cmd.offerIndex()<menu.getOffers().size()){f.tradeMenuId=menu.containerId;var offer=menu.getOffers().get(cmd.offerIndex());f.tradeUses=offer.getUses();f.tradeInputA=countItem(body,BuiltInRegistries.ITEM.getKey(offer.getCostA().getItem()).toString());f.tradeInputB=offer.getCostB().isEmpty()?0:countItem(body,BuiltInRegistries.ITEM.getKey(offer.getCostB().getItem()).toString());f.tradeOutput=countItem(body,BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).toString());}}
                return result.consumesAction() ? DailyActionCommand.CommandResult.success() : DailyActionCommand.CommandResult.uncertain("TRADE_OPEN_UNVERIFIED");
            }
            if (!(body.containerMenu instanceof MerchantMenu menu)) return DailyActionCommand.CommandResult.rejected("TRADE_MENU_INVALIDATED");
            if (cmd.offerIndex()<0||cmd.offerIndex()>=menu.getOffers().size()) return DailyActionCommand.CommandResult.rejected("TRADE_OFFER_NOT_FOUND");
            var offer=menu.getOffers().get(cmd.offerIndex());Facts f=findFacts();if(f!=null&&f.tradeOffer<0){f.tradeOffer=cmd.offerIndex();f.tradeUses=offer.getUses();f.tradeInputA=countItem(body,BuiltInRegistries.ITEM.getKey(offer.getCostA().getItem()).toString());f.tradeInputB=offer.getCostB().isEmpty()?0:countItem(body,BuiltInRegistries.ITEM.getKey(offer.getCostB().getItem()).toString());f.tradeOutput=countItem(body,BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).toString());}
            if (cmd.action().equals("SELECT_TRADE")) {
                if(offer.isOutOfStock())return DailyActionCommand.CommandResult.rejected("TRADE_DISABLED");menu.setSelectionHint(cmd.offerIndex());menu.tryMoveItems(cmd.offerIndex());menu.broadcastChanges();if(!offer.satisfiedBy(menu.getSlot(0).getItem(),menu.getSlot(1).getItem()))return DailyActionCommand.CommandResult.rejected("TRADE_RESOURCES_INSUFFICIENT");
                gateway.markVanillaMenuAction(body);
                return DailyActionCommand.CommandResult.success();
            }
            if(cmd.action().equals("EXECUTE_TRADE")){if(inventoryCapacity(body,offer.getResult())<offer.getResult().getCount())return DailyActionCommand.CommandResult.rejected("INVENTORY_FULL");menu.clicked(2,0,ClickType.QUICK_MOVE,body);menu.broadcastChanges();gateway.markVanillaMenuAction(body);return offer.getUses()>(f==null?offer.getUses():f.tradeUses)?DailyActionCommand.CommandResult.success():DailyActionCommand.CommandResult.uncertain("TRADE_NOT_VERIFIED");}
            return DailyActionCommand.CommandResult.success();
        }
        private DailyActionCommand.CommandResult enchant(DailyActionCommand.Enchant cmd) {
            if(cmd.action().equals("OPEN_ENCHANT")){DailyActionCommand.CommandResult opened=menuAction(cmd.action(),cmd.option(),cmd.station());Facts f=findFacts();if(opened.accepted()&&f!=null&&body.containerMenu instanceof EnchantmentMenu){f.enchantStation=cmd.station();f.enchantMenuId=body.containerMenu.containerId;}return opened;}
            if(!(body.containerMenu instanceof EnchantmentMenu menu))return DailyActionCommand.CommandResult.rejected("ENCHANTMENT_MENU_INVALIDATED");
            Facts f=findFacts();
            if(cmd.action().equals("SELECT_ENCHANT")){if(cmd.option()<0||cmd.option()>=menu.costs.length)return DailyActionCommand.CommandResult.rejected("ENCHANT_OPTION_INVALID");if(f!=null){f.enchantOption=cmd.option();f.enchantXp=body.experienceLevel;f.enchantLapis=totalLapis(body);}if(menu.getSlot(0).getItem().isEmpty()){Item requested=resolve(f==null?"":f.parameters.itemId());int source=findOpenMenuItemSlot(body,requested);if(source<0||!moveCount(body,source,0,1))return DailyActionCommand.CommandResult.rejected("ENCHANT_ITEM_MISSING");}if(menu.getSlot(1).getItem().isEmpty()){int source=findOpenMenuItemSlot(body,Items.LAPIS_LAZULI);if(source<0||!moveCount(body,source,1,cmd.option()+1))return DailyActionCommand.CommandResult.rejected("LAPIS_INSUFFICIENT");}if(f!=null)f.enchantDigest=stackDigest(menu.getSlot(0).getItem());menu.broadcastChanges();gateway.markVanillaMenuAction(body);return DailyActionCommand.CommandResult.success();}
            if(cmd.action().equals("APPLY_ENCHANT")){boolean accepted=menu.clickMenuButton(body,cmd.option());gateway.markVanillaMenuAction(body);return accepted?DailyActionCommand.CommandResult.success():DailyActionCommand.CommandResult.rejected("ENCHANT_OPTION_INVALID");}
            return DailyActionCommand.CommandResult.success();
        }
        private DailyActionCommand.CommandResult brew(DailyActionCommand.Brew cmd) {
            if(cmd.action().equals("OPEN_BREW")){DailyActionCommand.CommandResult opened=menuAction(cmd.action(),cmd.slot(),cmd.station());Facts f=findFacts();if(opened.accepted()&&f!=null&&body.containerMenu instanceof BrewingStandMenu){f.brewStation=cmd.station();f.brewMenuId=body.containerMenu.containerId;}return opened;}
            if(!(body.containerMenu instanceof BrewingStandMenu menu))return DailyActionCommand.CommandResult.rejected("BREWING_MENU_INVALIDATED");
            Facts f=findFacts();
            if(cmd.action().equals("LOAD_BREW")){int bottles=Math.max(1,Math.min(3,cmd.bottleCount()));if(f!=null)f.brewBottles=bottles;Map<Integer,String> initial=new HashMap<>();for(int slot=0;slot<bottles;slot++){if(menu.getSlot(slot).getItem().isEmpty()){int source=findOpenMenuItemSlot(body,Items.POTION);if(source<0||!moveCount(body,source,slot,1))return DailyActionCommand.CommandResult.rejected("BREWING_BOTTLES_MISSING");}initial.put(slot,stackDigest(menu.getSlot(slot).getItem()));}if(f!=null)f.brewInitial=Map.copyOf(initial);Item ingredient=resolve(cmd.itemId());int source=findOpenMenuItemSlot(body,ingredient);if(source<0||!moveCount(body,source,3,1))return DailyActionCommand.CommandResult.rejected("BREWING_INGREDIENT_MISSING");if(menu.getSlot(4).getItem().isEmpty()){source=findOpenMenuItemSlot(body,Items.BLAZE_POWDER);if(source<0||!moveCount(body,source,4,1))return DailyActionCommand.CommandResult.rejected("BREWING_FUEL_MISSING");}menu.broadcastChanges();gateway.markVanillaMenuAction(body);return DailyActionCommand.CommandResult.success();}
            if(cmd.action().equals("TAKE_BREW")){int bottles=f==null?Math.max(1,cmd.bottleCount()):f.brewBottles;Map<Integer,String> completed=new HashMap<>();for(int slot=0;slot<bottles;slot++){ItemStack result=menu.getSlot(slot).getItem();if(!result.isEmpty())completed.put(slot,stackDigest(result));}if(f!=null&&!f.brewCompleted.isEmpty()&&!f.brewCompleted.equals(completed))return DailyActionCommand.CommandResult.rejected("BREWING_CONTENT_CHANGED");completed.clear();int taken=0;for(int slot=0;slot<bottles;slot++){ItemStack result=menu.getSlot(slot).getItem();if(result.isEmpty())continue;String digest=stackDigest(result);completed.put(slot,digest);int before=countInventoryDigest(body,digest);menu.clicked(slot,0,ClickType.QUICK_MOVE,body);menu.broadcastChanges();if(menu.getSlot(slot).getItem().isEmpty()&&countInventoryDigest(body,digest)>before)taken++;}if(f!=null){f.brewCompleted=Map.copyOf(completed);f.brewTaken=taken;}gateway.markVanillaMenuAction(body);return taken>=bottles?DailyActionCommand.CommandResult.success():DailyActionCommand.CommandResult.uncertain("BREW_RESULTS_NOT_VERIFIED");}
            return DailyActionCommand.CommandResult.success();
        }
        private DailyActionCommand.CommandResult fishing(DailyActionCommand.Fishing cmd) {
            if (!ensureMainHand(Items.FISHING_ROD)) return DailyActionCommand.CommandResult.rejected("FISHING_ROD_MISSING");
            if (cmd.action().equals("CAST_LINE") || cmd.action().equals("REEL_LINE")) {
                if(cmd.action().equals("CAST_LINE")&&cmd.waterTarget()!=null)gateway.lookAt(body,Vec3.atCenterOf(new BlockPos(cmd.waterTarget().x(),cmd.waterTarget().y(),cmd.waterTarget().z())));
                Facts facts = findFacts();
                if (cmd.action().equals("CAST_LINE") && facts != null) {
                    facts.fishingRodDamage = body.getMainHandItem().getDamageValue();
                }
                var result = body.gameMode.useItem(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND);
                gateway.markVanillaGameModeAction(body);
                if (cmd.action().equals("CAST_LINE") && body.fishing != null && facts != null) {
                    facts.fishingHookId = body.fishing.getUUID().toString();
                    facts.fishingCastTick = server.getTickCount();
                    facts.fishingHookY = body.fishing.getY();
                    facts.fishingHookYObserved = true;
                }
                boolean verified = cmd.action().equals("CAST_LINE") ? body.fishing != null : body.fishing == null;
                if (cmd.action().equals("REEL_LINE") && verified && facts != null) {
                    facts.fishingReelVerified = true;
                }
                return result.consumesAction() && verified ? DailyActionCommand.CommandResult.success()
                        : DailyActionCommand.CommandResult.uncertain("FISH_USE_UNVERIFIED");
            }
            if (cmd.action().equals("VERIFY_LOOT")) {
                ItemEntity nearest = body.serverLevel().getEntitiesOfClass(ItemEntity.class,
                                body.getBoundingBox().inflate(8.0D), value -> value.isAlive() && isFish(value.getItem())).stream()
                        .min(java.util.Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
                if (nearest != null && nearest.distanceToSqr(body) <= 2.25D) {
                    gateway.stopInput(body);
                    nearest.playerTouch(body);
                }
            }
            return DailyActionCommand.CommandResult.success();
        }
        private DailyActionCommand.CommandResult glide(DailyActionCommand.Glide cmd) {
            if(cmd.action().equals("EQUIP_ELYTRA"))return equip(new DailyActionCommand.Equip("EQUIP",cmd.elytraItemId(),cmd.sourceSlot(),"CHEST",cmd.stackDigest()));
            if (cmd.action().equals("START_GLIDE") && !body.isFallFlying()) {if(!body.tryToStartFallFlying())return DailyActionCommand.CommandResult.rejected("GLIDE_START_UNSAFE");gateway.markVanillaGameModeAction(body);}
            if ((cmd.action().equals("GLIDE")||cmd.action().equals("LAND")) && cmd.target() != null) {
                Vec3 delta = Vec3.atCenterOf(new BlockPos(cmd.target().x(), cmd.target().y(), cmd.target().z())).subtract(body.position());
                gateway.lookAt(body,Vec3.atCenterOf(new BlockPos(cmd.target().x(),cmd.target().y(),cmd.target().z())));gateway.applyMoveInput(body,(float)Math.toDegrees(Math.atan2(-delta.x, delta.z)),false);
            }
            return DailyActionCommand.CommandResult.success();
        }

        private DailyActionCommand.CommandResult menuAction(String action, int slot, DailyActionRequest.Position station) {
            if (action.startsWith("OPEN_") && station != null) {
                BlockPos pos = new BlockPos(station.x(), station.y(), station.z());
                var result = body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                gateway.markVanillaGameModeAction(body);
                if (!result.consumesAction()) return DailyActionCommand.CommandResult.uncertain("MENU_OPEN_UNVERIFIED");
            }
            if (body.containerMenu == body.inventoryMenu) return DailyActionCommand.CommandResult.rejected("MENU_NOT_OPEN");
            if (action.startsWith("EXECUTE") || action.startsWith("SELECT") || action.startsWith("TAKE")) {
                if (slot < 0 || slot >= body.containerMenu.slots.size()) return DailyActionCommand.CommandResult.rejected("MENU_SLOT_INVALID");
                body.containerMenu.clicked(slot, 0, ClickType.PICKUP, body); gateway.markVanillaMenuAction(body);
            }
            return DailyActionCommand.CommandResult.success();
        }

        private boolean ensureMainHand(Item item){if(item==null)return false;if(body.getMainHandItem().is(item))return true;for(int slot=0;slot<36;slot++){ItemStack stack=body.getInventory().getItem(slot);if(stack.is(item))return equip(new DailyActionCommand.Equip("EQUIP",BuiltInRegistries.ITEM.getKey(item).toString(),slot,"MAIN_HAND",stackDigest(stack))).accepted();}return false;}
        private Facts findFacts() { return facts.get(body.getUUID()); }
        private Item resolve(String id) { var key = net.minecraft.resources.ResourceLocation.tryParse(id == null ? "" : id); return key == null ? null : BuiltInRegistries.ITEM.get(key); }
        private int firstEmptyInventoryMenuSlot() { for (int i=9;i<45;i++) if (body.inventoryMenu.getSlot(i).getItem().isEmpty()) return i; return -1; }
        private static int inventoryMenuSlot(int slot) { return slot < 9 ? 36 + slot : slot; }
        private static int equipmentMenuSlot(String slot) { return switch (slot == null ? "" : slot.toUpperCase(Locale.ROOT)) { case "HEAD" -> 5; case "CHEST" -> 6; case "LEGS" -> 7; case "FEET" -> 8; case "OFF_HAND" -> 45; default -> -1; }; }
        private static Direction direction(String value) { try { return Direction.valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { return Direction.UP; } }
    }

    private DailyActionCommand.CommandResult navigateShared(
            CompanionPlayer body, String key, Supplier<DailyNavigator.Goal> goal) {
        UUID id = body.getUUID();
        BodyNavigationPort port = navigationPorts.computeIfAbsent(id, ignored -> new BodyNavigationPort(body));
        port.body = body;
        DailyNavigator navigator = navigators.computeIfAbsent(id, ignored -> new DailyNavigator(port));
        if (!key.equals(navigationKeys.get(id))) {
            navigator.start(goal, server.getTickCount());
            navigationKeys.put(id, key);
        }
        DailyNavigator.NavigationResult result = navigator.tick(server.getTickCount());
        return switch (result.status()) {
            case RUNNING, PAUSED, ARRIVED -> DailyActionCommand.CommandResult.success();
            case TARGET_UNLOADED, BUDGET_EXCEEDED, UNREACHABLE, STUCK, TIMEOUT, WORLD_CHANGED, CANCELLED ->
                    DailyActionCommand.CommandResult.rejected("NAVIGATION_" + result.code());
            case IDLE -> DailyActionCommand.CommandResult.uncertain("NAVIGATION_IDLE");
        };
    }

    private void removeNavigator(UUID id) {
        DailyNavigator navigator = navigators.remove(id);
        if (navigator != null) navigator.cancel();
        navigationPorts.remove(id); navigationKeys.remove(id);
    }

    private final class BodyNavigationPort implements NavigationPort {
        private CompanionPlayer body;
        private BodyNavigationPort(CompanionPlayer body) { this.body = body; }
        @Override public Vec currentPosition() { Vec3 p=body.position(); return new Vec(p.x,p.y,p.z); }
        @Override public String worldKey() { return body.serverLevel().dimension().location().toString(); }
        @Override public com.mccompanion.core.navigation.GridPathPlanner.Plan plan(NavPoint from, NavPoint target) {
            return navigation.plan(body, new Vec3(target.x()+.5D,target.y(),target.z()+.5D));
        }
        @Override public boolean traversable(NavPoint from, NavPoint to) {
            return navigation.remainsTraversable(body, from.plannerPoint(), to.plannerPoint());
        }
        @Override public boolean openDoor(NavPoint point) {
            return !navigation.requiresPassageOpening(body, point.plannerPoint())
                    || navigation.openDoorIfNeeded(body, point.plannerPoint());
        }
        @Override public void applyMove(Vec direction, boolean jump) {
            gateway.applyMoveInput(body,(float)Math.toDegrees(Math.atan2(-direction.x(),direction.z())),jump||body.horizontalCollision);
        }
        @Override public void stop() { gateway.stopInput(body); }
    }

    private static void returnMenuInputs(CompanionPlayer body) {
        returnCarried(body);
        int[] slots = body.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu ? new int[]{0,1}
                : body.containerMenu instanceof net.minecraft.world.inventory.EnchantmentMenu ? new int[]{0,1}
                : body.containerMenu instanceof net.minecraft.world.inventory.BrewingStandMenu ? new int[]{0,1,2,3,4}
                : new int[0];
        for(int slot:slots) if(slot<body.containerMenu.slots.size()&&!body.containerMenu.getSlot(slot).getItem().isEmpty())
            body.containerMenu.clicked(slot,0,ClickType.QUICK_MOVE,body);
        returnCarried(body);
    }
    private static void returnCarried(CompanionPlayer body){if(body.containerMenu.getCarried().isEmpty())return;for(int index=0;index<body.containerMenu.slots.size();index++){Slot slot=body.containerMenu.getSlot(index);if(slot.container==body.getInventory()&&slot.getItem().isEmpty()){body.containerMenu.clicked(index,0,ClickType.PICKUP,body);break;}}if(!body.containerMenu.getCarried().isEmpty())throw new IllegalStateException("MENU_CURSOR_NOT_RECOVERED");}

    private static int inventoryTotal(CompanionPlayer body) {
        int total = 0; for (int i = 0; i < body.getInventory().getContainerSize(); i++) total += body.getInventory().getItem(i).getCount(); return total;
    }
    private static int fishCount(CompanionPlayer body){int total=0;for(int slot=0;slot<36;slot++){ItemStack stack=body.getInventory().getItem(slot);if(isFish(stack))total+=stack.getCount();}return total;}
    private static boolean isFish(ItemStack stack){return stack.is(Items.COD)||stack.is(Items.SALMON)||stack.is(Items.TROPICAL_FISH)||stack.is(Items.PUFFERFISH);}
}
