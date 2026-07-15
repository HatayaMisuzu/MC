package com.mccompanion.runtime.conversation;

public record ConversationOption(String id, String label, String description) {
    public ConversationOption {
        id = required(id, "id"); label = required(label, "label");
        description = description == null ? "" : description.strip();
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }
}
