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
        int maxRiskUnits) {
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
