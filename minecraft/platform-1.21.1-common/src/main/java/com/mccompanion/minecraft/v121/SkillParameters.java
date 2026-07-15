package com.mccompanion.minecraft.v121;

public record SkillParameters(String capability, String itemId, int quantity, boolean allowPartial,
                              String dimension, Integer x, Integer y, Integer z) {
    public SkillParameters {
        capability = capability == null ? "" : capability;
        itemId = itemId == null ? "" : itemId;
        dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("quantity must be 1..2304");
    }

    public SkillParameters(String capability, String itemId, int quantity, boolean allowPartial) {
        this(capability, itemId, quantity, allowPartial, "minecraft:overworld", null, null, null);
    }

    public boolean hasBlockTarget() { return x != null && y != null && z != null; }
}
