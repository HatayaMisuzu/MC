package com.mccompanion.runtime.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.task.TaskType;

public record Intent(TaskType type, JsonNode arguments, String originalText) {
    public Intent {
        arguments = arguments == null ? Json.object() : arguments.deepCopy();
        originalText = originalText == null ? "" : originalText;
    }
}
