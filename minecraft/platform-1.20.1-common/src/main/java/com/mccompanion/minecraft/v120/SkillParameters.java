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
        Integer durationTicks) {
    public SkillParameters {
        capability = capability == null ? "" : capability;
        itemId = itemId == null ? "" : itemId;
        dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        targetId = targetId == null ? "" : targetId;
        face = face == null || face.isBlank() ? "UP" : face;
        hand = hand == null || hand.isBlank() ? "MAIN_HAND" : hand;
        sessionToken = sessionToken == null ? "" : sessionToken;
        menuAction = menuAction == null ? "" : menuAction;
        if (quantity < 1 || quantity > 2304) {
            throw new IllegalArgumentException("quantity must be 1..2304");
        }
    }

    public boolean hasBlockTarget() {
        return x != null && y != null && z != null;
    }
}
