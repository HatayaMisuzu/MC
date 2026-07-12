package com.mccompanion.minecraft.v121;

import java.util.Optional;

/** Runtime-independent safety reflexes evaluated before every behavior tick. */
final class ReflexController {
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
