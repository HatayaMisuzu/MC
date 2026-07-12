package com.mccompanion.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

public record CommandReply(
        boolean accepted,
        String code,
        String message,
        String taskId,
        String state,
        long revision,
        long controlEpoch,
        JsonNode data
) {
    public CommandReply {
        data = data == null ? Json.object() : data.deepCopy();
    }

    public ObjectNode toJson() {
        ObjectNode result = Json.object().put("accepted", accepted)
                .put("code", code).put("message", message)
                .put("revision", revision).put("controlEpoch", controlEpoch);
        if (taskId != null) result.put("taskId", taskId);
        if (state != null) result.put("state", state);
        result.set("data", data);
        return result;
    }

    public static CommandReply fromJson(JsonNode node) {
        return new CommandReply(node.path("accepted").asBoolean(false), node.path("code").asText("UNKNOWN"),
                node.path("message").asText(""), node.path("taskId").asText(null),
                node.path("state").asText(null), node.path("revision").asLong(-1),
                node.path("controlEpoch").asLong(-1), node.path("data"));
    }

    public static CommandReply rejected(String code, String message) {
        return new CommandReply(false, code, message, null, null, -1, -1, Json.object());
    }
}
