package com.mccompanion.minecraft.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Detects stable inventory thresholds and bounded task-relevant world changes. */
public final class InventoryWorldEventTracker {
    public static final int NEAR_FULL_FREE_SLOTS = 2;
    private static final int CAPACITY_CONFIRMATIONS = 2;

    private final Map<String, State> states = new HashMap<>();

    public List<Event> observe(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        State previous = states.get(snapshot.companionId());
        List<Event> events = new ArrayList<>();

        CapacityBand observedBand = capacityBand(snapshot);
        CapacityBand candidateBand = previous != null && previous.candidateBand == observedBand
                ? previous.candidateBand : observedBand;
        int candidateObservations = previous != null && previous.candidateBand == observedBand
                ? previous.candidateObservations + 1 : 1;
        CapacityBand stableBand = previous == null ? CapacityBand.NORMAL : previous.stableBand;
        if (candidateObservations >= CAPACITY_CONFIRMATIONS && stableBand != candidateBand) {
            CapacityBand oldStable = stableBand;
            stableBand = candidateBand;
            if (stableBand == CapacityBand.FULL) {
                events.add(event(snapshot, Type.INVENTORY_FULL,
                        Integer.toString(previousFree(previous, snapshot)),
                        Integer.toString(snapshot.freeInventorySlots())));
            } else if (stableBand == CapacityBand.NEAR_FULL && oldStable == CapacityBand.NORMAL) {
                events.add(event(snapshot, Type.INVENTORY_NEAR_FULL,
                        Integer.toString(previousFree(previous, snapshot)),
                        Integer.toString(snapshot.freeInventorySlots())));
            }
        }

        ResourceGoal goal = snapshot.resourceGoal();
        boolean sameGoal = previous != null && Objects.equals(previous.behaviorId, snapshot.behaviorId())
                && Objects.equals(previous.goal, goal);
        int currentGoalCount = goal == null ? 0 : snapshot.inventory().getOrDefault(goal.itemId(), 0);
        if (goal != null) {
            if (!sameGoal) {
                if (currentGoalCount >= goal.requiredCount()) {
                    events.add(event(snapshot, Type.RESOURCE_TARGET_REACHED,
                            "0", Integer.toString(currentGoalCount)));
                } else {
                    events.add(event(snapshot, Type.KEY_ITEM_INSUFFICIENT,
                            Integer.toString(currentGoalCount), Integer.toString(goal.requiredCount())));
                }
            } else {
                int previousGoalCount = previous.goalCount;
                if (previousGoalCount == 0 && currentGoalCount > 0) {
                    events.add(event(snapshot, Type.KEY_ITEM_ACQUIRED,
                            "0", Integer.toString(currentGoalCount)));
                }
                if (previousGoalCount >= goal.requiredCount()
                        && currentGoalCount < goal.requiredCount()) {
                    events.add(event(snapshot, Type.KEY_ITEM_INSUFFICIENT,
                            Integer.toString(currentGoalCount), Integer.toString(goal.requiredCount())));
                } else if (previousGoalCount < goal.requiredCount()
                        && currentGoalCount >= goal.requiredCount()) {
                    events.add(event(snapshot, Type.RESOURCE_TARGET_REACHED,
                            Integer.toString(previousGoalCount), Integer.toString(currentGoalCount)));
                }
            }
        }

        if (previous != null) {
            if (!previous.dimension.equals(snapshot.dimension())) {
                events.add(event(snapshot, Type.DIMENSION_CHANGED,
                        previous.dimension, snapshot.dimension()));
            }
            if (previous.timeOfDay != snapshot.timeOfDay()) {
                events.add(event(snapshot, Type.DAY_NIGHT_CHANGED,
                        previous.timeOfDay.name(), snapshot.timeOfDay().name()));
            }
            if (previous.weather != snapshot.weather()) {
                events.add(event(snapshot, Type.WEATHER_CHANGED,
                        previous.weather.name(), snapshot.weather().name()));
            }
            if (snapshot.behaviorId() != null
                    && snapshot.behaviorId().equals(previous.behaviorId)) {
                observeTarget(events, snapshot, previous.target);
            }
        }

        states.put(snapshot.companionId(), new State(snapshot.behaviorId(), goal, currentGoalCount,
                snapshot.freeInventorySlots(), stableBand, candidateBand, candidateObservations,
                snapshot.dimension(), snapshot.timeOfDay(), snapshot.weather(), snapshot.target()));
        return List.copyOf(events);
    }

    public void retainCompanions(Set<String> companionIds) {
        states.keySet().retainAll(Set.copyOf(companionIds));
    }

    public void clear() {
        states.clear();
    }

    private static void observeTarget(List<Event> events, Snapshot snapshot, Target previous) {
        Target current = snapshot.target();
        if (previous == null && current == null) return;
        if (previous == null || current == null
                || !previous.identity().equals(current.identity())
                || previous.kind() != current.kind()
                || previous.present() != current.present()) {
            events.add(event(snapshot, Type.TASK_TARGET_CHANGED,
                    previous == null ? "NONE" : previous.summary(),
                    current == null ? "NONE" : current.summary()));
            return;
        }
        if (!previous.blockFingerprint().equals(current.blockFingerprint())) {
            events.add(event(snapshot, Type.TARGET_BLOCK_CHANGED,
                    previous.blockFingerprint(), current.blockFingerprint()));
        }
        if (current.kind() == TargetKind.CONTAINER
                && !previous.containerFingerprint().equals(current.containerFingerprint())) {
            events.add(event(snapshot, Type.TARGET_CONTAINER_CHANGED,
                    previous.containerFingerprint(), current.containerFingerprint()));
        }
    }

    private static CapacityBand capacityBand(Snapshot snapshot) {
        if (snapshot.freeInventorySlots() == 0) return CapacityBand.FULL;
        if (snapshot.freeInventorySlots() <= Math.min(NEAR_FULL_FREE_SLOTS,
                snapshot.inventorySlots() - 1)) return CapacityBand.NEAR_FULL;
        return CapacityBand.NORMAL;
    }

    private static int previousFree(State previous, Snapshot snapshot) {
        return previous == null ? snapshot.freeInventorySlots() : previous.freeInventorySlots;
    }

    private static Event event(Snapshot snapshot, Type type, String previousValue, String currentValue) {
        return new Event(snapshot.companionId() + ':' + type.name() + ':' + snapshot.tick(),
                type, type.priority(), snapshot, previousValue, currentValue);
    }

    public record Snapshot(String companionId, String behaviorId, long tick, Instant observedAt,
                           int inventorySlots, int freeInventorySlots,
                           Map<String, Integer> inventory, ResourceGoal resourceGoal,
                           String dimension, TimeOfDay timeOfDay, Weather weather, Target target) {
        public Snapshot {
            companionId = required(companionId, "companionId");
            behaviorId = optional(behaviorId);
            if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
            Objects.requireNonNull(observedAt, "observedAt");
            if (inventorySlots < 1 || inventorySlots > 256
                    || freeInventorySlots < 0 || freeInventorySlots > inventorySlots) {
                throw new IllegalArgumentException("inventory slot counts are invalid");
            }
            Map<String, Integer> bounded = new java.util.TreeMap<>();
            Objects.requireNonNull(inventory, "inventory").forEach((item, count) -> {
                String id = required(item, "inventory item");
                if (count == null || count < 1 || count > 1_000_000) {
                    throw new IllegalArgumentException("inventory count is invalid");
                }
                bounded.put(id, count);
            });
            inventory = Map.copyOf(bounded);
            dimension = required(dimension, "dimension");
            Objects.requireNonNull(timeOfDay, "timeOfDay");
            Objects.requireNonNull(weather, "weather");
        }

        @Override public Map<String, Integer> inventory() { return Map.copyOf(inventory); }
    }

    public record ResourceGoal(String itemId, int requiredCount) {
        public ResourceGoal {
            itemId = required(itemId, "itemId");
            if (requiredCount < 1 || requiredCount > 1_000_000) {
                throw new IllegalArgumentException("requiredCount is invalid");
            }
        }
    }

    public record Target(String identity, TargetKind kind, boolean present, String blockId,
                         String blockFingerprint, String containerType,
                         String containerFingerprint) {
        public Target {
            identity = required(identity, "target identity");
            Objects.requireNonNull(kind, "kind");
            blockId = optional(blockId);
            blockFingerprint = normalized(blockFingerprint);
            containerType = optional(containerType);
            containerFingerprint = normalized(containerFingerprint);
        }

        String summary() {
            return identity + ':' + kind + ':' + present + ':' + (blockId == null ? "" : blockId);
        }
    }

    public record Event(String eventId, Type type, Priority priority, Snapshot snapshot,
                        String previousValue, String currentValue) { }

    public enum TimeOfDay { DAY, NIGHT }
    public enum Weather { CLEAR, RAIN, THUNDER }
    public enum TargetKind { BLOCK, CONTAINER }
    public enum Priority { MEDIUM, HIGH, CRITICAL }

    public enum Type {
        INVENTORY_NEAR_FULL(Priority.HIGH),
        INVENTORY_FULL(Priority.CRITICAL),
        KEY_ITEM_ACQUIRED(Priority.HIGH),
        KEY_ITEM_INSUFFICIENT(Priority.HIGH),
        RESOURCE_TARGET_REACHED(Priority.HIGH),
        DIMENSION_CHANGED(Priority.HIGH),
        DAY_NIGHT_CHANGED(Priority.MEDIUM),
        WEATHER_CHANGED(Priority.MEDIUM),
        TASK_TARGET_CHANGED(Priority.HIGH),
        TARGET_CONTAINER_CHANGED(Priority.HIGH),
        TARGET_BLOCK_CHANGED(Priority.HIGH);

        private final Priority priority;
        Type(Priority priority) { this.priority = priority; }
        public Priority priority() { return priority; }
    }

    private enum CapacityBand { NORMAL, NEAR_FULL, FULL }

    private record State(String behaviorId, ResourceGoal goal, int goalCount,
                         int freeInventorySlots, CapacityBand stableBand,
                         CapacityBand candidateBand, int candidateObservations,
                         String dimension, TimeOfDay timeOfDay, Weather weather, Target target) { }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String result = value.strip();
        if (result.length() > 512) throw new IllegalArgumentException(field + " is too long");
        return result;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : required(value, "optional value");
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return "";
        String result = value.strip();
        return result.length() <= 2_048 ? result : result.substring(0, 2_048);
    }
}
