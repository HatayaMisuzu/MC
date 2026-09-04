package com.mccompanion.core.body.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class DailyActionOutcomeTest {
    @Test
    void preservesSpecificFailuresButProjectsUncertainEffectsExplicitly() {
        var blocked = DailyActionOutcome.forRuntime(new DailyActionEngine.Observation(
                DailyActionEngine.Status.BLOCKED, DailyActionKind.FISH, DailyActionPhase.WAIT_BITE,
                "FISHING_ROD_BROKEN", Map.of("phase", "WAIT_BITE")));
        assertEquals("FISHING_ROD_BROKEN", blocked.code());
        assertEquals("BLOCKED", blocked.details().get("dailyStatus"));

        var uncertain = DailyActionOutcome.forRuntime(new DailyActionEngine.Observation(
                DailyActionEngine.Status.UNCERTAIN, DailyActionKind.FISH, DailyActionPhase.REEL_LINE,
                "REEL_NOT_VERIFIED", Map.of("phase", "REEL_LINE")));
        assertEquals("UNCERTAIN_EFFECT", uncertain.code());
        assertEquals("REEL_NOT_VERIFIED", uncertain.details().get("dailyCode"));
        assertEquals("UNCERTAIN", uncertain.details().get("dailyStatus"));
    }
}
