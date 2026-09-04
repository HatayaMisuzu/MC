package com.mccompanion.minecraft.v121;

import com.mccompanion.core.body.combat.CombatController;
import com.mccompanion.core.body.combat.ThreatPolicy;
import com.mccompanion.core.body.interaction.EntityTargetIdentity;
import com.mccompanion.core.navigation.GridPathPlanner;
import com.mccompanion.core.navigation.RouteExecutionController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

/** Bounded interruption of one existing behavior; all effects use ordinary player actions. */
final class MinecraftThreatRecovery {
    record Seen(Mob entity, ThreatPolicy.Threat fact) { }
    interface Move { boolean to(Vec3 destination, UUID ignoredTarget); }
    final RouteExecutionController.Session interruptedRoute;
    final CompanionEntry.Mode interruptedMode;
    final com.mccompanion.core.body.BodyControlArbiter.Snapshot interruptedControl;
    private final String dimension;
    private final PlayerActionGateway gateway;
    private final SurvivalNavigationAdapter navigation;
    private final String trigger;
    private boolean recovering;
    private int elapsed;
    private int lastTick;
    private int clearTicks;
    private MinecraftCombatController defense;
    private Vec3 destination;
    private int nextRouteTick;
    private Item food;
    private int foodBefore;
    private int hungerBefore;
    private int eatingSince;
    private int meals;
    private String phase = "DISENGAGE";
    private UUID selected;

    MinecraftThreatRecovery(CompanionEntry entry, CompanionPlayer body, ThreatPolicy.Decision decision,
                            RouteExecutionController.Session route, PlayerActionGateway gateway,
                            SurvivalNavigationAdapter navigation, int tick,
                            com.mccompanion.core.body.BodyControlArbiter.Snapshot control) {
        interruptedControl = control;
        interruptedRoute = route;
        interruptedMode = entry.mode;
        dimension = body.serverLevel().dimension().location().toString();
        this.gateway = gateway;
        this.navigation = navigation;
        trigger = decision.reason();
        recovering = ThreatPolicy.lowHealth(body.getHealth(), body.getMaxHealth());
        lastTick = tick;
    }

    static List<Seen> observe(CompanionPlayer body, ServerPlayer owner) {
        Mob attacker = owner != null && owner.level() == body.level()
                && owner.tickCount - owner.getLastHurtByMobTimestamp() <= 100
                && owner.getLastHurtByMob() instanceof Mob mob ? mob : null;
        return body.serverLevel().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(24),
                        mob -> mob.isAlive() && (mob instanceof Enemy || mob == attacker))
                .stream().sorted(Comparator.comparingDouble(body::distanceToSqr)).limit(32).map(mob -> {
                    var kind = mob instanceof Creeper ? ThreatPolicy.Kind.CREEPER
                            : mob instanceof AbstractSkeleton ? ThreatPolicy.Kind.RANGED : ThreatPolicy.Kind.MELEE;
                    boolean ownerHit = mob == attacker && mob.distanceToSqr(owner) <= 144;
                    return new Seen(mob, new ThreatPolicy.Threat(mob.getUUID(), kind, body.distanceTo(mob),
                            mob.getTarget() == body || owner != null && mob.getTarget() == owner,
                            ownerHit, mob instanceof Creeper creeper && creeper.getSwellDir() > 0));
                }).toList();
    }

    static ThreatPolicy.Decision decision(CompanionPlayer body, UUID target, List<Seen> seen) {
        return ThreatPolicy.decide(body.getHealth(), body.getMaxHealth(), target,
                seen.stream().map(Seen::fact).toList());
    }

    /** null = still handling; COMPLETE or a concrete failure is returned only from observations. */
    String tick(CompanionPlayer body, ServerPlayer owner, List<Seen> seen, int tick, Move move) {
        elapsed += Math.max(0, tick - lastTick);
        lastTick = tick;
        if (!dimension.equals(body.serverLevel().dimension().location().toString())) return "WORLD_CHANGED";
        if (elapsed > 1200) return "THREAT_RECOVERY_TIMEOUT";
        recovering |= ThreatPolicy.lowHealth(body.getHealth(), body.getMaxHealth());
        var decision = decision(body, null, seen);
        selected = decision.target() == null ? null : decision.target().id();
        if (!recovering && decision.action() == ThreatPolicy.Action.DEFEND_OWNER) {
            Mob target = seen.stream().filter(s -> s.entity().getUUID().equals(selected))
                    .map(Seen::entity).findFirst().orElseThrow();
            if (defense == null || !defense.identity.uuid().equals(selected)) {
                if (defense != null) defense.pause(body);
                defense = new MinecraftCombatController(new EntityTargetIdentity(selected,
                        EntityTargetIdentity.Source.UUID, "", false), defenseStyle(body, target), 400, body, gateway);
            }
            phase = "DEFEND_OWNER";
            var result = defense.tick(body, target, tick);
            if (result.status() == CombatController.Status.FAILED) return result.code();
            if (result.action() == CombatController.Action.CHASE || result.action() == CombatController.Action.BACK_OFF) {
                Vec3 away = body.position().subtract(target.position()).multiply(1, 0, 1).normalize();
                double distance = defense.style() == CombatController.Style.BOW ? 10 : 1.8;
                if (!move.to(target.position().add(away.scale(distance)), selected)) return "RECOVERY_PATH_BLOCKED";
            } else gateway.stopInput(body);
            return null;
        }
        if (defense != null) { defense.pause(body); defense = null; }
        List<Seen> unsafe = seen.stream().filter(s -> s.fact().distance() < s.fact().safeDistance()).toList();
        if (!unsafe.isEmpty()) {
            clearTicks = 0;
            phase = "RETREAT";
            gateway.cancelUsing(body);
            food = null;
            if (destination == null || tick >= nextRouteTick || body.distanceToSqr(destination) < 1) {
                destination = retreatDestination(body, unsafe);
                nextRouteTick = tick + 10;
            }
            if (destination == null || !move.to(destination, null)) return "RECOVERY_PATH_BLOCKED";
            return null;
        }
        gateway.stopInput(body);
        if (++clearTicks < 10) { phase = "SAFE_DISTANCE"; return null; }
        if (!recovering || ThreatPolicy.recovered(body.getHealth(), body.getMaxHealth())) {
            gateway.cancelUsing(body);
            phase = "RESUMED";
            return "COMPLETE";
        }
        phase = "RECOVER";
        if (food != null) {
            if (body.getInventory().countItem(food) < foodBefore
                    && body.getFoodData().getFoodLevel() > hungerBefore) { meals++; food = null; }
            else if (tick - eatingSince > 60) return "RECOVERY_FOOD_UNVERIFIED";
            else return null;
        }
        if (body.getFoodData().getFoodLevel() >= 18) return null; // Wait for vanilla regeneration.
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.FOOD)
                    && stack.get(net.minecraft.core.component.DataComponents.FOOD).nutrition() > 0) {
                food = stack.getItem();
                foodBefore = body.getInventory().countItem(food);
                hungerBefore = body.getFoodData().getFoodLevel();
                eatingSince = tick;
                if (!gateway.equipCombatItem(body, slot, false)
                        || !gateway.startUsing(body, InteractionHand.MAIN_HAND)) return "RECOVERY_FOOD_USE_FAILED";
                phase = "EAT";
                return null;
            }
        }
        return "RECOVERY_FOOD_MISSING";
    }

    private Vec3 retreatDestination(CompanionPlayer body, List<Seen> threats) {
        List<Vec3> candidates = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            candidates.add(body.position().add(Math.cos(angle) * 4, 0, Math.sin(angle) * 4));
        }
        candidates.sort(Comparator.comparingDouble((Vec3 p) -> clearance(p, threats)).reversed());
        double current = clearance(body.position(), threats);
        for (Vec3 candidate : candidates) {
            if (clearance(candidate, threats) <= current + 0.25) continue;
            if (navigation.plan(body, candidate).status() == GridPathPlanner.Status.READY) return candidate;
        }
        return null;
    }

    private static double clearance(Vec3 position, List<Seen> threats) {
        return threats.stream().mapToDouble(s -> position.distanceTo(s.entity().position())
                - s.fact().safeDistance()).min().orElse(24);
    }

    static String defenseStyle(CompanionPlayer body, Mob target) {
        boolean bow = false, shield = false;
        for (int slot = 0; slot < 36; slot++) {
            var item = body.getInventory().getItem(slot).getItem();
            bow |= item instanceof BowItem;
            shield |= item instanceof ShieldItem;
        }
        if (target instanceof AbstractSkeleton && bow
                && body.getInventory().items.stream().anyMatch(s -> s.getItem() instanceof net.minecraft.world.item.ArrowItem))
            return "BowAttack";
        return shield ? "ShieldCombat" : "MeleeAttack";
    }

    void stop(CompanionPlayer body) {
        if (defense != null) defense.pause(body);
        gateway.cancelUsing(body);
        gateway.stopInput(body);
    }

    Map<String, String> details() {
        return Map.of("localSafetyHandling", "true", "trigger", trigger, "phase", phase,
                "threatId", selected == null ? "" : selected.toString(), "meals", Integer.toString(meals),
                "interruptedMode", interruptedMode.name());
    }
}
