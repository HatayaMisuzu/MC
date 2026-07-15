package com.mccompanion.runtime.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class TextUnderstandingTest {
    private final TextNormalizer normalizer = new TextNormalizer();

    @ParameterizedTest
    @CsvSource({
            "'拿１６个铁锭给我',16",
            "'准备一组火把',64",
            "'只要半组木头',32",
            "'四分之一组煤就够了',16"
    })
    void normalizesFullWidthAndGameQuantities(String text, int expected) {
        var result = normalizer.normalize(text);
        assertEquals(expected, result.quantity().orElseThrow());
        assertFalse(result.normalized().contains("１６"));
    }

    @Test
    void extractsBoundedCoordinatesWithoutMistakingThemForQuantity() {
        var result = normalizer.normalize("去 12，70，-8 看看");
        assertEquals(new NormalizedInput.Coordinates(12, 70, -8), result.coordinates().orElseThrow());
        assertTrue(result.quantity().isEmpty());
        assertTrue(normalizer.normalize("去 30000001 70 0").coordinates().isEmpty());
    }

    @Test
    void hintsAreCandidatesAndRecognizeDeliveryWithoutChoosingAStorageRoute() {
        var hints = new HintExtractor().extract(normalizer.normalize("去基地箱子拿16个铁锭给我"));
        assertEquals("ACQUIRE_AND_DELIVER", hints.possibleIntent());
        assertTrue(hints.deliveryLikely());
        assertEquals("minecraft:iron_ingot", hints.items().getFirst().id());
        assertTrue(hints.confidence() < 1.0);
    }
}
