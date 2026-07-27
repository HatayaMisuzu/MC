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
        JsonNode preferences,
        JsonNode episodeCapsule,
        JsonNode brainSemanticState,
        JsonNode brainBehaviorSettings,
        int maxPlanSteps) {
    public AgentContext {
        companionId = companionId == null ? "" : companionId.strip();
        verifiedWorld = verifiedWorld == null ? Json.object() : verifiedWorld.deepCopy();
        recentConversation = bounded(recentConversation, 12);
        activeTask = activeTask == null ? Json.object() : activeTask.deepCopy();
        knownLandmarks = bounded(knownLandmarks, 64);
        availableCapabilities = bounded(availableCapabilities, 64);
        preferences = preferences == null ? Json.object() : preferences.deepCopy();
        episodeCapsule = episodeCapsule == null ? Json.object() : episodeCapsule.deepCopy();
        brainSemanticState = brainSemanticState == null ? Json.object() : brainSemanticState.deepCopy();
        brainBehaviorSettings = brainBehaviorSettings == null ? Json.object() : brainBehaviorSettings.deepCopy();
        maxPlanSteps = Math.max(1, Math.min(maxPlanSteps, 8));
    }

    public AgentContext(String companionId, JsonNode verifiedWorld, List<String> recentConversation,
                        JsonNode activeTask, List<String> knownLandmarks,
                        List<String> availableCapabilities, JsonNode preferences,
                        JsonNode episodeCapsule, int maxPlanSteps) {
        this(companionId, verifiedWorld, recentConversation, activeTask, knownLandmarks,
                availableCapabilities, preferences, episodeCapsule, Json.object(), Json.object(), maxPlanSteps);
    }

    public AgentContext(String companionId, JsonNode verifiedWorld, List<String> recentConversation,
                        JsonNode activeTask, List<String> knownLandmarks,
                        List<String> availableCapabilities, JsonNode preferences, int maxPlanSteps) {
        this(companionId, verifiedWorld, recentConversation, activeTask, knownLandmarks,
                availableCapabilities, preferences, Json.object(), Json.object(), Json.object(), maxPlanSteps);
    }

    public AgentContext(String companionId, JsonNode verifiedWorld, List<String> recentConversation,
                        JsonNode activeTask, List<String> knownLandmarks,
                        List<String> availableCapabilities, int maxPlanSteps) {
        this(companionId, verifiedWorld, recentConversation, activeTask, knownLandmarks,
                availableCapabilities, Json.object(), Json.object(), Json.object(), Json.object(), maxPlanSteps);
    }

    public static AgentContext empty(String companionId, List<String> capabilities) {
        return new AgentContext(companionId, Json.object(), List.of(), Json.object(), List.of(),
                capabilities, Json.object(), Json.object(), Json.object(), Json.object(), 5);
    }

    public AgentContext withBrainSemanticState(JsonNode state) {
        return new AgentContext(companionId, verifiedWorld, recentConversation, activeTask, knownLandmarks,
                availableCapabilities, preferences, episodeCapsule, state, brainBehaviorSettings, maxPlanSteps);
    }

    public AgentContext withBrainBehaviorSettings(JsonNode settings) {
        return new AgentContext(companionId, verifiedWorld, recentConversation, activeTask, knownLandmarks,
                availableCapabilities, preferences, episodeCapsule, brainSemanticState, settings, maxPlanSteps);
    }

    private static List<String> bounded(List<String> values, int maximum) {
        if (values == null || values.isEmpty()) return List.of();
        int start = Math.max(0, values.size() - maximum);
        return List.copyOf(values.subList(start, values.size()));
    }
}
