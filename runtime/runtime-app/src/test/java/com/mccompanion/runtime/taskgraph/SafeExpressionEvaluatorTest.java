package com.mccompanion.runtime.taskgraph;

import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeExpressionEvaluatorTest {
    @Test
    void evaluatesOnlyReferencesScalarsComparisonsAndBooleanOperators() {
        var context = Json.object();
        context.set("inputs", Json.object().put("minimum", 2));
        context.set("variables", Json.object().put("enabled", true));
        context.set("outputs", Json.object().set("scan",
                Json.object().put("count", 3).put("item", "testmod:ore")));

        assertTrue(SafeExpressionEvaluator.evaluateBoolean(
                "${outputs.scan.count >= inputs.minimum && variables.enabled == true}", context));
        assertEquals("testmod:ore",
                SafeExpressionEvaluator.evaluate("${outputs.scan.item}", context).asText());
        assertTrue(SafeExpressionEvaluator.evaluateBoolean("${true || variables.enabled == false}", context));
        assertFalse(SafeExpressionEvaluator.evaluateBoolean("${false && variables.enabled == true}", context));
        assertThrows(IllegalArgumentException.class,
                () -> SafeExpressionEvaluator.evaluateBoolean("${missing.value == true}", context));
        assertThrows(IllegalArgumentException.class,
                () -> SafeExpressionEvaluator.evaluateBoolean("${outputs.scan.count + 1 > 2}", context));
    }
}
