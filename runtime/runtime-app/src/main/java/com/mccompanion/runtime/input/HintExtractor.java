package com.mccompanion.runtime.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Produces candidates for the model; candidates never authorize or execute an action. */
public final class HintExtractor {
    private static final Map<String, String> ITEMS = Map.ofEntries(
            Map.entry("铁锭", "minecraft:iron_ingot"), Map.entry("iron ingot", "minecraft:iron_ingot"),
            Map.entry("铁镐", "minecraft:iron_pickaxe"), Map.entry("火把", "minecraft:torch"),
            Map.entry("木头", "minecraft:oak_log"), Map.entry("原木", "minecraft:oak_log"),
            Map.entry("煤", "minecraft:coal"), Map.entry("食物", "minecraft:cooked_beef"));

    public IntentHints extract(NormalizedInput input) {
        List<IntentHints.EntityCandidate> items = new ArrayList<>();
        ITEMS.forEach((alias, id) -> {
            if (input.normalized().contains(alias)) items.add(new IntentHints.EntityCandidate(id, 0.75));
        });
        boolean delivery = input.normalized().matches(".*(?:给我|交给|送给|deliver).*" );
        String intent = items.isEmpty() ? "UNKNOWN" : delivery ? "ACQUIRE_AND_DELIVER" : "ACQUIRE_ITEM";
        return new IntentHints(intent, items.isEmpty() ? 0.0 : 0.65, List.copyOf(items), delivery);
    }
}
