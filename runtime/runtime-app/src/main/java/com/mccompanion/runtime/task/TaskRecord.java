package com.mccompanion.runtime.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record TaskRecord(
        String taskId,
        String rootTaskId,
        String parentTaskId,
        String companionId,
        TaskType type,
        TaskState state,
        long revision,
        String requestText,
        JsonNode payload,
        String behaviorId,
        long behaviorRevision,
        long controlEpoch,
        boolean reconciliationRequired,
        Instant createdAt,
        Instant updatedAt
) {
}
