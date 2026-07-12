package com.mccompanion.minecraft.v121;

import org.slf4j.Logger;

/** Completes vanilla death/drop processing, saves the resulting inventory, then sleeps the body for recovery. */
final class CompanionDeathController {
    private final Logger logger;

    CompanionDeathController(Logger logger) {
        this.logger = logger;
    }

    boolean isDeathReadyForRecovery(CompanionPlayer body) {
        return body.isDeadOrDying();
    }

    void recordDeath(CompanionEntry entry, CompanionPlayer body) {
        logger.warn("companion_died owner={} companion={} position={} inventory_after_vanilla_drop={}",
                entry.ownerId,
                entry.companionId,
                body.position(),
                body.getInventory().isEmpty() ? "empty" : "retained_by_gamerule_or_undropped");
    }
}
