package com.mccompanion.minecraft.forge;

import com.mccompanion.minecraft.bridge.BridgeCodec;
import com.mccompanion.minecraft.bridge.BridgeValues;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GsonBridgeCodec implements BridgeCodec {
    private final Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();
    @Override public Map<String, Object> decode(String text) throws IOException {
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value = read(reader, 0);
            if (!(value instanceof Map<?, ?>) || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Bridge object required");
            @SuppressWarnings("unchecked") var object = (Map<String, Object>) value;
            return BridgeValues.freeze(object);
        } catch (IllegalStateException | NumberFormatException invalid) { throw new IOException("Invalid bridge JSON", invalid); }
    }
    private static Object read(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("Bridge nesting exceeds bounds");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var map = new LinkedHashMap<String, Object>(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (map.containsKey(key)) throw new IOException("Duplicate bridge key");
                    map.put(key, read(reader, depth + 1));
                }
                reader.endObject(); yield map;
            }
            case BEGIN_ARRAY -> {
                var list = new ArrayList<>(); reader.beginArray();
                while (reader.hasNext()) list.add(read(reader, depth + 1));
                reader.endArray(); yield list;
            }
            case STRING -> reader.nextString();
            case NUMBER -> new java.math.BigDecimal(reader.nextString());
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield null; }
            default -> throw new IOException("Invalid bridge value");
        };
    }
    @Override public String encode(Map<String, Object> value) { return gson.toJson(value); }
}
