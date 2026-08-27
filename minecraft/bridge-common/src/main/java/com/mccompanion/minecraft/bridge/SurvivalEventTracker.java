package com.mccompanion.minecraft.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Turns bounded real-player vital and lifecycle observations into meaningful state edges. */
public final class SurvivalEventTracker {
    public static final double LOW_HEALTH_ENTER_RATIO = 0.30D;
    public static final double LOW_HEALTH_RECOVER_RATIO = 0.50D;
    public static final int LOW_AIR_ENTER = 40;
    public static final int LOW_AIR_RECOVER = 80;
    public static final float FALL_DANGER_ENTER = 6.0F;

    private final Map<String, State> states = new HashMap<>();

    public List<Event> observe(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        State previous = states.get(snapshot.companionId());
        List<Event> events = new ArrayList<>();

        if (snapshot.lifecycle() == Lifecycle.DEAD) {
            if (previous == null || previous.lifecycle != Lifecycle.DEAD) {
                events.add(event(snapshot, Type.DEATH, previous == null ? snapshot.health() : previous.health));
            }
            states.put(snapshot.companionId(), State.from(snapshot, false, false, false));
            return List.copyOf(events);
        }
        if (snapshot.lifecycle() == Lifecycle.SLEEPING) {
            states.put(snapshot.companionId(), State.from(snapshot, false, false, false));
            return List.of();
        }
        if (previous != null && previous.lifecycle == Lifecycle.DEAD) {
            events.add(event(snapshot, Type.RESPAWN, previous.health));
            states.put(snapshot.companionId(), State.from(snapshot,
                    lowHealth(snapshot), lowAir(snapshot), fallDanger(snapshot)));
            return List.copyOf(events);
        }

        boolean wasActive = previous != null && previous.lifecycle == Lifecycle.ACTIVE;
        boolean previousLowHealth = wasActive && previous.lowHealth;
        boolean currentLowHealth = previousLowHealth
                ? snapshot.health() < snapshot.maxHealth() * LOW_HEALTH_RECOVER_RATIO
                : lowHealth(snapshot);
        boolean previousLowAir = wasActive && previous.lowAir;
        boolean currentLowAir = previousLowAir
                ? snapshot.air() < Math.min(LOW_AIR_RECOVER, snapshot.maxAir()) : lowAir(snapshot);
        boolean previousFallDanger = wasActive && previous.fallDanger;
        boolean currentFallDanger = fallDanger(snapshot);

        if (wasActive && snapshot.health() + 0.001F < previous.health && snapshot.health() > 0.0F) {
            events.add(event(snapshot, Type.DAMAGE, previous.health));
        }
        if (currentLowHealth && !previousLowHealth) {
            events.add(event(snapshot, Type.LOW_HEALTH, wasActive ? previous.health : snapshot.health()));
        } else if (!currentLowHealth && previousLowHealth) {
            events.add(event(snapshot, Type.HEALTH_RECOVERED, previous.health));
        }
        boolean effectiveFire = snapshot.onFire() && !snapshot.inLava();
        edge(events, snapshot, wasActive && previous.onFire, effectiveFire,
                Type.FIRE, Type.FIRE_CLEARED, wasActive ? previous.health : snapshot.health());
        edge(events, snapshot, wasActive && previous.inLava, snapshot.inLava(),
                Type.LAVA, Type.LAVA_CLEARED, wasActive ? previous.health : snapshot.health());
        if (currentLowAir && !previousLowAir) {
            events.add(event(snapshot, Type.LOW_AIR, wasActive ? previous.health : snapshot.health()));
        } else if (!currentLowAir && previousLowAir) {
            events.add(event(snapshot, Type.AIR_RECOVERED, previous.health));
        }
        if (currentFallDanger && !previousFallDanger) {
            events.add(event(snapshot, Type.FALL_DANGER, wasActive ? previous.health : snapshot.health()));
        } else if (!currentFallDanger && previousFallDanger) {
            events.add(event(snapshot, Type.FALL_DANGER_CLEARED, previous.health));
        }

        states.put(snapshot.companionId(), State.from(
                snapshot, currentLowHealth, currentLowAir, currentFallDanger));
        return List.copyOf(events);
    }

    public void retainCompanions(Set<String> companionIds) {
        states.keySet().retainAll(Set.copyOf(companionIds));
    }

    public void clear() {
        states.clear();
    }

    private static void edge(List<Event> events, Snapshot snapshot, boolean previous, boolean current,
                             Type entered, Type cleared, float previousHealth) {
        if (current && !previous) events.add(event(snapshot, entered, previousHealth));
        else if (!current && previous) events.add(event(snapshot, cleared, previousHealth));
    }

    private static Event event(Snapshot snapshot, Type type, float previousHealth) {
        String identity = snapshot.companionId() + ':' + type.name() + ':' + snapshot.tick();
        float damage = type == Type.DAMAGE ? Math.max(0.0F, previousHealth - snapshot.health()) : 0.0F;
        return new Event(identity, type, type.priority(), snapshot, previousHealth, damage);
    }

    private static boolean lowHealth(Snapshot snapshot) {
        return snapshot.maxHealth() <= 0.0F
                || snapshot.health() <= snapshot.maxHealth() * LOW_HEALTH_ENTER_RATIO;
    }

    private static boolean lowAir(Snapshot snapshot) {
        return snapshot.air() <= Math.min(LOW_AIR_ENTER, snapshot.maxAir());
    }

    private static boolean fallDanger(Snapshot snapshot) {
        return !snapshot.onGround() && snapshot.fallDistance() >= FALL_DANGER_ENTER;
    }

    public record Snapshot(String companionId, String behaviorId, long tick, Instant observedAt,
                           Lifecycle lifecycle, float health, float maxHealth, int air, int maxAir,
                           boolean onFire, boolean inLava, boolean onGround, float fallDistance) {
        public Snapshot {
            companionId = required(companionId, "companionId");
            behaviorId = optional(behaviorId);
            if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(lifecycle, "lifecycle");
            if (!Float.isFinite(health) || !Float.isFinite(maxHealth)
                    || health < 0.0F || maxHealth < 0.0F || health > maxHealth) {
                throw new IllegalArgumentException("health must be finite and within maxHealth");
            }
            if (air < 0 || maxAir < 0 || air > maxAir) {
                throw new IllegalArgumentException("air must be within maxAir");
            }
            if (!Float.isFinite(fallDistance) || fallDistance < 0.0F) {
                throw new IllegalArgumentException("fallDistance must be finite and non-negative");
            }
            if (lifecycle == Lifecycle.ACTIVE && (maxHealth <= 0.0F || maxAir <= 0)) {
                throw new IllegalArgumentException("active observations require positive maxima");
            }
        }
    }

    public record Event(String eventId, Type type, Priority priority, Snapshot snapshot,
                        float previousHealth, float damageAmount) { }

    public enum Lifecycle { ACTIVE, DEAD, SLEEPING }

    public enum Priority { MEDIUM, HIGH, CRITICAL }

    public enum Type {
        DAMAGE(Priority.HIGH),
        LOW_HEALTH(Priority.CRITICAL),
        HEALTH_RECOVERED(Priority.MEDIUM),
        FIRE(Priority.CRITICAL),
        FIRE_CLEARED(Priority.MEDIUM),
        LAVA(Priority.CRITICAL),
        LAVA_CLEARED(Priority.MEDIUM),
        LOW_AIR(Priority.CRITICAL),
        AIR_RECOVERED(Priority.MEDIUM),
        FALL_DANGER(Priority.CRITICAL),
        FALL_DANGER_CLEARED(Priority.MEDIUM),
        DEATH(Priority.CRITICAL),
        RESPAWN(Priority.HIGH);

        private final Priority priority;

        Type(Priority priority) { this.priority = priority; }

        public Priority priority() { return priority; }
    }

    private record State(Lifecycle lifecycle, float health, boolean onFire, boolean inLava,
                         boolean lowHealth, boolean lowAir, boolean fallDanger) {
        static State from(Snapshot snapshot, boolean lowHealth, boolean lowAir, boolean fallDanger) {
            return new State(snapshot.lifecycle(), snapshot.health(),
                    snapshot.onFire() && !snapshot.inLava(), snapshot.inLava(),
                    lowHealth, lowAir, fallDanger);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
