package com.mccompanion.core.body.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SmallBlueprintTest {
    @Test
    void validatesBoundsUniquenessStateAlternativesAndSupportBudget() {
        var anchor = new SmallBlueprint.Anchor("minecraft:overworld", 1, 64, 2);
        var size = new SmallBlueprint.Size(5, 4, 5);
        var first = new SmallBlueprint.Block(new SmallBlueprint.Offset(0, 0, 0),
                "minecraft:oak_planks", Map.of(), List.of("minecraft:spruce_planks"));
        var second = new SmallBlueprint.Block(new SmallBlueprint.Offset(0, 1, 0),
                "minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom"), List.of());
        var blueprint = new SmallBlueprint(anchor, size, List.of(second, first),
                new SmallBlueprint.SupportPolicy(List.of("minecraft:cobblestone"), 4, true));
        assertEquals(List.of(first, second), blueprint.buildOrder().stream()
                .map(SmallBlueprint.IndexedBlock::block).toList());

        assertThrows(IllegalArgumentException.class, () -> new SmallBlueprint(anchor, size,
                List.of(first, first), SmallBlueprint.SupportPolicy.none()));
        assertThrows(IllegalArgumentException.class, () -> new SmallBlueprint(anchor, size,
                List.of(new SmallBlueprint.Block(new SmallBlueprint.Offset(5, 0, 0),
                        "minecraft:stone", Map.of(), List.of())), SmallBlueprint.SupportPolicy.none()));
        assertThrows(IllegalArgumentException.class, () -> new SmallBlueprint.SupportPolicy(
                List.of("minecraft:dirt"), 0, true));
    }

    @Test
    void acceptsWallWithinSmallHouseEnvelope() {
        var blocks = new java.util.ArrayList<SmallBlueprint.Block>();
        for (int y = 0; y < 4; y++) for (int x = 0; x < 5; x++) {
            blocks.add(new SmallBlueprint.Block(new SmallBlueprint.Offset(x, y, 0),
                    y == 0 ? "minecraft:cobblestone" : "minecraft:oak_planks", Map.of(),
                    List.of("minecraft:spruce_planks")));
        }
        var houseWall = new SmallBlueprint(new SmallBlueprint.Anchor("minecraft:overworld", 0, 64, 0),
                new SmallBlueprint.Size(5, 4, 5), blocks, SmallBlueprint.SupportPolicy.none());
        assertEquals(20, houseWall.blocks().size());
    }
}
