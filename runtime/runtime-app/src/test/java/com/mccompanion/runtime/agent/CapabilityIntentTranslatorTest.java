package com.mccompanion.runtime.agent;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityIntentTranslatorTest {
    private final CapabilityIntentTranslator translator = new CapabilityIntentTranslator();

    @Test
    void translatesOnlyImplementedReusableCapabilities() {
        var follow = translator.translate(step("FollowOwner", Json.object()), "跟着我").orElseThrow();
        assertEquals(TaskType.FOLLOW, follow.type());
        var navigate = translator.translate(step("NavigateTo", Json.object().putObject("target")
                .put("x", 8).put("y", 64).put("z", -3)), "去那里").orElseThrow();
        assertEquals(TaskType.TRAVEL, navigate.type());
        assertEquals(-3, navigate.arguments().path("target").path("z").asInt());
        assertTrue(translator.translate(step("CraftItem", Json.object()), "做铁镐").isEmpty());
    }

    @Test
    void refusesInvalidOrUnsafeCoordinates() {
        assertTrue(translator.translate(step("NavigateTo", Json.object().putObject("target")
                .put("x", 30_000_001).put("y", 64).put("z", 0)), "go").isEmpty());
    }

    private static PlanStep step(String capability, com.fasterxml.jackson.databind.JsonNode parameters) {
        return new PlanStep("goal", capability, parameters, "result", Json.object(), "stop", false, RiskLevel.LOW);
    }
}
