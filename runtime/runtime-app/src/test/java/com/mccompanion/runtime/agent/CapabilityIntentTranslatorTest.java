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
        var withdrawParameters = Json.object().put("item", "minecraft:iron_ingot").put("quantity", 3);
        withdrawParameters.set("container", Json.object().put("dimension", "minecraft:overworld")
                .put("x", 8).put("y", 64).put("z", -3));
        var withdraw = translator.translate(step("WithdrawFromStorage", withdrawParameters), "take iron").orElseThrow();
        assertEquals(TaskType.SKILL, withdraw.type());
        assertEquals("WithdrawFromStorage", withdraw.arguments().path("capability").asText());
        var craft = translator.translate(step("CraftItem",
                Json.object().put("item", "minecraft:iron_pickaxe").put("quantity", 1)), "做铁镐").orElseThrow();
        assertEquals(TaskType.SKILL, craft.type());
        assertEquals("CraftItem", craft.arguments().path("capability").asText());
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
