package com.mccompanion.minecraft.bridge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SurvivalEventTrackerTest {
    @Test
    void separatesDamageFromThresholdCrossingAndUsesRecoveryHysteresis() {
        SurvivalEventTracker tracker = new SurvivalEventTracker();
        assertTrue(tracker.observe(active(1, 20.0F, 300, false, false, true, 0.0F)).isEmpty());
        assertEquals(List.of(SurvivalEventTracker.Type.DAMAGE), types(
                tracker.observe(active(2, 10.0F, 300, false, false, true, 0.0F))));
        assertEquals(List.of(SurvivalEventTracker.Type.DAMAGE, SurvivalEventTracker.Type.LOW_HEALTH), types(
                tracker.observe(active(3, 6.0F, 300, false, false, true, 0.0F))));
        assertEquals(List.of(SurvivalEventTracker.Type.DAMAGE), types(
                tracker.observe(active(4, 5.0F, 300, false, false, true, 0.0F))));
        assertTrue(tracker.observe(active(5, 9.0F, 300, false, false, true, 0.0F)).isEmpty());
        assertEquals(List.of(SurvivalEventTracker.Type.HEALTH_RECOVERED), types(
                tracker.observe(active(6, 10.0F, 300, false, false, true, 0.0F))));
    }

    @Test
    void sustainedFireLavaLowAirAndFallDangerDoNotSpam() {
        SurvivalEventTracker tracker = new SurvivalEventTracker();
        List<SurvivalEventTracker.Type> entered = types(tracker.observe(
                active(1, 20.0F, 40, true, true, false, 6.0F)));
        assertEquals(List.of(SurvivalEventTracker.Type.LAVA,
                SurvivalEventTracker.Type.LOW_AIR, SurvivalEventTracker.Type.FALL_DANGER), entered);
        assertTrue(tracker.observe(active(2, 20.0F, 30, true, true, false, 9.0F)).isEmpty());
        assertEquals(List.of(SurvivalEventTracker.Type.FIRE, SurvivalEventTracker.Type.LAVA_CLEARED,
                SurvivalEventTracker.Type.AIR_RECOVERED,
                SurvivalEventTracker.Type.FALL_DANGER_CLEARED), types(tracker.observe(
                active(3, 20.0F, 100, true, false, true, 0.0F))));
        assertEquals(List.of(SurvivalEventTracker.Type.FIRE_CLEARED), types(tracker.observe(
                active(4, 20.0F, 100, false, false, true, 0.0F))));
    }

    @Test
    void deathSuppressesDerivedEdgesAndOnlyRealDeadToActiveTransitionRespawns() {
        SurvivalEventTracker tracker = new SurvivalEventTracker();
        tracker.observe(active(1, 20.0F, 300, false, false, true, 0.0F));
        assertEquals(List.of(SurvivalEventTracker.Type.DEATH), types(tracker.observe(
                snapshot(2, SurvivalEventTracker.Lifecycle.DEAD, 0.0F, 0, true, true, false, 20.0F))));
        assertTrue(tracker.observe(snapshot(
                3, SurvivalEventTracker.Lifecycle.DEAD, 0.0F, 0, false, false, true, 0.0F)).isEmpty());
        assertEquals(List.of(SurvivalEventTracker.Type.RESPAWN), types(tracker.observe(
                active(4, 20.0F, 300, false, false, true, 0.0F))));
        tracker.observe(snapshot(5, SurvivalEventTracker.Lifecycle.SLEEPING,
                0.0F, 0, false, false, true, 0.0F));
        assertTrue(tracker.observe(active(6, 20.0F, 300, false, false, true, 0.0F)).isEmpty());
    }

    private static SurvivalEventTracker.Snapshot active(long tick, float health, int air,
                                                         boolean fire, boolean lava,
                                                         boolean onGround, float fallDistance) {
        return snapshot(tick, SurvivalEventTracker.Lifecycle.ACTIVE,
                health, air, fire, lava, onGround, fallDistance);
    }

    private static SurvivalEventTracker.Snapshot snapshot(long tick,
                                                           SurvivalEventTracker.Lifecycle lifecycle,
                                                           float health, int air, boolean fire,
                                                           boolean lava, boolean onGround,
                                                           float fallDistance) {
        return new SurvivalEventTracker.Snapshot("companion", "behavior", tick, Instant.EPOCH,
                lifecycle, health, lifecycle == SurvivalEventTracker.Lifecycle.ACTIVE ? 20.0F : 0.0F,
                air, lifecycle == SurvivalEventTracker.Lifecycle.ACTIVE ? 300 : 0,
                fire, lava, onGround, fallDistance);
    }

    private static List<SurvivalEventTracker.Type> types(List<SurvivalEventTracker.Event> events) {
        return events.stream().map(SurvivalEventTracker.Event::type).toList();
    }
}
