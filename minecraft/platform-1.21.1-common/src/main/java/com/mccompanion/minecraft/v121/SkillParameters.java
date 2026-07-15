package com.mccompanion.minecraft.v121;

public record SkillParameters(String capability, String itemId, int quantity, boolean allowPartial) {
    public SkillParameters {
        capability = capability == null ? "" : capability;
        itemId = itemId == null ? "" : itemId;
        if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("quantity must be 1..2304");
    }
}
