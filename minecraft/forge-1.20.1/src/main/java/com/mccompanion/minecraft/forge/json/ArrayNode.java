package com.mccompanion.minecraft.forge.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ArrayNode extends JsonNode {
    ArrayNode(JsonArray value) { super(value); }
    private JsonArray array() { return value.getAsJsonArray(); }

    public ArrayNode add(String value) { array().add(value); return this; }
    public ArrayNode add(int value) { array().add(value); return this; }
    public ArrayNode add(long value) { array().add(value); return this; }
    public ArrayNode add(double value) { array().add(value); return this; }
    public ArrayNode add(boolean value) { array().add(value); return this; }
    public ArrayNode add(JsonNode value) {
        array().add(value == null ? com.google.gson.JsonNull.INSTANCE : value.value); return this;
    }
    public ObjectNode addObject() {
        JsonObject child = new JsonObject(); array().add(child); return new ObjectNode(child);
    }
}
