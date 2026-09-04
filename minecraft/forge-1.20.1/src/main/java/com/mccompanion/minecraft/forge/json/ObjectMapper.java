package com.mccompanion.minecraft.forge.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;

public final class ObjectMapper {
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    public JsonNode valueToTree(Object value) { return JsonNode.wrap(GSON.toJsonTree(value)); }
    public ObjectNode createObjectNode() { return new ObjectNode(new JsonObject()); }
    public ArrayNode createArrayNode() { return new ArrayNode(new JsonArray()); }
    public JsonNode readTree(String text) throws IOException {
        try { return JsonNode.wrap(JsonParser.parseString(text)); }
        catch (RuntimeException invalid) { throw new IOException("invalid JSON", invalid); }
    }
    public String writeValueAsString(JsonNode value) throws IOException {
        if (value == null) throw new IOException("JSON value is null");
        return value.toString();
    }
}
