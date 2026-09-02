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
            case "FollowEntity", "ApproachEntity", "KeepDistanceFromEntity", "ChaseEntity",
                    "EscortEntity", "FleeFromEntity", "FaceEntity", "MeleeAttack", "ShieldCombat", "BowAttack" ->
                    Optional.ofNullable(entitySkill(step, requestText));
            case "WithdrawFromStorage", "DepositToStorage", "CraftItem", "DeliverItem", "EatAndRecover" ->
                    Optional.of(skill(step, requestText));
            default -> Optional.empty();
        };
    }

    private static Intent skill(PlanStep step, String requestText) {
        ObjectNode arguments = Json.object().put("capability", step.capability());
        arguments.set("parameters", step.parameters());
        return new Intent(TaskType.SKILL, arguments, requestText);
    }

    private static Intent entitySkill(PlanStep step, String requestText) {
        if (!step.parameters().isObject()) return null;
        ObjectNode values = step.parameters().deepCopy();
        JsonNode target = values.remove("target");
        if (target == null) {
            if (!values.path("targetReferenceKind").isTextual()) return null;
            return skill(step.capability(), values, requestText);
        }
        if (!target.isObject()) return null;
        int references = (target.has("uuid") ? 1 : 0) + (target.has("entityId") ? 1 : 0)
                + (target.has("playerIdentity") ? 1 : 0) + (target.has("name") ? 1 : 0);
        if (references != 1 || target.size() != 1) return null;
        try {
            if (target.has("uuid")) {
                values.put("entityId", java.util.UUID.fromString(target.path("uuid").asText()).toString());
                values.put("targetReferenceKind", "UUID");
            } else if (target.has("entityId") && target.path("entityId").canConvertToInt()
                    && target.path("entityId").asInt() >= 0) {
                values.put("targetRuntimeId", target.path("entityId").asInt());
                values.put("targetReferenceKind", "ENTITY_ID");
            } else if (target.has("playerIdentity")) {
                values.put("entityId", java.util.UUID.fromString(
                        target.path("playerIdentity").asText()).toString());
                values.put("targetReferenceKind", "VERIFIED_PLAYER");
            } else if (target.has("name") && target.path("name").isTextual()
                    && !target.path("name").asText().isBlank()
                    && target.path("name").asText().strip().length() <= 64) {
                values.put("targetName", target.path("name").asText().strip());
                values.put("targetReferenceKind", "NAME");
            } else return null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return skill(step.capability(), values, requestText);
    }

    private static Intent skill(String capability, JsonNode parameters, String requestText) {
        ObjectNode arguments = Json.object().put("capability", capability);
        arguments.set("parameters", parameters);
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
