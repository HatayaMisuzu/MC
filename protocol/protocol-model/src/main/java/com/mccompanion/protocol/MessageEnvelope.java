package com.mccompanion.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageEnvelope(
        ProtocolVersion protocol,
        MessageType type,
        String messageId,
        long sequence,
        Instant sentAt,
        String correlationId,
        JsonNode payload,
        String sessionId,
        String worldId) {

    public MessageEnvelope(ProtocolVersion protocol, MessageType type, String messageId, long sequence,
                           Instant sentAt, String correlationId, JsonNode payload) {
        this(protocol, type, messageId, sequence, sentAt, correlationId, payload, null, null);
    }

    public MessageEnvelope {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(type, "type");
        messageId = ProtocolFields.identifier(messageId, "messageId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Objects.requireNonNull(sentAt, "sentAt");
        if (correlationId != null) {
            correlationId = ProtocolFields.identifier(correlationId, "correlationId");
        }
        Objects.requireNonNull(payload, "payload");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        payload = payload.deepCopy();
        if (sessionId != null) sessionId = ProtocolFields.identifier(sessionId, "sessionId");
        if (worldId != null) worldId = ProtocolFields.identifier(worldId, "worldId");
    }

    public static MessageEnvelope create(MessageType type, long sequence, JsonNode payload) {
        return new MessageEnvelope(ProtocolVersion.CURRENT, type, UUID.randomUUID().toString(), sequence,
                Instant.now(), null, payload);
    }

    public MessageEnvelope correlatedTo(String requestMessageId) {
        return new MessageEnvelope(protocol, type, messageId, sequence, sentAt, requestMessageId, payload, sessionId, worldId);
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
