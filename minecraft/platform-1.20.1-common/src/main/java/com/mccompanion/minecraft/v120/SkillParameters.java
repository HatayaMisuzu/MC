package com.mccompanion.minecraft.v120;

/** Bounded, declarative inputs for Runtime-authored primitive execution. */
public record SkillParameters(
        String capability,
        String itemId,
        int quantity,
        boolean allowPartial,
        String dimension,
        Integer x,
        Integer y,
        Integer z,
        String targetId,
        String face,
        String hand,
        String sessionToken,
        Integer slot,
        Integer button,
        String menuAction,
        Integer durationTicks,
        String secondaryTargetId,
        java.util.List<String> allowedBreakBlocks,
        java.util.List<String> allowedPlaceBlocks,
        int maxBreakBlocks,
        int maxPlaceBlocks,
        int maxRiskUnits,
        String targetReferenceKind,
        String targetName,
        Integer targetRuntimeId,
        Double minimumDistance,
        Double maximumDistance,
        Integer lostTimeoutTicks) {
    public SkillParameters {
        capability = capability == null ? "" : capability;
        itemId = itemId == null ? "" : itemId;
        dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        targetId = targetId == null ? "" : targetId;
        face = face == null || face.isBlank() ? "UP" : face;
        hand = hand == null || hand.isBlank() ? "MAIN_HAND" : hand;
        sessionToken = sessionToken == null ? "" : sessionToken;
        menuAction = menuAction == null ? "" : menuAction;
        secondaryTargetId = secondaryTargetId == null ? "" : secondaryTargetId;
        targetReferenceKind = targetReferenceKind == null ? "" : targetReferenceKind;
        targetName = targetName == null ? "" : targetName;
        allowedBreakBlocks = allowedBreakBlocks == null ? java.util.List.of()
                : java.util.List.copyOf(allowedBreakBlocks);
        allowedPlaceBlocks = allowedPlaceBlocks == null ? java.util.List.of()
                : java.util.List.copyOf(allowedPlaceBlocks);
        if (quantity < 1 || quantity > 2304) {
            throw new IllegalArgumentException("quantity must be 1..2304");
        }
        if (maxBreakBlocks < 0 || maxBreakBlocks > 8 || maxPlaceBlocks < 0 || maxPlaceBlocks > 8
                || maxRiskUnits < 0 || maxRiskUnits > 16) {
            throw new IllegalArgumentException("navigation budgets are outside safe bounds");
        }
        if (targetRuntimeId != null && targetRuntimeId < 0) {
            throw new IllegalArgumentException("targetRuntimeId must be non-negative");
        }
        if (minimumDistance != null && (!Double.isFinite(minimumDistance)
                || minimumDistance < 0.0D || minimumDistance > 64.0D)) {
            throw new IllegalArgumentException("minimumDistance must be 0..64");
        }
        if (maximumDistance != null && (!Double.isFinite(maximumDistance)
                || maximumDistance < 0.0D || maximumDistance > 64.0D)) {
            throw new IllegalArgumentException("maximumDistance must be 0..64");
        }
        if (minimumDistance != null && maximumDistance != null && minimumDistance > maximumDistance) {
            throw new IllegalArgumentException("minimumDistance must not exceed maximumDistance");
        }
        if (lostTimeoutTicks != null && (lostTimeoutTicks < 1 || lostTimeoutTicks > 1200)) {
            throw new IllegalArgumentException("lostTimeoutTicks must be 1..1200");
        }
    }

    /** Compatibility constructor for the pre-entity-behavior wire shape. */
    public SkillParameters(
        String capability,
        String itemId,
        int quantity,
        boolean allowPartial,
        String dimension,
        Integer x,
        Integer y,
        Integer z,
        String targetId,
        String face,
        String hand,
        String sessionToken,
        Integer slot,
        Integer button,
        String menuAction,
        Integer durationTicks,
        String secondaryTargetId,
        java.util.List<String> allowedBreakBlocks,
        java.util.List<String> allowedPlaceBlocks,
        int maxBreakBlocks,
        int maxPlaceBlocks,
        int maxRiskUnits) {
        this(capability, itemId, quantity, allowPartial, dimension, x, y, z, targetId, face, hand,
                sessionToken, slot, button, menuAction, durationTicks, secondaryTargetId,
                allowedBreakBlocks, allowedPlaceBlocks, maxBreakBlocks, maxPlaceBlocks, maxRiskUnits,
                "", "", null, null, null, null);
    }

    public SkillParameters(String capability, String targetId, String targetReferenceKind,
                           String targetName, Integer targetRuntimeId, Double minimumDistance,
                           Double maximumDistance, Integer lostTimeoutTicks) {
        this(capability, "", 1, false, "minecraft:overworld", null, null, null,
                targetId, "UP", "MAIN_HAND", "", null, null, "", null, "",
                java.util.List.of(), java.util.List.of(), 0, 0, 8,
                targetReferenceKind, targetName, targetRuntimeId,
                minimumDistance, maximumDistance, lostTimeoutTicks);
    }

    /** Compatibility constructor for the pre-navigation-policy wire shape. */
    public SkillParameters(
        String capability,
        String itemId,
        int quantity,
        boolean allowPartial,
        String dimension,
        Integer x,
        Integer y,
        Integer z,
        String targetId,
        String face,
        String hand,
        String sessionToken,
        Integer slot,
        Integer button,
        String menuAction,
        Integer durationTicks,
        String secondaryTargetId) {
        this(capability, itemId, quantity, allowPartial, dimension, x, y, z, targetId, face, hand,
                sessionToken, slot, button, menuAction, durationTicks, secondaryTargetId,
                java.util.List.of(), java.util.List.of(), 0, 0, 8);
    }

    /** Compatibility constructor for the pre-partner-entity wire shape. */
    public SkillParameters(
        String capability,
        String itemId,
        int quantity,
        boolean allowPartial,
        String dimension,
        Integer x,
        Integer y,
        Integer z,
        String targetId,
        String face,
        String hand,
        String sessionToken,
        Integer slot,
        Integer button,
        String menuAction,
        Integer durationTicks) {
        this(capability, itemId, quantity, allowPartial, dimension, x, y, z, targetId, face, hand,
            sessionToken, slot, button, menuAction, durationTicks, "");
    }

    public boolean hasBlockTarget() {
        return x != null && y != null && z != null;
    }
}
