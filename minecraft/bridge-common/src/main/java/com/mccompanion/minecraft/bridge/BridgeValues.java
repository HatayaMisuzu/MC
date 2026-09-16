package com.mccompanion.minecraft.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Copies codec output before thread handoff, rejecting arbitrary objects and unbounded trees. */
public final class BridgeValues {
    private BridgeValues() { }
    public static Map<String, Object> freeze(Map<String, ?> source) {
        @SuppressWarnings("unchecked") var value = (Map<String, Object>) copy(source, 0, new int[1]);
        return value;
    }
    private static Object copy(Object value, int depth, int[] count) {
        if (depth > 64 || ++count[0] > 65_536) throw new IllegalArgumentException("Bridge payload exceeds bounds");
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof String text) {
            if (text.length() > 262_144) throw new IllegalArgumentException("Bridge string exceeds bounds");
            return text;
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Non-finite bridge number");
            if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long
                    || number instanceof java.math.BigInteger || number instanceof java.math.BigDecimal) return number;
            return number.doubleValue();
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text) || text.length() > 512) throw new IllegalArgumentException("Invalid bridge key");
                result.put(text, copy(item, depth + 1, count));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof java.util.List<?> list) {
            return Collections.unmodifiableList(list.stream().map(item -> copy(item, depth + 1, count)).toList());
        }
        throw new IllegalArgumentException("Native or codec object leaked into bridge data");
    }
}
