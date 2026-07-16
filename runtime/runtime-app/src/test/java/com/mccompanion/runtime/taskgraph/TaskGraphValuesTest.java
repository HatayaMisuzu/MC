package com.mccompanion.runtime.taskgraph;

import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskGraphValuesTest {
    @Test
    void resolvesBoundedArrayCandidatesAndLengthWithoutCodeEvaluation() {
        var context = Json.object();
        context.set("inputs", Json.object());
        context.set("variables", Json.object());
        context.set("state", Json.object());
        context.set("outputs", Json.parse("""
                {"scan":{"candidates":[
                  {"position":{"x":4,"y":12,"z":-3},"block":"examplemod:blue_ore"},
                  {"position":{"x":9,"y":8,"z":2},"block":"minecraft:diamond_ore"}
                ]}}
                """));

        assertEquals(2, TaskGraphValues.lookup(context, "outputs.scan.candidates.length").asInt());
        assertEquals("examplemod:blue_ore",
                TaskGraphValues.lookup(context, "outputs.scan.candidates[0].block").asText());
        assertEquals(4, TaskGraphValues.resolve(
                Json.MAPPER.getNodeFactory().textNode("${outputs.scan.candidates[0].position.x}"),
                context).asInt());
        assertEquals("candidate=9", TaskGraphValues.resolve(
                Json.MAPPER.getNodeFactory().textNode(
                        "candidate=${outputs.scan.candidates[1].position.x}"), context).asText());
    }

    @Test
    void rejectsDynamicMalformedAndOutOfBoundsArraySelection() {
        var context = Json.object();
        context.set("inputs", Json.object().put("index", 0));
        context.set("variables", Json.object());
        context.set("state", Json.object());
        context.set("outputs", Json.parse("{" +
                "\"scan\":{\"candidates\":[{\"block\":\"minecraft:stone\"}]}}"));

        assertThrows(IllegalArgumentException.class,
                () -> TaskGraphValues.lookup(context, "outputs.scan.candidates[1]"));
        assertNotNull(TaskGraphValues.validatePath("outputs.scan.candidates[256]"));
        assertNotNull(TaskGraphValues.validateReferences(Json.MAPPER.getNodeFactory().textNode(
                "${outputs.scan.candidates[inputs.index]}")));
        assertNotNull(TaskGraphValues.validateReferences(Json.MAPPER.getNodeFactory().textNode(
                "${outputs.scan.candidates[0]")));
    }
}
