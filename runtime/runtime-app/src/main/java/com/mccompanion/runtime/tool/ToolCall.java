package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

public record ToolCall(String callId, String name, JsonNode arguments) {
    public ToolCall {
        if (callId == null || callId.isBlank() || callId.length() > 128) {
            throw new IllegalArgumentException("tool call id is invalid");
        }
        if (name == null || !name.matches("[a-z][a-z0-9_.-]{2,63}")) {
            throw new IllegalArgumentException("tool name is invalid");
        }
        arguments = arguments == null ? Json.object() : arguments.deepCopy();
        if (!arguments.isObject()) throw new IllegalArgumentException("tool arguments must be an object");
        if (Json.write(arguments).length() > 32_768) throw new IllegalArgumentException("tool arguments are too large");
    }
}
