package com.mccompanion.minecraft.v121;

import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;

/** Runtime-independent safety reflexes evaluated before every behavior tick. */
final class ReflexController {
    Optional<Entity> nearestRetreatThreat(CompanionPlayer body) {
        return nearestRetreatThreat(body, null);
    }

    /** The explicit combat target is handled locally; other threats retain the existing reflex. */
    Optional<Entity> nearestRetreatThreat(CompanionPlayer body, java.util.UUID combatTarget) {
        return body.serverLevel().getEntities(body, body.getBoundingBox().inflate(6.0D),
                        entity -> entity.isAlive() && entity instanceof Enemy
                                && !entity.getUUID().equals(combatTarget))
                .stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(body)));
    }

    Optional<String> blockingReason(CompanionPlayer body) {
        if (body.getHealth() <= Math.min(4.0F, body.getMaxHealth() * 0.2F)) {
            return Optional.of("LOW_HEALTH");
        }
        if (body.isInLava() || body.isOnFire()) {
            return Optional.of("ENVIRONMENT_HAZARD");
        }
        if (body.isInWater() && body.getAirSupply() <= 40) {
            return Optional.of("DROWNING_RISK");
        }
        return Optional.empty();
    }
}
