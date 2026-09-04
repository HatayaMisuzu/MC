package com.mccompanion.minecraft.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stateful edge detector for bounded, server-observed player and entity facts.
 * Minecraft-version adapters provide facts; this class only turns state changes into events.
 */
public final class EntityEventTracker {
    private final Map<String, State> states = new HashMap<>();

    public List<Event> observe(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        State previous = states.get(snapshot.companionId());
        State current = State.from(snapshot);
        List<Event> events = new ArrayList<>();

        if (previous != null) {
            difference(current.players, previous.players).forEach(id ->
                    events.add(event(snapshot, Type.PLAYER_ENTERED_RANGE, current.players.get(id))));
            difference(previous.players, current.players).forEach(id ->
                    events.add(event(snapshot, Type.PLAYER_LEFT_RANGE, previous.players.get(id))));
        }

        Set<String> previousHostiles = previous == null ? Set.of() : previous.hostiles.keySet();
        difference(current.hostiles, previousHostiles).forEach(id ->
                events.add(event(snapshot, Type.HOSTILE_ENTERED_THREAT_RANGE, current.hostiles.get(id))));

        TargetFact priorTarget = previous == null ? null : previous.target;
        Event targetEvent = targetTransition(snapshot, priorTarget, current.target);
        if (targetEvent != null) events.add(targetEvent);

        states.put(snapshot.companionId(), current);
        return List.copyOf(events);
    }

    public void retainCompanions(Set<String> companionIds) {
        states.keySet().retainAll(Set.copyOf(companionIds));
    }

    public void clear() {
        states.clear();
    }

    private static Event targetTransition(Snapshot snapshot, TargetFact previous, TargetFact current) {
        if (current == null) return null;
        boolean sameTarget = previous != null
                && previous.identity().equals(current.identity())
                && previous.kind() == current.kind();
        if (sameTarget && previous.state() == current.state()) return null;

        if (current.state() == TargetState.IN_RANGE) {
            if (sameTarget && previous.state() != TargetState.IN_RANGE) {
                return event(snapshot, Type.TARGET_REAPPEARED, current.asEntity());
            }
            return null;
        }
        if (current.state() == TargetState.DEAD && current.kind() == TargetKind.CURRENT) {
            return event(snapshot, Type.CURRENT_TARGET_DIED, current.asEntity());
        }
        if (current.state() == TargetState.MISSING && current.kind() == TargetKind.CURRENT) {
            return event(snapshot, Type.CURRENT_TARGET_DISAPPEARED, current.asEntity());
        }
        Type lost = current.kind() == TargetKind.FOLLOW
                ? Type.FOLLOW_TARGET_LOST : Type.CURRENT_TARGET_LOST;
        return event(snapshot, lost, current.asEntity());
    }

    private static Event event(Snapshot snapshot, Type type, EntityFact target) {
        String identity = snapshot.companionId() + ':' + type.name() + ':'
                + target.identity() + ':' + snapshot.tick();
        return new Event(identity, type, type.priority(), snapshot.companionId(),
                snapshot.behaviorId(), snapshot.tick(), snapshot.observedAt(), target);
    }

    private static Set<String> difference(Map<String, EntityFact> left, Map<String, EntityFact> right) {
        Set<String> result = new LinkedHashSet<>(left.keySet());
        result.removeAll(right.keySet());
        return result;
    }

    private static Set<String> difference(Map<String, EntityFact> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left.keySet());
        result.removeAll(right);
        return result;
    }

    public record Snapshot(String companionId, String behaviorId, long tick, Instant observedAt,
                           List<EntityFact> playersInRange, List<EntityFact> hostilesInThreatRange,
                           TargetFact target) {
        public Snapshot {
            companionId = required(companionId, "companionId");
            behaviorId = optional(behaviorId);
            if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
            Objects.requireNonNull(observedAt, "observedAt");
            playersInRange = copy(playersInRange);
            hostilesInThreatRange = copy(hostilesInThreatRange);
        }
    }

    public record EntityFact(String identity, String type, String displayName,
                             boolean player, boolean hostile, boolean alive,
                             double distanceSquared) {
        public EntityFact {
            identity = required(identity, "identity");
            type = required(type, "type");
            displayName = optional(displayName);
            if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
                throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
            }
        }
    }

    public record TargetBinding(String identity, TargetKind kind) {
        public TargetBinding {
            identity = required(identity, "identity");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record TargetFact(String identity, String type, String displayName, TargetKind kind,
                             TargetState state, boolean player, boolean hostile, boolean alive,
                             double distanceSquared) {
        public TargetFact {
            identity = required(identity, "identity");
            type = required(type, "type");
            displayName = optional(displayName);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(state, "state");
            if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
                throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
            }
        }

        EntityFact asEntity() {
            return new EntityFact(identity, type, displayName, player, hostile, alive, distanceSquared);
        }
    }

    public record Event(String eventId, Type type, Priority priority, String companionId,
                        String behaviorId, long tick, Instant occurredAt, EntityFact target) {
    }

    public enum TargetKind { FOLLOW, CURRENT }

    public enum TargetState { IN_RANGE, OUT_OF_RANGE, MISSING, DEAD }

    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Type {
        PLAYER_ENTERED_RANGE(Priority.MEDIUM),
        PLAYER_LEFT_RANGE(Priority.MEDIUM),
        FOLLOW_TARGET_LOST(Priority.HIGH),
        CURRENT_TARGET_LOST(Priority.HIGH),
        TARGET_REAPPEARED(Priority.HIGH),
        HOSTILE_ENTERED_THREAT_RANGE(Priority.CRITICAL),
        CURRENT_TARGET_DIED(Priority.CRITICAL),
        CURRENT_TARGET_DISAPPEARED(Priority.HIGH);

        private final Priority priority;

        Type(Priority priority) { this.priority = priority; }

        public Priority priority() { return priority; }
    }

    private record State(Map<String, EntityFact> players, Map<String, EntityFact> hostiles,
                         TargetFact target) {
        static State from(Snapshot snapshot) {
            return new State(index(snapshot.playersInRange()), index(snapshot.hostilesInThreatRange()),
                    snapshot.target());
        }

        private static Map<String, EntityFact> index(List<EntityFact> facts) {
            Map<String, EntityFact> values = new LinkedHashMap<>();
            for (EntityFact fact : facts) values.put(fact.identity(), fact);
            return Map.copyOf(values);
        }
    }

    private static List<EntityFact> copy(List<EntityFact> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
