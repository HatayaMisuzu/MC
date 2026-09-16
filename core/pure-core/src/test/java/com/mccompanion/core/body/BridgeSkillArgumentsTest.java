package com.mccompanion.core.body;

import com.mccompanion.minecraft.bridge.BridgeSkillArguments;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BridgeSkillArgumentsTest {
    @Test void decodesOnlyBoundedPlainValues() {
        SkillParameters result = BridgeSkillArguments.decode(Map.of(
                "capability", "storage.transfer",
                "parameters", Map.of(
                        "item", "minecraft:oak_log",
                        "quantity", 3,
                        "allowedBreakBlocks", List.of("minecraft:oak_log", 7))));

        assertEquals("storage.transfer", result.capability());
        assertEquals("minecraft:oak_log", result.itemId());
        assertEquals(3, result.quantity());
        assertEquals(List.of("minecraft:oak_log"), result.allowedBreakBlocks());
    }

    @Test void rejectsMalformedNumbersWithoutEscapingTheCodecBoundary() {
        assertNull(BridgeSkillArguments.decode(Map.of(
                "capability", "storage.transfer",
                "parameters", Map.of("quantity", "many"))));
        assertNull(BridgeSkillArguments.decode(Map.of(
                "capability", "build.small_blueprint",
                "parameters", Map.of("blueprint", Map.of(
                        "anchor", Map.of("dimension", "minecraft:overworld", "x", "bad"),
                        "maxSize", Map.of(), "blocks", List.of())))));
    }
}
