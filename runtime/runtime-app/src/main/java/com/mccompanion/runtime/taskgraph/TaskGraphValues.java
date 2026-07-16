package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves bounded data references only; it does not evaluate source code. */
final class TaskGraphValues {
    static final int MAX_ARRAY_INDEX = 255;
    private static final Set<String> ROOTS = Set.of("inputs", "variables", "state", "outputs");
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^{}]+)}");

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
            validateTemplates(text);
            var exact = TEMPLATE.matcher(text);
            if (exact.matches()) return lookup(context, exact.group(1)).deepCopy();
            var matcher = TEMPLATE.matcher(text);
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                JsonNode resolved = lookup(context, matcher.group(1));
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

    static String validateReferences(JsonNode value) {
        try {
            validateReferenceValue(value);
            return null;
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
    }

    static String validatePath(String path) {
        try {
            parsePath(path);
            return null;
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
    }

    static JsonNode lookup(ObjectNode context, String path) {
        ReferencePath reference = parsePath(path);
        JsonNode current = context.path(reference.root());
        if (current.isMissingNode()) throw unresolved(path);
        for (Accessor accessor : reference.accessors()) {
            if (accessor.field() != null) {
                if (current.isArray() && accessor.field().equals("length")) {
                    current = Json.MAPPER.getNodeFactory().numberNode(current.size());
                } else if (current.isObject() && current.has(accessor.field())) {
                    current = current.path(accessor.field());
                } else {
                    throw unresolved(path);
                }
            } else {
                int index = accessor.index();
                if (!current.isArray() || index >= current.size()) throw unresolved(path);
                current = current.path(index);
            }
        }
        return current;
    }

    private static void validateReferenceValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return;
        if (value.isTextual()) {
            validateTemplates(value.asText());
        } else if (value.isContainerNode()) {
            value.forEach(TaskGraphValues::validateReferenceValue);
        }
    }

    private static void validateTemplates(String text) {
        var matcher = TEMPLATE.matcher(text);
        int consumed = 0;
        while (matcher.find()) {
            int unclosed = text.indexOf("${", consumed);
            if (unclosed >= 0 && unclosed < matcher.start()) {
                throw new IllegalArgumentException("malformed task graph reference");
            }
            parsePath(matcher.group(1));
            consumed = matcher.end();
        }
        if (text.indexOf("${", consumed) >= 0) {
            throw new IllegalArgumentException("malformed task graph reference");
        }
    }

    private static ReferencePath parsePath(String path) {
        if (path == null || path.isBlank() || path.length() > 512) {
            throw new IllegalArgumentException("task graph reference path must contain 1..512 characters");
        }
        int position = 0;
        while (position < path.length() && isFieldCharacter(path.charAt(position))) position++;
        String root = path.substring(0, position);
        if (!ROOTS.contains(root)) {
            throw new IllegalArgumentException("task graph reference root is unsupported");
        }
        List<Accessor> accessors = new ArrayList<>();
        while (position < path.length()) {
            char token = path.charAt(position);
            if (token == '.') {
                int start = ++position;
                while (position < path.length() && isFieldCharacter(path.charAt(position))) position++;
                if (start == position) throw new IllegalArgumentException("task graph reference field is invalid");
                accessors.add(Accessor.field(path.substring(start, position)));
            } else if (token == '[') {
                int start = ++position;
                while (position < path.length() && Character.isDigit(path.charAt(position))) position++;
                if (start == position || position >= path.length() || path.charAt(position) != ']') {
                    throw new IllegalArgumentException("task graph array index must be a literal integer");
                }
                String digits = path.substring(start, position++);
                if (digits.length() > 1 && digits.startsWith("0")) {
                    throw new IllegalArgumentException("task graph array index must be canonical");
                }
                int index;
                try {
                    index = Integer.parseInt(digits);
                } catch (NumberFormatException failure) {
                    throw new IllegalArgumentException("task graph array index is out of range");
                }
                if (index > MAX_ARRAY_INDEX) {
                    throw new IllegalArgumentException(
                            "task graph array index must be 0.." + MAX_ARRAY_INDEX);
                }
                accessors.add(Accessor.index(index));
            } else {
                throw new IllegalArgumentException("task graph reference path is invalid");
            }
        }
        if (accessors.isEmpty()) {
            throw new IllegalArgumentException("task graph reference must select a value");
        }
        return new ReferencePath(root, List.copyOf(accessors));
    }

    private static boolean isFieldCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-';
    }

    private static IllegalArgumentException unresolved(String path) {
        return new IllegalArgumentException("unresolved task graph reference: " + path);
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

    private record ReferencePath(String root, List<Accessor> accessors) {
    }

    private record Accessor(String field, int index) {
        private static Accessor field(String field) {
            return new Accessor(field, -1);
        }

        private static Accessor index(int index) {
            return new Accessor(null, index);
        }
    }
}
