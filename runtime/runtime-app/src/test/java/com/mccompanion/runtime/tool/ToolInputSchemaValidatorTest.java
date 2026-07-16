package com.mccompanion.runtime.tool;

import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInputSchemaValidatorTest {
    @Test
    void validatesBoundedObjectArrayAndScalarSubset() {
        var schema = Json.parse("""
                {"type":"object","additionalProperties":false,"required":["name","count","values"],
                 "properties":{
                   "name":{"type":"string","minLength":1,"maxLength":4,"enum":["ore","wood"]},
                   "count":{"type":"integer","minimum":1,"maximum":3},
                   "values":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"boolean"}}
                 }}
                """);

        assertTrue(ToolInputSchemaValidator.validate(schema,
                Json.parse("{\"name\":\"ore\",\"count\":2,\"values\":[true]}"), false).isEmpty());
        var violations = ToolInputSchemaValidator.validate(schema,
                Json.parse("{\"name\":\"rock\",\"count\":4,\"values\":[],\"extra\":1}"), false);

        assertEquals(4, violations.size());
        assertTrue(violations.stream().anyMatch(value -> value.code().equals("ENUM")));
        assertTrue(violations.stream().anyMatch(value -> value.code().equals("MAXIMUM")));
        assertTrue(violations.stream().anyMatch(value -> value.code().equals("ARRAY_SIZE")));
        assertTrue(violations.stream().anyMatch(value -> value.code().equals("ADDITIONAL_PROPERTY")));
    }

    @Test
    void defersOnlyExactTaskGraphReferencesDuringStaticValidation() {
        var integer = Json.parse("{\"type\":\"integer\",\"minimum\":1}");

        assertTrue(ToolInputSchemaValidator.validate(integer,
                Json.MAPPER.getNodeFactory().textNode("${inputs.count}"), true).isEmpty());
        assertEquals("TYPE", ToolInputSchemaValidator.validate(integer,
                Json.MAPPER.getNodeFactory().textNode("count=${inputs.count}"), true)
                .getFirst().code());
    }
}
