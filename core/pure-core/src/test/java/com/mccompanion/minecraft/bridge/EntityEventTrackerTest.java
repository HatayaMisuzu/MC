package com.mccompanion.minecraft.bridge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.mccompanion.minecraft.bridge.EntityEventTracker.TargetKind.CURRENT;
import static com.mccompanion.minecraft.bridge.EntityEventTracker.TargetKind.FOLLOW;
import static com.mccompanion.minecraft.bridge.EntityEventTracker.TargetState.DEAD;
import static com.mccompanion.minecraft.bridge.EntityEventTracker.TargetState.IN_RANGE;
import static com.mccompanion.minecraft.bridge.EntityEventTracker.TargetState.MISSING;
import static com.mccompanion.minecraft.bridge.EntityEventTracker.TargetState.OUT_OF_RANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class EntityEventTrackerTest {
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void emitsPlayerEdgesAndDoesNotSpamSustainedPresence() {
        EntityEventTracker tracker = new EntityEventTracker();
        assertEquals(List.of(), tracker.observe(snapshot(1, List.of(), List.of(), null)));

        var entered = tracker.observe(snapshot(2, List.of(player("p1")), List.of(), null));
        assertEquals(List.of(EntityEventTracker.Type.PLAYER_ENTERED_RANGE), types(entered));
        assertEquals(List.of(), tracker.observe(snapshot(3, List.of(player("p1")), List.of(), null)));

        var left = tracker.observe(snapshot(4, List.of(), List.of(), null));
        assertEquals(List.of(EntityEventTracker.Type.PLAYER_LEFT_RANGE), types(left));
    }

    @Test
    void hostilePresenceIsCriticalAndEmittedOnlyOnEntry() {
        EntityEventTracker tracker = new EntityEventTracker();
        var first = tracker.observe(snapshot(1, List.of(), List.of(hostile("z1")), null));
        assertEquals(List.of(EntityEventTracker.Type.HOSTILE_ENTERED_THREAT_RANGE), types(first));
        assertEquals(EntityEventTracker.Priority.CRITICAL, first.get(0).priority());
        assertEquals(List.of(), tracker.observe(snapshot(2, List.of(), List.of(hostile("z1")), null)));
        tracker.observe(snapshot(3, List.of(), List.of(), null));
        assertEquals(List.of(EntityEventTracker.Type.HOSTILE_ENTERED_THREAT_RANGE),
                types(tracker.observe(snapshot(4, List.of(), List.of(hostile("z1")), null))));
    }

    @Test
    void distinguishesFollowLossReappearanceDeathAndDisappearance() {
        EntityEventTracker tracker = new EntityEventTracker();
        tracker.observe(snapshot(1, List.of(), List.of(), target("owner", FOLLOW, IN_RANGE)));
        assertEquals(List.of(EntityEventTracker.Type.FOLLOW_TARGET_LOST),
                types(tracker.observe(snapshot(2, List.of(), List.of(), target("owner", FOLLOW, OUT_OF_RANGE)))));
        assertEquals(List.of(EntityEventTracker.Type.TARGET_REAPPEARED),
                types(tracker.observe(snapshot(3, List.of(), List.of(), target("owner", FOLLOW, IN_RANGE)))));

        tracker.observe(snapshot(4, List.of(), List.of(), target("cow", CURRENT, IN_RANGE)));
        assertEquals(List.of(EntityEventTracker.Type.CURRENT_TARGET_DIED),
                types(tracker.observe(snapshot(5, List.of(), List.of(), target("cow", CURRENT, DEAD)))));
        tracker.observe(snapshot(6, List.of(), List.of(), target("pig", CURRENT, IN_RANGE)));
        assertEquals(List.of(EntityEventTracker.Type.CURRENT_TARGET_DISAPPEARED),
                types(tracker.observe(snapshot(7, List.of(), List.of(), target("pig", CURRENT, MISSING)))));
    }

    private static EntityEventTracker.Snapshot snapshot(long tick,
                                                         List<EntityEventTracker.EntityFact> players,
                                                         List<EntityEventTracker.EntityFact> hostiles,
                                                         EntityEventTracker.TargetFact target) {
        return new EntityEventTracker.Snapshot("companion", "behavior", tick, NOW, players, hostiles, target);
    }

    private static EntityEventTracker.EntityFact player(String id) {
        return new EntityEventTracker.EntityFact(id, "minecraft:player", id, true, false, true, 4.0D);
    }

    private static EntityEventTracker.EntityFact hostile(String id) {
        return new EntityEventTracker.EntityFact(id, "minecraft:zombie", id, false, true, true, 4.0D);
    }

    private static EntityEventTracker.TargetFact target(
            String id, EntityEventTracker.TargetKind kind, EntityEventTracker.TargetState state) {
        return new EntityEventTracker.TargetFact(id, "minecraft:player", id, kind, state,
                kind == FOLLOW, false, state != DEAD, state == OUT_OF_RANGE ? 400.0D : 4.0D);
    }

    private static List<EntityEventTracker.Type> types(List<EntityEventTracker.Event> events) {
        return events.stream().map(EntityEventTracker.Event::type).toList();
    }
}
