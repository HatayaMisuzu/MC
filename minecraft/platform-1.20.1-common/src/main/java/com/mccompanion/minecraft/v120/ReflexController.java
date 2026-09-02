package com.mccompanion.minecraft.v120;

import java.util.Optional;

/** Runtime-independent safety reflexes evaluated before every behavior tick. */
final class ReflexController {
    Optional<String> blockingReason(CompanionPlayer body) {
        if (com.mccompanion.core.body.combat.ThreatPolicy.lowHealth(body.getHealth(), body.getMaxHealth())) {
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
