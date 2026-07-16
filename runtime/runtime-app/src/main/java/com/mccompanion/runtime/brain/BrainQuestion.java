package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.conversation.ConversationOption;
import com.mccompanion.runtime.json.Json;

import java.util.List;

/** Structured user question returned by an external brain. */
public record BrainQuestion(String prompt, String reason, List<ConversationOption> options,
                            boolean freeTextAllowed, JsonNode context, String taskId) {
    public BrainQuestion {
        prompt = required(prompt, "prompt");
        reason = required(reason, "reason");
        options = options == null ? List.of() : List.copyOf(options);
        if (options.isEmpty() || options.size() > 3) {
            throw new IllegalArgumentException("brain question requires 1..3 options");
        }
        if (options.stream().map(ConversationOption::id).distinct().count() != options.size()) {
            throw new IllegalArgumentException("brain question option ids must be unique");
        }
        context = context == null || context.isMissingNode() || context.isNull() ? Json.object() : context.deepCopy();
        taskId = taskId == null || taskId.isBlank() ? null : taskId.strip();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }
}
