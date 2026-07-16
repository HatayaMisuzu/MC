package com.mccompanion.runtime.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record WaitingQuestion(String questionId, String planId, String brainSessionId, String taskId,
                              String taskGraphExecutionId,
                              String companionId, String prompt,
                              String reason, List<ConversationOption> options, boolean freeTextAllowed,
                              String state, JsonNode context, JsonNode answer,
                              Instant createdAt, Instant updatedAt, Instant expiresAt) {
    public WaitingQuestion(String questionId, String planId, String companionId, String prompt,
                           String reason, List<ConversationOption> options, boolean freeTextAllowed,
                           String state, JsonNode context, JsonNode answer,
                           Instant createdAt, Instant updatedAt, Instant expiresAt) {
        this(questionId, planId, null, null, null, companionId, prompt, reason, options, freeTextAllowed,
                state, context, answer, createdAt, updatedAt, expiresAt);
    }
}
