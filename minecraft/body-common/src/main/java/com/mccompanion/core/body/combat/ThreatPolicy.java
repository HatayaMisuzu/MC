package com.mccompanion.core.body.combat;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Finite safety rules over current observations, never a source of new durable goals. */
public final class ThreatPolicy {
    private ThreatPolicy() { }
    public enum Kind { CREEPER, RANGED, MELEE }
    public enum Action { CONTINUE, RETREAT, DEFEND_OWNER }
    public record Threat(UUID id, Kind kind, double distance, boolean attacking, boolean ownerAttacked,
                         boolean primed) {
        public double safeDistance() { return kind == Kind.RANGED ? 16 : kind == Kind.CREEPER ? 8 : 7; }
        int priority() { return primed ? 400 : kind == Kind.CREEPER ? 300 : ownerAttacked ? 250
                : kind == Kind.RANGED && attacking ? 200 : 100; }
    }
    public record Decision(Action action, Threat target, String reason) { }
    public static boolean lowHealth(double health, double maximum) { return health <= maximum * 0.30; }
    public static boolean recovered(double health, double maximum) { return health >= maximum * 0.60; }
    public static List<Threat> ordered(List<Threat> threats) {
        return threats.stream().sorted(Comparator.comparingInt(Threat::priority).reversed()
                .thenComparingDouble(Threat::distance).thenComparing(t -> t.id().toString())).toList();
    }
    public static Decision decide(double health, double maximum, UUID combatTarget, List<Threat> threats) {
        List<Threat> ordered = ordered(threats);
        Threat first = ordered.isEmpty() ? null : ordered.get(0);
        if (lowHealth(health, maximum)) return new Decision(Action.RETREAT, first, "LOW_HEALTH");
        for (Threat t : ordered) if (t.kind() == Kind.CREEPER && (t.distance() < 5 || t.primed() && t.distance() < 8))
            return new Decision(Action.RETREAT, t, "CREEPER_DANGER");
        if (threats.stream().filter(t -> t.distance() <= 4).count() > 1)
            return new Decision(Action.RETREAT, first, "MULTIPLE_HOSTILES");
        for (Threat t : ordered) {
            if (t.ownerAttacked()) return new Decision(Action.DEFEND_OWNER, t, "OWNER_ATTACKED");
            if (t.id().equals(combatTarget)) continue;
            if (t.kind() == Kind.RANGED && t.attacking() && t.distance() < 16)
                return new Decision(Action.RETREAT, t, "RANGED_THREAT");
            if (t.distance() <= 3) return new Decision(Action.RETREAT, t, "MELEE_THREAT");
        }
        return new Decision(Action.CONTINUE, null, "CLEAR");
    }
}
