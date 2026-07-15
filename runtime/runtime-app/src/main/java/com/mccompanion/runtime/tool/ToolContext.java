package com.mccompanion.runtime.tool;

public record ToolContext(String controllerId, String brainSessionId, String companionId) {
    public ToolContext {
        controllerId = required(controllerId, "controllerId");
        brainSessionId = required(brainSessionId, "brainSessionId");
        companionId = required(companionId, "companionId");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value.strip();
    }
}
