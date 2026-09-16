package com.mccompanion.core.body;

/** Immutable facts exported by version bindings and consumed by shared behavior/Bridge code. */
public final class BodySnapshots {
    private BodySnapshots() { }

    public record RuntimeSnapshot(
            String companionId, String ownerId, String displayName, String dimension,
            double x, double y, double z, String bodyState, String behaviorId,
            String behaviorState, long behaviorRevision, long controlEpoch, boolean runtimeConnected,
            float health, float maxHealth, int foodLevel, int airSupply, boolean onFire, boolean inLava,
            int freeInventorySlots, java.util.Map<String, Integer> inventory,
            java.util.List<ContainerSnapshot> visibleContainers, String evidenceSummary,
            BehaviorObservation behaviorObservation,
            java.util.Map<String, String> equipment, java.util.Map<String, String> vehicle,
            java.util.Map<String, String> menu, java.util.Map<String, String> sleep,
            java.util.Map<String, String> fish, java.util.Map<String, String> glide,
            java.util.Map<String, String> bucket, java.util.Map<String, String> crop,
            java.util.Map<String, String> breed, java.util.Map<String, String> trade,
            java.util.Map<String, String> enchant, java.util.Map<String, String> brew) {

        public RuntimeSnapshot(
            String companionId, String ownerId, String displayName, String dimension,
            double x, double y, double z, String bodyState, String behaviorId,
            String behaviorState, long behaviorRevision, long controlEpoch, boolean runtimeConnected,
            float health, float maxHealth, int foodLevel, int airSupply, boolean onFire, boolean inLava,
            int freeInventorySlots, java.util.Map<String, Integer> inventory,
            String evidenceSummary,
            BehaviorObservation behaviorObservation,
            java.util.Map<String, String> equipment, java.util.Map<String, String> vehicle,
            java.util.Map<String, String> menu, java.util.Map<String, String> sleep,
            java.util.Map<String, String> fish, java.util.Map<String, String> glide,
            java.util.Map<String, String> bucket, java.util.Map<String, String> crop,
            java.util.Map<String, String> breed, java.util.Map<String, String> trade,
            java.util.Map<String, String> enchant, java.util.Map<String, String> brew) {
            this(companionId, ownerId, displayName, dimension, x, y, z, bodyState, behaviorId, behaviorState, behaviorRevision, controlEpoch, runtimeConnected, health, maxHealth, foodLevel, airSupply, onFire, inLava, freeInventorySlots, inventory, java.util.List.of(), evidenceSummary, behaviorObservation, equipment, vehicle, menu, sleep, fish, glide, bucket, crop, breed, trade, enchant, brew);
        }
        public RuntimeSnapshot {
            inventory = java.util.Map.copyOf(inventory);
            visibleContainers = java.util.List.copyOf(visibleContainers);
            equipment = equipment == null ? java.util.Map.of() : java.util.Map.copyOf(equipment);
            vehicle = vehicle == null ? java.util.Map.of() : java.util.Map.copyOf(vehicle);
            menu = menu == null ? java.util.Map.of() : java.util.Map.copyOf(menu);
            sleep = sleep == null ? java.util.Map.of() : java.util.Map.copyOf(sleep);
            fish = fish == null ? java.util.Map.of() : java.util.Map.copyOf(fish);
            glide = glide == null ? java.util.Map.of() : java.util.Map.copyOf(glide);
            bucket = bucket == null ? java.util.Map.of() : java.util.Map.copyOf(bucket);
            crop = crop == null ? java.util.Map.of() : java.util.Map.copyOf(crop);
            breed = breed == null ? java.util.Map.of() : java.util.Map.copyOf(breed);
            trade = trade == null ? java.util.Map.of() : java.util.Map.copyOf(trade);
            enchant = enchant == null ? java.util.Map.of() : java.util.Map.copyOf(enchant);
            brew = brew == null ? java.util.Map.of() : java.util.Map.copyOf(brew);
        }
    }

    public record BehaviorObservation(String failureCode, String itemId, int requested, int available,
                                      java.util.List<ScanCandidate> candidates,
                                      java.util.Map<String, String> details) {
        public BehaviorObservation {
            candidates = candidates == null ? java.util.List.of() : java.util.List.copyOf(candidates);
            details = details == null ? java.util.Map.of() : java.util.Map.copyOf(details);
        }
        public BehaviorObservation(String failureCode, String itemId, int requested, int available,
                                   java.util.List<ScanCandidate> candidates) {
            this(failureCode, itemId, requested, available, candidates, java.util.Map.of());
        }
        public BehaviorObservation(String failureCode, String itemId, int requested, int available) {
            this(failureCode, itemId, requested, available, java.util.List.of(), java.util.Map.of());
        }
    }

    public record ScanCandidate(String block, String dimension, int x, int y, int z, double distanceSquared) { }

    public record ContainerSnapshot(String type, String dimension, int x, int y, int z) { }
}
