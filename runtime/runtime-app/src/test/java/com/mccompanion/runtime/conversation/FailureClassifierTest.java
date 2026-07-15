package com.mccompanion.runtime.conversation;

import com.mccompanion.runtime.agent.PlanStep;
import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureClassifierTest {
    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void explicitShortageConstraintRequiresConversationInsteadOfAutonomousExpansion() {
        var result = classifier.classify("ITEM_INSUFFICIENT", "去箱子拿16个铁锭，不够就告诉我",
                Json.object().put("available", 6).put("requested", 16), step());
        assertEquals(FailureCategory.RESOURCE_SHORTAGE, result.category());
        assertTrue(result.requiresUserChoice());
        assertFalse(result.autonomousReplanAllowed());
        assertEquals("USER_REQUIRED_SHORTAGE_REPORT", result.reason());
        assertEquals(6, result.observedFacts().path("available").asInt());
    }

    @Test
    void readsConcreteFailureFromFabricBlockedSnapshot() {
        var snapshot = Json.object().put("failureCode", "ITEM_INSUFFICIENT")
                .put("available", 6).put("requested", 16);
        var result = classifier.classify("BLOCKED", "去箱子拿16个铁锭，不够就告诉我",
                Json.object().set("snapshot", snapshot), step());
        assertEquals(FailureCategory.RESOURCE_SHORTAGE, result.category());
        assertTrue(result.requiresUserChoice());
    }

    @Test
    void explicitBroaderSearchAllowsAutomaticReplan() {
        var result = classifier.classify("ITEM_INSUFFICIENT", "不够就去找其他箱子补齐",
                Json.object().put("available", 6), step());
        assertTrue(result.autonomousReplanAllowed());
        assertFalse(result.requiresUserChoice());
    }

    @Test
    void unreachableRouteCanReplanButUnsupportedCapabilityCannotLoop() {
        assertTrue(classifier.classify("PATH_UNREACHABLE", "go", Json.object(), step())
                .autonomousReplanAllowed());
        var unsupported = classifier.classify("CAPABILITY_UNAVAILABLE", "build", Json.object(), step());
        assertEquals(FailureCategory.UNSUPPORTED_CAPABILITY, unsupported.category());
        assertFalse(unsupported.autonomousReplanAllowed());
    }

    private static PlanStep step() {
        return new PlanStep("get iron", "WithdrawFromStorage", Json.object().put("quantity", 16),
                "inventory delta", Json.object(), "classify", false, RiskLevel.LOW);
    }
}
