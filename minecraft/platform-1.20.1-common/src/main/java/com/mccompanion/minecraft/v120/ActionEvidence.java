package com.mccompanion.minecraft.v120;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Bounded, queryable evidence for one locally executed behavior. */
record ActionEvidence(
        UUID actionId,
        UUID companionId,
        String behavior,
        long startTick,
        long endTick,
        Vec3 beforePosition,
        Vec3 afterPosition,
        int beforeInventoryDigest,
        int afterInventoryDigest,
        boolean success,
        String failureCode,
        String playerPathUsed,
        boolean forbiddenWriteDetected) {
}
