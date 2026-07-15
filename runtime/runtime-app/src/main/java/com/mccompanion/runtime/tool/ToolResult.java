package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

public record ToolResult(String callId, String toolName, boolean success, String code,
                         JsonNode observation, boolean terminal) {
    public ToolResult {
        observation = observation == null ? Json.object() : observation.deepCopy();
        if (code == null || code.isBlank()) code = success ? "OK" : "TOOL_FAILED";
    }

    public static ToolResult rejected(ToolCall call, String code, String message) {
        return new ToolResult(call.callId(), call.name(), false, code,
                Json.object().put("message", message), true);
    }
}
