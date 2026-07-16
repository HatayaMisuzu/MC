package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bounded validator for the JSON Schema subset emitted by MCAC Tool definitions.
 * This is structural validation only; it never evaluates Task Graph expressions.
 */
public final class ToolInputSchemaValidator {
    private static final Pattern EXACT_REFERENCE = Pattern.compile("\\$\\{[^{}]+}");
    private static final int MAX_VIOLATIONS = 32;

    private ToolInputSchemaValidator() {
    }

    public static List<Violation> validate(JsonNode schema, JsonNode value, boolean deferExactReferences) {
        ArrayList<Violation> violations = new ArrayList<>();
        validate(schema, value, "$", deferExactReferences, violations);
        return List.copyOf(violations);
    }

    private static void validate(JsonNode schema, JsonNode value, String path,
                                 boolean deferExactReferences, List<Violation> violations) {
        if (violations.size() >= MAX_VIOLATIONS || schema == null || !schema.isObject()) return;
        if (deferExactReferences && value != null && value.isTextual()
                && EXACT_REFERENCE.matcher(value.asText()).matches()) {
            return;
        }
        JsonNode alternatives = schema.path("oneOf");
        if (alternatives.isArray() && !alternatives.isEmpty()) {
            int matches = 0;
            for (JsonNode alternative : alternatives) {
                ArrayList<Violation> candidate = new ArrayList<>();
                validate(merged(schema, alternative), value, path, deferExactReferences, candidate);
                if (candidate.isEmpty()) matches++;
            }
            if (matches != 1) add(violations, path, "ONE_OF", "value must match exactly one schema alternative");
            return;
        }
        if (schema.has("enum") && schema.path("enum").isArray()) {
            boolean matched = false;
            for (JsonNode allowed : schema.path("enum")) {
                if (allowed.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) add(violations, path, "ENUM", "value is not an allowed enum member");
        }
        String type = schema.path("type").asText("");
        if (!type.isBlank() && !matchesType(type, value)) {
            add(violations, path, "TYPE", "expected " + type);
            return;
        }
        switch (type) {
            case "object" -> validateObject(schema, value, path, deferExactReferences, violations);
            case "array" -> validateArray(schema, value, path, deferExactReferences, violations);
            case "string" -> validateString(schema, value, path, violations);
            case "integer", "number" -> validateNumber(schema, value, path, violations);
            default -> {
                // Empty schemas intentionally accept any bounded JSON value.
            }
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path,
                                       boolean deferExactReferences, List<Violation> violations) {
        if (!value.isObject()) return;
        JsonNode properties = schema.path("properties");
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode name : required) {
                if (name.isTextual() && !value.has(name.asText())) {
                    add(violations, child(path, name.asText()), "REQUIRED", "required property is missing");
                }
            }
        }
        value.fields().forEachRemaining(entry -> {
            if (violations.size() >= MAX_VIOLATIONS) return;
            if (properties.isObject() && properties.has(entry.getKey())) {
                validate(properties.path(entry.getKey()), entry.getValue(), child(path, entry.getKey()),
                        deferExactReferences, violations);
            } else if (schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()) {
                add(violations, child(path, entry.getKey()), "ADDITIONAL_PROPERTY",
                        "additional property is not allowed");
            } else if (schema.path("additionalProperties").isObject()) {
                validate(schema.path("additionalProperties"), entry.getValue(), child(path, entry.getKey()),
                        deferExactReferences, violations);
            }
        });
    }

    private static void validateArray(JsonNode schema, JsonNode value, String path,
                                      boolean deferExactReferences, List<Violation> violations) {
        if (!value.isArray()) return;
        int minimum = schema.path("minItems").isIntegralNumber() ? schema.path("minItems").asInt() : 0;
        int maximum = schema.path("maxItems").isIntegralNumber() ? schema.path("maxItems").asInt() : Integer.MAX_VALUE;
        if (value.size() < minimum || value.size() > maximum) {
            add(violations, path, "ARRAY_SIZE", "array size is outside the declared bounds");
        }
        if (schema.path("items").isObject()) {
            for (int index = 0; index < value.size() && violations.size() < MAX_VIOLATIONS; index++) {
                validate(schema.path("items"), value.path(index), path + "[" + index + "]",
                        deferExactReferences, violations);
            }
        }
    }

    private static void validateString(JsonNode schema, JsonNode value, String path,
                                       List<Violation> violations) {
        if (!value.isTextual()) return;
        int minimum = schema.path("minLength").isIntegralNumber() ? schema.path("minLength").asInt() : 0;
        int maximum = schema.path("maxLength").isIntegralNumber()
                ? schema.path("maxLength").asInt() : Integer.MAX_VALUE;
        int length = value.asText().length();
        if (length < minimum || length > maximum) {
            add(violations, path, "STRING_LENGTH", "string length is outside the declared bounds");
        }
    }

    private static void validateNumber(JsonNode schema, JsonNode value, String path,
                                       List<Violation> violations) {
        if (!value.isNumber()) return;
        BigDecimal number = value.decimalValue();
        if (schema.path("minimum").isNumber()
                && number.compareTo(schema.path("minimum").decimalValue()) < 0) {
            add(violations, path, "MINIMUM", "number is below the declared minimum");
        }
        if (schema.path("maximum").isNumber()
                && number.compareTo(schema.path("maximum").decimalValue()) > 0) {
            add(violations, path, "MAXIMUM", "number is above the declared maximum");
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        if (value == null || value.isMissingNode()) return false;
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static JsonNode merged(JsonNode root, JsonNode alternative) {
        com.fasterxml.jackson.databind.node.ObjectNode merged =
                (com.fasterxml.jackson.databind.node.ObjectNode) root.deepCopy();
        merged.remove("oneOf");
        if (alternative.isObject()) {
            alternative.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        }
        return merged;
    }

    private static String child(String path, String field) {
        return field.matches("[A-Za-z_][A-Za-z0-9_-]*") ? path + "." + field : path + "['" + field + "']";
    }

    private static void add(List<Violation> violations, String path, String code, String message) {
        if (violations.size() < MAX_VIOLATIONS) violations.add(new Violation(path, code, message));
    }

    public record Violation(String path, String code, String message) {
    }
}
