package com.mccompanion.minecraft.bridge;

import java.util.Map;

/** Shared wire projection of bounded observations from the existing event trackers. */
public final class BridgeEventMessages {
    private BridgeEventMessages() { }
    public static Map<String, Object> entity(EntityEventTracker.Event event, boolean localSafetyHandling) {
        Fields payload = new Fields()
                .with("eventId", event.eventId())
                .with("eventType", event.type().name())
                .with("priority", event.priority().name())
                .with("source", "MINECRAFT_ENTITY_OBSERVER")
                .with("companionId", event.companionId())
                .with("tick", event.tick())
                .with("occurredAt", event.occurredAt().toString());
        if (event.behaviorId() != null) payload.with("behaviorId", event.behaviorId());
        EntityEventTracker.EntityFact target = event.target();
        payload.child("target")
                .with("entityId", target.identity()).with("entityType", target.type())
                .with("displayName", target.displayName() == null ? "" : target.displayName())
                .with("player", target.player()).with("hostile", target.hostile())
                .with("alive", target.alive()).with("distanceSquared", target.distanceSquared());
        payload.with("localSafetyHandling", localSafetyHandling);
        return payload;
    }

    public static Map<String, Object> survival(SurvivalEventTracker.Event event, boolean localSafetyHandling) {
        SurvivalEventTracker.Snapshot snapshot = event.snapshot();
        Fields payload = new Fields()
                .with("eventId", event.eventId()).with("eventType", event.type().name())
                .with("priority", event.priority().name()).with("source", "MINECRAFT_SURVIVAL_OBSERVER")
                .with("companionId", snapshot.companionId()).with("tick", snapshot.tick())
                .with("occurredAt", snapshot.observedAt().toString())
                .with("previousHealth", event.previousHealth()).with("damageAmount", event.damageAmount());
        if (snapshot.behaviorId() != null) payload.with("behaviorId", snapshot.behaviorId());
        payload.child("vitals")
                .with("lifecycle", snapshot.lifecycle().name())
                .with("health", snapshot.health()).with("maxHealth", snapshot.maxHealth())
                .with("air", snapshot.air()).with("maxAir", snapshot.maxAir())
                .with("onFire", snapshot.onFire()).with("inLava", snapshot.inLava())
                .with("onGround", snapshot.onGround()).with("fallDistance", snapshot.fallDistance());
        payload.with("localSafetyHandling", localSafetyHandling);
        return payload;
    }

    public static Map<String, Object> inventoryWorld(InventoryWorldEventTracker.Event event, boolean localSafetyHandling) {
        InventoryWorldEventTracker.Snapshot snapshot = event.snapshot();
        boolean inventoryCategory = event.type().ordinal()
                <= InventoryWorldEventTracker.Type.RESOURCE_TARGET_REACHED.ordinal();
        Fields payload = new Fields()
                .with("eventId", event.eventId()).with("eventType", event.type().name())
                .with("category", inventoryCategory ? "INVENTORY" : "WORLD")
                .with("priority", event.priority().name())
                .with("source", "MINECRAFT_INVENTORY_WORLD_OBSERVER")
                .with("companionId", snapshot.companionId()).with("tick", snapshot.tick())
                .with("occurredAt", snapshot.observedAt().toString())
                .with("previousValue", event.previousValue()).with("currentValue", event.currentValue());
        if (snapshot.behaviorId() != null) payload.with("behaviorId", snapshot.behaviorId());
        Fields inventory = payload.child("inventory")
                .with("slots", snapshot.inventorySlots()).with("freeSlots", snapshot.freeInventorySlots());
        Fields counts = inventory.child("counts");
        snapshot.inventory().forEach(counts::with);
        if (snapshot.resourceGoal() != null) inventory.child("goal")
                .with("itemId", snapshot.resourceGoal().itemId())
                .with("requiredCount", snapshot.resourceGoal().requiredCount())
                .with("currentCount", snapshot.inventory().getOrDefault(snapshot.resourceGoal().itemId(), 0));
        payload.child("world").with("dimension", snapshot.dimension())
                .with("timeOfDay", snapshot.timeOfDay().name()).with("weather", snapshot.weather().name());
        if (snapshot.target() != null) {
            InventoryWorldEventTracker.Target target = snapshot.target();
            payload.child("target").with("identity", target.identity())
                    .with("kind", target.kind().name()).with("present", target.present())
                    .with("blockId", target.blockId() == null ? "" : target.blockId())
                    .with("blockFingerprint", target.blockFingerprint())
                    .with("containerType", target.containerType() == null ? "" : target.containerType())
                    .with("containerFingerprint", target.containerFingerprint());
        }
        return payload;
    }

    private static final class Fields extends java.util.LinkedHashMap<String, Object> {
        Fields with(String key, Object value) { put(key, value); return this; }
        Fields child(String key) { Fields value = new Fields(); put(key, value); return value; }
    }
}
