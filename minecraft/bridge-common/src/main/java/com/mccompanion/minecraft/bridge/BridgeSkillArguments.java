package com.mccompanion.minecraft.bridge;

import com.mccompanion.core.body.SkillParameters;
import com.mccompanion.core.body.build.SmallBlueprint;
import java.util.Map;

/** One command argument decoder shared by the Jackson and Gson boundaries. */
public final class BridgeSkillArguments {
    private BridgeSkillArguments() { }
    public static SkillParameters decode(Map<String, Object> fields) {
        try {
            return decodeBounded(fields);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static SkillParameters decodeBounded(Map<String, Object> fields) {
        Value parameters = new Value(fields);
        if (!parameters.path("capability").isTextual()) return null;
        Value values = parameters.path("parameters");
        String item = values.path("item").asText(values.path("itemId").asText(""));
        int quantity = values.path("quantity").asInt(1);
        Value target = values.path("container").isObject() ? values.path("container")
                : values.path("station").isObject() ? values.path("station") : values.path("target");
        Integer x = target.path("x").canConvertToInt() ? target.path("x").asInt() : null;
        Integer y = target.path("y").canConvertToInt() ? target.path("y").asInt() : null;
        Integer z = target.path("z").canConvertToInt() ? target.path("z").asInt() : null;
        return new SkillParameters(parameters.path("capability").asText(), item, quantity,
                values.path("allowPartial").asBoolean(false),
                target.path("dimension").asText("minecraft:overworld"), x, y, z,
                values.path("entityId").asText(""), values.path("face").asText("UP"),
                values.path("hand").asText("MAIN_HAND"),
                values.path("sessionToken").asText(""),
                values.path("slot").canConvertToInt() ? values.path("slot").asInt() : null,
                values.path("button").canConvertToInt() ? values.path("button").asInt() : null,
                values.path("action").asText(""),
                values.path("durationTicks").canConvertToInt()
                        ? values.path("durationTicks").asInt() : null,
                values.path("partnerEntityId").asText(""),
                stringList(values.path("allowedBreakBlocks")),
                stringList(values.path("allowedPlaceBlocks")),
                values.path("maxBreakBlocks").asInt(0),
                values.path("maxPlaceBlocks").asInt(0),
                values.path("maxRiskUnits").asInt(8),
                values.path("targetReferenceKind").asText(""),
                values.path("targetName").asText(""),
                values.path("targetRuntimeId").canConvertToInt() ? values.path("targetRuntimeId").asInt() : null,
                optionalDouble(values.path("minimumDistance")),
                optionalDouble(values.path("maximumDistance")),
                values.path("lostTimeoutTicks").canConvertToInt() ? values.path("lostTimeoutTicks").asInt() : null,
                smallBlueprint(values.path("blueprint")));
    }

    private static java.util.List<String> stringList(Value value) {
        if (!value.isArray()) return java.util.List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        value.forEach(entry -> { if (entry.isTextual()) result.add(entry.asText()); });
        return java.util.List.copyOf(result);
    }

    private static SmallBlueprint smallBlueprint(Value value) {
        if (!value.isObject()) return null;
        Value anchor = value.path("anchor");
        Value size = value.path("maxSize");
        java.util.ArrayList<SmallBlueprint.Block> blocks = new java.util.ArrayList<>();
        for (Value block : value.path("blocks")) {
            Value position = block.path("position");
            java.util.Map<String, String> state = new java.util.LinkedHashMap<>();
            block.path("state").object().forEach((key, entry) -> state.put(key, new Value(entry).asText()));
            blocks.add(new SmallBlueprint.Block(new SmallBlueprint.Offset(
                    position.path("x").asInt(), position.path("y").asInt(), position.path("z").asInt()),
                    block.path("block").asText(), state, stringList(block.path("alternatives"))));
        }
        Value support = value.path("temporarySupport");
        return new SmallBlueprint(new SmallBlueprint.Anchor(anchor.path("dimension").asText(),
                anchor.path("x").asInt(), anchor.path("y").asInt(), anchor.path("z").asInt()),
                new SmallBlueprint.Size(size.path("x").asInt(), size.path("y").asInt(), size.path("z").asInt()),
                blocks, new SmallBlueprint.SupportPolicy(stringList(support.path("blocks")),
                        support.path("maxBlocks").asInt(), support.path("cleanup").asBoolean(true)));
    }

    private static Double optionalDouble(Value value) {
        return value.isNumber() ? value.asDouble() : null;
    }

    // A private view over already bounded plain values; no codec or game object crosses this boundary.
    private record Value(Object value) implements Iterable<Value> {
        @SuppressWarnings("unchecked") Map<String, Object> object() {
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
        Value path(String key) { return new Value(object().get(key)); }
        boolean isObject() { return value instanceof Map<?, ?>; }
        boolean isArray() { return value instanceof java.util.List<?>; }
        boolean isTextual() { return value instanceof String; }
        boolean isNumber() { return value instanceof Number; }
        String asText() { return asText(""); }
        String asText(String fallback) { return value instanceof String text ? text : fallback; }
        boolean asBoolean(boolean fallback) { return value instanceof Boolean flag ? flag : fallback; }
        boolean canConvertToInt() {
            return value instanceof Number number && number.doubleValue() == number.intValue();
        }
        int asInt() { return asInt(0); }
        int asInt(int fallback) {
            if (value == null) return fallback;
            if (!canConvertToInt()) throw new IllegalArgumentException("Expected a bounded integer");
            return ((Number) value).intValue();
        }
        double asDouble() { return ((Number) value).doubleValue(); }
        public java.util.Iterator<Value> iterator() {
            return value instanceof java.util.List<?> list
                    ? list.stream().map(Value::new).iterator() : java.util.Collections.emptyIterator();
        }
    }
}
