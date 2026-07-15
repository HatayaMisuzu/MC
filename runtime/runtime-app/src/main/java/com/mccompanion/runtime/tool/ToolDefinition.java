package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

import java.time.Duration;

public record ToolDefinition(String name, String version, String description, JsonNode inputSchema,
                             String risk, String permission, Duration timeout, boolean idempotent) {
    public ToolDefinition {
        if (name == null || !name.matches("[a-z][a-z0-9_.-]{2,63}")) {
            throw new IllegalArgumentException("invalid tool name");
        }
        version = required(version, "version");
        description = required(description, "description");
        inputSchema = inputSchema == null ? Json.object() : inputSchema.deepCopy();
        if (!inputSchema.isObject()) throw new IllegalArgumentException("inputSchema must be an object");
        risk = required(risk, "risk");
        permission = required(permission, "permission");
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("tool timeout is invalid");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.strip();
    }
}
