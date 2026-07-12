package com.mccompanion.runtime.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record TaskEvent(long sequence, String taskId, long revision, String eventType, JsonNode payload, Instant createdAt) {
}
