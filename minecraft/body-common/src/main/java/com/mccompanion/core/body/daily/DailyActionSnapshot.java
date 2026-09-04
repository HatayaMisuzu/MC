package com.mccompanion.core.body.daily;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable, loader-neutral facts used by DailyActionEngine postcondition checks. */
public record DailyActionSnapshot(
        long tick,
        boolean alive,
        String dimension,
        DailyActionRequest.Position position,
        Map<String, ItemFact> inventory,
        Map<String, ItemFact> equipment,
        BedFact bed,
        BucketFact bucket,
        VehicleFact vehicle,
        FishFact fish,
        CropFact crop,
        BreedFact breed,
        MenuFact menu,
        int experienceLevel,
        boolean sleeping,
        boolean onGround,
        boolean fallFlying,
        List<ItemCandidate> itemCandidates,
        List<BedCandidate> bedCandidates,
        List<VehicleCandidate> vehicleCandidates,
        List<CropCandidate> cropCandidates,
        List<AnimalCandidate> animalCandidates,
        List<EntityCandidate> entityCandidates,
        TradeFact trade,
        EnchantFact enchant,
        BrewFact brew,
        GlideFact glide) {

    public DailyActionSnapshot {
        dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        inventory = inventory == null ? Map.of() : Map.copyOf(inventory);
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
        itemCandidates = itemCandidates == null ? List.of() : List.copyOf(itemCandidates);
        bedCandidates = bedCandidates == null ? List.of() : List.copyOf(bedCandidates);
        vehicleCandidates = vehicleCandidates == null ? List.of() : List.copyOf(vehicleCandidates);
        cropCandidates = cropCandidates == null ? List.of() : List.copyOf(cropCandidates);
        animalCandidates = animalCandidates == null ? List.of() : List.copyOf(animalCandidates);
        entityCandidates = entityCandidates == null ? List.of() : List.copyOf(entityCandidates);
    }

    /** Compatibility constructor for adapters that have not migrated from singleton facts yet. */
    public DailyActionSnapshot(long tick, boolean alive, String dimension,
                               DailyActionRequest.Position position, Map<String, ItemFact> inventory,
                               Map<String, ItemFact> equipment, BedFact bed, BucketFact bucket,
                               VehicleFact vehicle, FishFact fish, CropFact crop, BreedFact breed,
                               MenuFact menu, int experienceLevel, boolean sleeping, boolean onGround,
                               boolean fallFlying) {
        this(tick, alive, dimension, position, inventory, equipment, bed, bucket, vehicle, fish, crop,
                breed, menu, experienceLevel, sleeping, onGround, fallFlying,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null);
    }

    /** Compatibility constructor for candidate-aware adapters written before glide facts landed. */
    public DailyActionSnapshot(long tick, boolean alive, String dimension,
                               DailyActionRequest.Position position, Map<String, ItemFact> inventory,
                               Map<String, ItemFact> equipment, BedFact bed, BucketFact bucket,
                               VehicleFact vehicle, FishFact fish, CropFact crop, BreedFact breed,
                               MenuFact menu, int experienceLevel, boolean sleeping, boolean onGround,
                               boolean fallFlying, List<ItemCandidate> itemCandidates,
                               List<BedCandidate> bedCandidates, List<VehicleCandidate> vehicleCandidates,
                               List<CropCandidate> cropCandidates, List<AnimalCandidate> animalCandidates) {
        this(tick, alive, dimension, position, inventory, equipment, bed, bucket, vehicle, fish, crop,
                breed, menu, experienceLevel, sleeping, onGround, fallFlying, itemCandidates,
                bedCandidates, vehicleCandidates, cropCandidates, animalCandidates, List.of(),
                null, null, null, null);
    }

    public int count(String itemId) {
        ItemFact fact = inventory.get(itemId == null ? "" : itemId);
        return fact == null ? 0 : fact.count();
    }

    public record ItemFact(String itemId, int count, int damage, int maxDamage, Map<String, String> components) {
        public ItemFact(int count, int damage, int maxDamage, Map<String, String> components) {
            this("", count, damage, maxDamage, components);
        }
        public ItemFact {
            itemId = itemId == null ? "" : itemId;
            if (count < 0 || damage < 0 || maxDamage < 0) throw new IllegalArgumentException("negative item fact");
            components = components == null ? Map.of() : Map.copyOf(components);
        }
    }

    public record ItemCandidate(int slot, String itemId, int count, int damage, int maxDamage,
                                boolean correctTool, double destroySpeed, double attackDamage,
                                Map<String, String> traits) {
        public ItemCandidate {
            itemId = itemId == null ? "" : itemId;
            traits = traits == null ? Map.of() : Map.copyOf(traits);
        }
        public boolean usable() { return count > 0 && (maxDamage <= 0 || damage < maxDamage - 1); }
    }

    public record BedFact(boolean found, DailyActionRequest.Position position, boolean usable, String problem) { }

    public record BedCandidate(DailyActionRequest.Position position, boolean occupied,
                               boolean usable, String problem) { }

    public record BucketFact(boolean targetChanged, String targetBlock, int emptyBuckets, int waterBuckets,
                             boolean allowed, boolean sourceValid, boolean placementValid, String problem,
                             DailyActionRequest.Position target, String action, String direction) {
        public BucketFact(boolean targetChanged, String targetBlock, int emptyBuckets, int waterBuckets) {
            this(targetChanged, targetBlock, emptyBuckets, waterBuckets, true, true, true, "",
                    null, "", "");
        }

        public BucketFact(boolean targetChanged, String targetBlock, int emptyBuckets, int waterBuckets,
                          boolean allowed, boolean sourceValid, boolean placementValid, String problem) {
            this(targetChanged, targetBlock, emptyBuckets, waterBuckets, allowed, sourceValid,
                    placementValid, problem, null, "", "");
        }

        public BucketFact {
            targetBlock = targetBlock == null ? "" : targetBlock;
            problem = problem == null ? "" : problem;
            action = action == null ? "" : action;
            direction = direction == null ? "" : direction;
        }
    }

    public record VehicleFact(boolean found, UUID id, String type, DailyActionRequest.Position position,
                              boolean seated, boolean stuck, boolean safe) { }

    public record VehicleCandidate(UUID id, String type, DailyActionRequest.Position position,
                                   boolean alive) { }

    public record FishFact(boolean rodEquipped, boolean bobberCast, boolean biting,
                           boolean hookAlive, int lootCount, boolean lootVerified,
                           String hookId, String rodDigest, int rodDamageDelta) {
        public FishFact(boolean rodEquipped, boolean bobberCast, boolean biting,
                        boolean hookAlive, int lootCount, boolean lootVerified) {
            this(rodEquipped, bobberCast, biting, hookAlive, lootCount, lootVerified,
                    "", "", 0);
        }

        public FishFact {
            hookId = hookId == null ? "" : hookId;
            rodDigest = rodDigest == null ? "" : rodDigest;
        }
    }

    public record CropFact(boolean found, DailyActionRequest.Position position, boolean mature,
                           boolean harvested, boolean pickedUp, boolean replanted, int age,
                           String seedItem, int inventoryDelta, String cropId,
                           String harvestItem, int harvestedCount, int seedConsumed) {
        public CropFact(boolean found, DailyActionRequest.Position position, boolean mature,
                        boolean harvested, boolean pickedUp, boolean replanted, int age,
                        String seedItem, int inventoryDelta) {
            this(found, position, mature, harvested, pickedUp, replanted, age, seedItem,
                    inventoryDelta, "", "", inventoryDelta, replanted ? 1 : 0);
        }

        public CropFact {
            seedItem = seedItem == null ? "" : seedItem;
            cropId = cropId == null ? "" : cropId;
            harvestItem = harvestItem == null ? "" : harvestItem;
        }
    }

    public record CropCandidate(DailyActionRequest.Position position, String blockId,
                                boolean mature, String seedItem) {
        public CropCandidate(DailyActionRequest.Position position, boolean mature, String seedItem) {
            this(position, "", mature, seedItem);
        }

        public CropCandidate {
            blockId = blockId == null ? "" : blockId;
            seedItem = seedItem == null ? "" : seedItem;
        }
    }

    public record BreedFact(boolean firstFed, boolean secondFed, boolean babyVerified,
                            int babyCount, UUID firstId, UUID secondId, int foodConsumed) {
        public BreedFact(boolean firstFed, boolean secondFed, boolean babyVerified,
                         int babyCount, UUID firstId, UUID secondId) {
            this(firstFed, secondFed, babyVerified, babyCount, firstId, secondId,
                    firstFed && secondFed ? 2 : firstFed || secondFed ? 1 : 0);
        }
    }

    public record AnimalCandidate(UUID id, String type, DailyActionRequest.Position position,
                                  String foodItem, boolean foodCompatible, boolean adult,
                                  boolean inLove, boolean alive) {
        public AnimalCandidate(UUID id, String type, DailyActionRequest.Position position,
                               boolean foodCompatible, boolean adult, boolean inLove, boolean alive) {
            this(id, type, position, "", foodCompatible, adult, inLove, alive);
        }

        public AnimalCandidate {
            type = type == null ? "" : type;
            foodItem = foodItem == null ? "" : foodItem;
        }
    }

    /** Nearby entity target used by daily menu interactions such as villager trading. */
    public record EntityCandidate(UUID id, String type, DailyActionRequest.Position position,
                                  boolean alive, boolean visible) {
        public EntityCandidate {
            type = type == null ? "" : type;
        }
    }

    public record TradeFact(boolean valid, String villagerId, int offerIndex, boolean disabled,
                            int uses, String inputA, String inputB, String output,
                            boolean resourcesAvailable, boolean inventorySpace, boolean inputsReady,
                            int inputAConsumed, int inputBConsumed, int outputReceived,
                            int inputACount, int inputBCount, int outputCount) {
        public TradeFact(boolean valid, String villagerId, int offerIndex, boolean disabled,
                         int uses, String inputA, String inputB, String output,
                         boolean resourcesAvailable, boolean inventorySpace, boolean inputsReady,
                         int inputAConsumed, int inputBConsumed, int outputReceived) {
            this(valid, villagerId, offerIndex, disabled, uses, inputA, inputB, output,
                    resourcesAvailable, inventorySpace, inputsReady, inputAConsumed, inputBConsumed,
                    outputReceived, inputA == null || inputA.isBlank() ? 0 : 1,
                    inputB == null || inputB.isBlank() ? 0 : 1,
                    output == null || output.isBlank() ? 0 : 1);
        }
        public TradeFact {
            villagerId = villagerId == null ? "" : villagerId;
            inputA = inputA == null ? "" : inputA;
            inputB = inputB == null ? "" : inputB;
            output = output == null ? "" : output;
        }
    }

    public record EnchantFact(boolean valid, DailyActionRequest.Position station, String itemId,
                              int option, int cost, int lapisAvailable, boolean inputsReady,
                              String itemDigest, int levelsSpent, int lapisSpent) {
        public EnchantFact {
            itemId = itemId == null ? "" : itemId;
            itemDigest = itemDigest == null ? "" : itemDigest;
        }
    }

    public record BrewFact(boolean valid, DailyActionRequest.Position station, String ingredientId,
                           int bottleCount, int fuel, boolean materialsAvailable,
                           boolean fuelAvailable, boolean inputsReady, int brewingTicks,
                           Map<Integer, String> bottleDigests, int resultsTaken,
                           int inventoryResultDelta) {
        public BrewFact {
            ingredientId = ingredientId == null ? "" : ingredientId;
            bottleDigests = bottleDigests == null ? Map.of() : Map.copyOf(bottleDigests);
        }

        public String digest() {
            return bottleDigests.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(";"));
        }
    }

    /** Continuous glide facts used to prevent a one-tick start from being reported as travel. */
    public record GlideFact(boolean safeToStart, boolean targetReached, boolean terrainSafe,
                            double distanceSquared, double bestDistanceSquared, String problem) {
        public GlideFact {
            problem = problem == null ? "" : problem;
        }
    }

    public record MenuFact(boolean open, String type, int offerIndex, boolean offerDisabled,
                           String offerInputA, String offerInputB, String offerOutput,
                           int offerUses, int offerMaxUses, int enchantCost, int lapisCount,
                           boolean enchantChanged, int brewingTicks, boolean brewResultReady,
                           int resultCount, Map<String, String> details) {
        public MenuFact {
            type = type == null ? "" : type;
            offerInputA = offerInputA == null ? "" : offerInputA;
            offerInputB = offerInputB == null ? "" : offerInputB;
            offerOutput = offerOutput == null ? "" : offerOutput;
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
