package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

import java.util.List;

/** Bounded context: verified facts and model inferences are deliberately separate. */
public record AgentContext(
        String companionId,
        JsonNode verifiedWorld,
        List<String> recentConversation,
        JsonNode activeTask,
        List<String> knownLandmarks,
        List<String> availableCapabilities,
        int maxPlanSteps) {
    public AgentContext {
        companionId = companionId == null ? "" : companionId.strip();
        verifiedWorld = verifiedWorld == null ? Json.object() : verifiedWorld.deepCopy();
        recentConversation = bounded(recentConversation, 12);
        activeTask = activeTask == null ? Json.object() : activeTask.deepCopy();
        knownLandmarks = bounded(knownLandmarks, 64);
        availableCapabilities = bounded(availableCapabilities, 64);
        maxPlanSteps = Math.max(1, Math.min(maxPlanSteps, 8));
    }

    public static AgentContext empty(String companionId, List<String> capabilities) {
        return new AgentContext(companionId, Json.object(), List.of(), Json.object(), List.of(), capabilities, 5);
    }

    private static List<String> bounded(List<String> values, int maximum) {
        if (values == null || values.isEmpty()) return List.of();
        int start = Math.max(0, values.size() - maximum);
        return List.copyOf(values.subList(start, values.size()));
    }
}
