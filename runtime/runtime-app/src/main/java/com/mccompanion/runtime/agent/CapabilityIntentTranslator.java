package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.task.TaskType;

import java.util.Optional;

/** Temporary protocol adapter for capabilities already implemented by the Fabric body. */
public final class CapabilityIntentTranslator {
    public Optional<Intent> translate(PlanStep step, String requestText) {
        return switch (step.capability()) {
            case "FollowOwner" -> Optional.of(new Intent(TaskType.FOLLOW, Json.object(), requestText));
            case "NavigateTo" -> Optional.ofNullable(navigate(step.parameters(), requestText));
            case "DeliverItem", "EatAndRecover" -> Optional.of(skill(step, requestText));
            default -> Optional.empty();
        };
    }

    private static Intent skill(PlanStep step, String requestText) {
        ObjectNode arguments = Json.object().put("capability", step.capability());
        arguments.set("parameters", step.parameters());
        return new Intent(TaskType.SKILL, arguments, requestText);
    }

    private static Intent navigate(JsonNode parameters, String requestText) {
        JsonNode target = parameters.path("target");
        if (target.isTextual() && target.asText().equalsIgnoreCase("owner")) return new Intent(TaskType.RETURN, Json.object(), requestText);
        if (!target.isObject() && parameters.has("x")) target = parameters;
        if (!target.isObject() || !target.path("x").canConvertToInt() || !target.path("y").canConvertToInt()
                || !target.path("z").canConvertToInt()) return null;
        int x = target.path("x").asInt(), y = target.path("y").asInt(), z = target.path("z").asInt();
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) return null;
        ObjectNode safeTarget = Json.object().put("dimension", target.path("dimension").asText("minecraft:overworld"))
                .put("x", x).put("y", y).put("z", z);
        ObjectNode arguments = Json.object(); arguments.set("target", safeTarget);
        return new Intent(TaskType.TRAVEL, arguments, requestText);
    }
}
