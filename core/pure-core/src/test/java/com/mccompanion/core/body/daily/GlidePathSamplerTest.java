package com.mccompanion.core.body.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GlidePathSamplerTest {
    @Test
    void samplesOnlyTheBoundedInteriorOfTheRequestedGlide() {
        var start = new DailyActionRequest.Position("minecraft:overworld", 0, 80, 0);
        var target = new DailyActionRequest.Position("minecraft:overworld", 12, 68, 4);

        var samples = GlidePathSampler.between(start, target, 6);

        assertTrue(samples.size() <= 5);
        assertTrue(samples.stream().noneMatch(start::equals));
        assertTrue(samples.stream().noneMatch(target::equals));
        assertTrue(samples.stream().allMatch(sample -> sample.dimension().equals("minecraft:overworld")));
    }

    @Test
    void refusesToSampleAcrossDimensions() {
        assertEquals(java.util.List.of(), GlidePathSampler.between(
                new DailyActionRequest.Position("minecraft:overworld", 0, 80, 0),
                new DailyActionRequest.Position("minecraft:the_nether", 4, 76, 0), 16));
    }
}
