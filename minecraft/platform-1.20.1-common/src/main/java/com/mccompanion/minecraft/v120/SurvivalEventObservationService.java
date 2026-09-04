package com.mccompanion.minecraft.v120;

import com.mccompanion.minecraft.bridge.SurvivalEventTracker;
import java.time.Instant;

/** Adapts real Minecraft 1.20.1 player vitals and lifecycle state to the shared edge detector. */
public final class SurvivalEventObservationService {
    public SurvivalEventTracker.Snapshot snapshot(CompanionRegistry.SurvivalEventBinding binding,
                                                   long tick, Instant observedAt) {
        java.util.Objects.requireNonNull(binding, "binding");
        CompanionPlayer body = binding.body();
        SurvivalEventTracker.Lifecycle lifecycle = binding.lifecycle();
        if (body == null || lifecycle != SurvivalEventTracker.Lifecycle.ACTIVE) {
            return new SurvivalEventTracker.Snapshot(binding.companionId(), binding.behaviorId(), tick,
                    observedAt, lifecycle, 0.0F, 0.0F, 0, 0,
                    false, false, true, 0.0F);
        }
        return new SurvivalEventTracker.Snapshot(binding.companionId(), binding.behaviorId(), tick,
                observedAt, lifecycle, Math.max(0.0F, body.getHealth()), body.getMaxHealth(),
                Math.max(0, body.getAirSupply()), body.getMaxAirSupply(), body.isOnFire(),
                body.isInLava(), body.onGround(), Math.max(0.0F, body.fallDistance));
    }
}
