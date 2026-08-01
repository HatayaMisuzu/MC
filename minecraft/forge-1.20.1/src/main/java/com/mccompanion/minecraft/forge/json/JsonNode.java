package com.mccompanion.minecraft.forge.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import java.util.Iterator;

/** Small Gson-backed tree API used to keep the Forge production bridge free of Jackson. */
public class JsonNode implements Iterable<JsonNode> {
    final JsonElement value;

    JsonNode(JsonElement value) {
        this.value = value == null ? JsonNull.INSTANCE : value;
    }

    static JsonNode wrap(JsonElement value) {
        if (value == null || value.isJsonNull()) return new JsonNode(JsonNull.INSTANCE);
        if (value.isJsonObject()) return new ObjectNode(value.getAsJsonObject());
        if (value.isJsonArray()) return new ArrayNode(value.getAsJsonArray());
        return new JsonNode(value);
    }

    public JsonNode path(String field) {
        return value.isJsonObject() ? wrap(value.getAsJsonObject().get(field)) : wrap(null);
    }

    public boolean has(String field) {
        return value.isJsonObject() && value.getAsJsonObject().has(field);
    }

    public boolean hasNonNull(String field) {
        return has(field) && !value.getAsJsonObject().get(field).isJsonNull();
    }

    public boolean isObject() { return value.isJsonObject(); }
    public boolean isArray() { return value.isJsonArray(); }
    public boolean isNull() { return value.isJsonNull(); }
    public boolean isEmpty() { return size() == 0; }
    public boolean isTextual() { return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(); }
    public boolean isIntegralNumber() {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        double number = value.getAsDouble();
        return Double.isFinite(number) && number == Math.rint(number);
    }
    public boolean canConvertToInt() {
        if (!isIntegralNumber()) return false;
        double number = value.getAsDouble();
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
    }

    public String asText() { return asText(""); }
    public String asText(String fallback) {
        if (value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try { return value.getAsString(); } catch (RuntimeException invalid) { return fallback; }
    }
    public int asInt() { return asInt(0); }
    public int asInt(int fallback) {
        try { return value.isJsonPrimitive() ? value.getAsInt() : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
    public long asLong() { return asLong(0L); }
    public long asLong(long fallback) {
        try { return value.isJsonPrimitive() ? value.getAsLong() : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
    public double asDouble() { return asDouble(0.0D); }
    public double asDouble(double fallback) {
        try { return value.isJsonPrimitive() ? value.getAsDouble() : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
    public boolean asBoolean() { return asBoolean(false); }
    public boolean asBoolean(boolean fallback) {
        try { return value.isJsonPrimitive() ? value.getAsBoolean() : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
    public int size() {
        return value.isJsonArray() ? value.getAsJsonArray().size()
                : value.isJsonObject() ? value.getAsJsonObject().size() : 0;
    }

    @Override public Iterator<JsonNode> iterator() {
        if (!value.isJsonArray()) return java.util.Collections.emptyIterator();
        Iterator<JsonElement> delegate = value.getAsJsonArray().iterator();
        return new Iterator<>() {
            @Override public boolean hasNext() { return delegate.hasNext(); }
            @Override public JsonNode next() { return wrap(delegate.next()); }
        };
    }

    @Override public String toString() { return value.toString(); }
}
