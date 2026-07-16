package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves bounded data references only; it does not evaluate source code. */
final class TaskGraphValues {
    private static final Pattern REFERENCE =
            Pattern.compile("\\$\\{(inputs|variables|outputs)(?:\\.[A-Za-z0-9_-]+)+}");

    private TaskGraphValues() {
    }

    static ObjectNode validateInputs(JsonNode graph, JsonNode supplied) {
        if (supplied == null || supplied.isNull() || supplied.isMissingNode()) supplied = Json.object();
        if (!supplied.isObject()) throw new IllegalArgumentException("inputs must be an object");
        ObjectNode result = (ObjectNode) supplied.deepCopy();
        JsonNode definitions = graph.path("inputs");
        if (definitions.isMissingNode()) {
            if (!result.isEmpty()) throw new IllegalArgumentException("graph declares no inputs");
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> values = result.fields();
        while (values.hasNext()) {
            String name = values.next().getKey();
            if (!definitions.has(name)) throw new IllegalArgumentException("unknown graph input: " + name);
        }
        definitions.fields().forEachRemaining(entry -> {
            JsonNode definition = entry.getValue();
            if (!result.has(entry.getKey()) && definition.has("default")) {
                result.set(entry.getKey(), definition.path("default").deepCopy());
            }
            if (definition.path("required").asBoolean(false) && !result.has(entry.getKey())) {
                throw new IllegalArgumentException("missing required graph input: " + entry.getKey());
            }
            if (result.has(entry.getKey()) && !matches(definition.path("type").asText(), result.path(entry.getKey()))) {
                throw new IllegalArgumentException("invalid type for graph input: " + entry.getKey());
            }
        });
        return result;
    }

    static JsonNode resolve(JsonNode value, ObjectNode context) {
        if (value == null || value.isNull()) return Json.MAPPER.nullNode();
        if (value.isTextual()) {
            String text = value.asText();
            if (REFERENCE.matcher(text).matches()) {
                return lookup(context, text.substring(2, text.length() - 1)).deepCopy();
            }
            var matcher = REFERENCE.matcher(text);
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                JsonNode resolved = lookup(context, matcher.group().substring(2, matcher.group().length() - 1));
                if (resolved.isContainerNode()) {
                    throw new IllegalArgumentException("container reference requires an exact placeholder");
                }
                matcher.appendReplacement(replaced, java.util.regex.Matcher.quoteReplacement(resolved.asText()));
            }
            matcher.appendTail(replaced);
            return Json.MAPPER.getNodeFactory().textNode(replaced.toString());
        }
        if (value.isObject()) {
            ObjectNode result = Json.object();
            value.fields().forEachRemaining(entry -> result.set(entry.getKey(), resolve(entry.getValue(), context)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = Json.MAPPER.createArrayNode();
            value.forEach(item -> result.add(resolve(item, context)));
            return result;
        }
        return value.deepCopy();
    }

    private static JsonNode lookup(ObjectNode context, String path) {
        JsonNode current = context;
        for (String segment : path.split("\\.")) {
            if (!current.isObject() || !current.has(segment)) {
                throw new IllegalArgumentException("unresolved task graph reference: " + path);
            }
            current = current.path(segment);
        }
        return current;
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "string", "registry_item", "registry_block", "registry_entity" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "position" -> value.isObject() && value.path("x").isNumber()
                    && value.path("y").isNumber() && value.path("z").isNumber();
            case "json" -> true;
            default -> false;
        };
    }
}
