package com.mccompanion.minecraft.forge.json;

import com.google.gson.JsonObject;

public final class ObjectNode extends JsonNode {
    ObjectNode(JsonObject value) { super(value); }
    private JsonObject object() { return value.getAsJsonObject(); }

    public ObjectNode put(String field, String value) {
        if (value == null) object().add(field, com.google.gson.JsonNull.INSTANCE);
        else object().addProperty(field, value);
        return this;
    }
    public ObjectNode put(String field, boolean value) { object().addProperty(field, value); return this; }
    public ObjectNode put(String field, int value) { object().addProperty(field, value); return this; }
    public ObjectNode put(String field, long value) { object().addProperty(field, value); return this; }
    public ObjectNode put(String field, float value) { object().addProperty(field, value); return this; }
    public ObjectNode put(String field, double value) { object().addProperty(field, value); return this; }
    public ObjectNode set(String field, JsonNode node) {
        object().add(field, node == null ? com.google.gson.JsonNull.INSTANCE : node.value);
        return this;
    }
    public ObjectNode putObject(String field) {
        JsonObject child = new JsonObject(); object().add(field, child); return new ObjectNode(child);
    }
    public ObjectNode with(String field) {
        if (object().has(field) && object().get(field).isJsonObject()) {
            return new ObjectNode(object().getAsJsonObject(field));
        }
        return putObject(field);
    }
    public ArrayNode putArray(String field) {
        com.google.gson.JsonArray child = new com.google.gson.JsonArray();
        object().add(field, child); return new ArrayNode(child);
    }
}
