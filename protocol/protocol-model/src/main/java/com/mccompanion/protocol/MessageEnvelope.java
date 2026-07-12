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
        JsonNode payload) {

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
    }

    public static MessageEnvelope create(MessageType type, long sequence, JsonNode payload) {
        return new MessageEnvelope(ProtocolVersion.CURRENT, type, UUID.randomUUID().toString(), sequence,
                Instant.now(), null, payload);
    }

    public MessageEnvelope correlatedTo(String requestMessageId) {
        return new MessageEnvelope(protocol, type, messageId, sequence, sentAt, requestMessageId, payload);
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
