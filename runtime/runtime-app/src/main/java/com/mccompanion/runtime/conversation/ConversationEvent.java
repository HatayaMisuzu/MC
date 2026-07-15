package com.mccompanion.runtime.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ConversationEvent(String eventId, String companionId, String planId, String questionId,
                                String direction, String kind, String content, JsonNode payload,
                                boolean gameDelivered, Instant createdAt) { }
