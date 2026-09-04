package com.mccompanion.core.body.daily;

import java.util.Locale;

/** Normalized, bounded input supplied by Runtime/Brain to the shared daily-action engine. */
public record DailyActionRequest(
        DailyActionKind kind,
        String itemId,
        String targetId,
        String secondaryTargetId,
        String dimension,
        Position target,
        String action,
        String destination,
        String direction,
        int slot,
        int quantity,
        int durationTicks,
        int maxTicks,
        int radius,
        String targetBlockId) {

    public DailyActionRequest {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        itemId = clean(itemId);
        targetId = clean(targetId);
        secondaryTargetId = clean(secondaryTargetId);
        dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        action = clean(action).toUpperCase(Locale.ROOT);
        if (action.isBlank()) action = defaultAction(kind);
        if (!allowedAction(kind, action)) throw new IllegalArgumentException("unsupported daily action: " + action);
        destination = clean(destination).toUpperCase(Locale.ROOT);
        direction = clean(direction).toUpperCase(Locale.ROOT);
        targetBlockId = clean(targetBlockId);
        if (targetBlockId.isBlank() && kind == DailyActionKind.EQUIP_ITEM
                && action.equals("BEST_TOOL")) targetBlockId = itemId;
        if (slot < -1 || slot > 127) throw new IllegalArgumentException("slot must be -1..127");
        if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("quantity must be 1..2304");
        if (durationTicks < 0 || durationTicks > 20 * 60 * 10) throw new IllegalArgumentException("durationTicks out of bounds");
        if (maxTicks < 1 || maxTicks > 20 * 60 * 30) throw new IllegalArgumentException("maxTicks out of bounds");
        if (radius < 0 || radius > 64) throw new IllegalArgumentException("radius out of bounds");
        if (target != null && !dimension.equals(target.dimension())) {
            throw new IllegalArgumentException("target dimension must match request dimension");
        }
        switch (kind) {
            case EQUIP_ITEM -> {
                if (action.equals("EQUIP") && itemId.isBlank()) required("itemId");
                if (action.equals("UNEQUIP") && destination.isBlank()) required("destination");
                if (action.equals("BEST_TOOL") && targetBlockId.isBlank()) required("targetBlockId");
            }
            case USE_WATER_BUCKET, ENCHANT_ITEM, BREW_POTION, GLIDE_WITH_ELYTRA -> {
                if (target == null) required("target");
                if ((kind == DailyActionKind.ENCHANT_ITEM || kind == DailyActionKind.BREW_POTION)
                        && itemId.isBlank()) required("itemId");
            }
            case USE_VEHICLE -> {
                if (action.equals("TRAVEL") && target == null) required("target");
                if (action.equals("MOUNT") && targetId.isBlank() && itemId.isBlank()) {
                    throw new IllegalArgumentException("vehicle target id or type is required");
                }
            }
            case FISH -> { if (itemId.isBlank()) required("fishing rod itemId"); }
            case FARM_CROP -> { if (itemId.isBlank()) required("crop itemId"); }
            case BREED_ANIMALS -> {
                if (quantity != 1) {
                    throw new IllegalArgumentException("breeding quantity must be exactly 1");
                }
                if (targetId.isBlank() != secondaryTargetId.isBlank()) {
                    throw new IllegalArgumentException("two animal target ids must be supplied together");
                }
                if (itemId.isBlank() && (targetId.isBlank() || secondaryTargetId.isBlank())) {
                    throw new IllegalArgumentException("animal type or two target ids are required");
                }
            }
            case TRADE_WITH_VILLAGER -> {
                if (targetId.isBlank()) required("villager targetId");
                if (slot < 0) required("offer slot");
            }
            case SLEEP_AT_BED -> { }
        }
    }

    public DailyActionRequest(DailyActionKind kind, String itemId, String targetId, String secondaryTargetId,
                              String dimension, Position target, String action, String destination,
                              String direction, int slot, int quantity, int durationTicks, int maxTicks) {
        this(kind, itemId, targetId, secondaryTargetId, dimension, target, action, destination, direction,
                slot, quantity, durationTicks, maxTicks, 16, "");
    }

    public DailyActionRequest(DailyActionKind kind, String itemId, String targetId, String secondaryTargetId,
                              String dimension, Position target, String action, String destination,
                              String direction, int slot, int quantity, int durationTicks, int maxTicks,
                              int radius) {
        this(kind, itemId, targetId, secondaryTargetId, dimension, target, action, destination,
                direction, slot, quantity, durationTicks, maxTicks, radius, "");
    }

    public static DailyActionRequest of(DailyActionKind kind, String itemId, int quantity) {
        return new DailyActionRequest(kind, itemId, "", "", "minecraft:overworld", null,
                "", "", "", -1, quantity, 0, 20 * 60 * 5, 16, "");
    }

    public DailyActionRequest withTarget(Position value) {
        return new DailyActionRequest(kind, itemId, targetId, secondaryTargetId, dimension, value,
                action, destination, direction, slot, quantity, durationTicks, maxTicks, radius,
                targetBlockId);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static void required(String field) {
        throw new IllegalArgumentException(field + " is required");
    }

    private static String defaultAction(DailyActionKind kind) {
        return switch (kind) {
            case EQUIP_ITEM -> "EQUIP";
            case SLEEP_AT_BED -> "SLEEP";
            case USE_WATER_BUCKET -> "FILL";
            case USE_VEHICLE -> "TRAVEL";
            case FISH -> "FISH";
            case FARM_CROP -> "HARVEST_REPLANT";
            case BREED_ANIMALS -> "BREED";
            case TRADE_WITH_VILLAGER -> "TRADE";
            case ENCHANT_ITEM -> "ENCHANT";
            case BREW_POTION -> "BREW";
            case GLIDE_WITH_ELYTRA -> "GLIDE";
        };
    }

    private static boolean allowedAction(DailyActionKind kind, String action) {
        return switch (kind) {
            case EQUIP_ITEM -> java.util.Set.of("EQUIP", "UNEQUIP", "BEST_TOOL",
                    "BEST_WEAPON_ANY", "BEST_WEAPON_MELEE", "BEST_WEAPON_RANGED").contains(action);
            case SLEEP_AT_BED -> java.util.Set.of("SLEEP", "WAKE").contains(action);
            case USE_WATER_BUCKET -> java.util.Set.of("FILL", "EMPTY").contains(action);
            case USE_VEHICLE -> java.util.Set.of("MOUNT", "TRAVEL", "DISMOUNT").contains(action);
            case FISH -> action.equals("FISH");
            case FARM_CROP -> action.equals("HARVEST_REPLANT");
            case BREED_ANIMALS -> action.equals("BREED");
            case TRADE_WITH_VILLAGER -> action.equals("TRADE");
            case ENCHANT_ITEM -> action.equals("ENCHANT");
            case BREW_POTION -> action.equals("BREW");
            case GLIDE_WITH_ELYTRA -> action.equals("GLIDE");
        };
    }

    public record Position(String dimension, int x, int y, int z) {
        public Position {
            dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        }
    }
}
