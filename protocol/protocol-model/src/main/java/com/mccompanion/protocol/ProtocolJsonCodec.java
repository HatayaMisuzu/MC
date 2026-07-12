package com.mccompanion.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ProtocolJsonCodec {
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = 1_048_576;
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_STRING_LENGTH = 262_144;

    private static final Map<MessageType, Class<?>> PAYLOAD_TYPES = Map.of(
            MessageType.HANDSHAKE_REQUEST, HandshakeRequest.class,
            MessageType.HANDSHAKE_RESPONSE, HandshakeResponse.class,
            MessageType.COMMAND, CommandEnvelope.class,
            MessageType.COMMAND_ACCEPTED, CommandAccepted.class,
            MessageType.BEHAVIOR_EVENT, BehaviorEvent.class,
            MessageType.COMPANION_STATUS, CompanionStatus.class,
            MessageType.ERROR, ErrorEnvelope.class,
            MessageType.HEARTBEAT, Heartbeat.class);

    private final ObjectMapper mapper;
    private final int maxDocumentBytes;

    public ProtocolJsonCodec() {
        this(DEFAULT_MAX_DOCUMENT_BYTES);
    }

    public ProtocolJsonCodec(int maxDocumentBytes) {
        if (maxDocumentBytes < 1_024) {
            throw new IllegalArgumentException("maxDocumentBytes must be at least 1024");
        }
        this.maxDocumentBytes = maxDocumentBytes;
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNumberLength(128)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build();
        this.mapper = JsonMapper.builder(factory)
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public String encode(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            String encoded = mapper.writeValueAsString(value);
            requireSize(encoded);
            return encoded;
        } catch (ProtocolValidationException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new ProtocolValidationException("Unable to encode " + value.getClass().getSimpleName(), invalid);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(type, "type");
        requireSize(json);
        try {
            return mapper.readValue(json, type);
        } catch (Exception invalid) {
            throw new ProtocolValidationException("Invalid JSON for " + type.getSimpleName(), invalid);
        }
    }

    public MessageEnvelope decodeEnvelope(String json) {
        MessageEnvelope envelope = decode(json, MessageEnvelope.class);
        decodeKnownPayload(envelope);
        return envelope;
    }

    public MessageEnvelope envelope(MessageType type, long sequence, Object payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Class<?> expected = PAYLOAD_TYPES.get(type);
        if (!expected.isInstance(payload)) {
            throw new ProtocolValidationException("Payload for " + type + " must be " + expected.getSimpleName());
        }
        try {
            JsonNode node = mapper.valueToTree(payload);
            return new MessageEnvelope(ProtocolVersion.CURRENT, type, UUID.randomUUID().toString(), sequence,
                    java.time.Instant.now(), null, node);
        } catch (ProtocolValidationException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new ProtocolValidationException("Unable to create envelope for " + type, invalid);
        }
    }

    public Object decodeKnownPayload(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        Class<?> expected = PAYLOAD_TYPES.get(envelope.type());
        return decodePayloadUnchecked(envelope, expected);
    }

    public <T> T decodePayload(MessageEnvelope envelope, Class<T> type) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(type, "type");
        Class<?> expected = PAYLOAD_TYPES.get(envelope.type());
        if (!expected.equals(type)) {
            throw new ProtocolValidationException("Envelope type " + envelope.type()
                    + " cannot be decoded as " + type.getSimpleName());
        }
        return type.cast(decodePayloadUnchecked(envelope, type));
    }

    private Object decodePayloadUnchecked(MessageEnvelope envelope, Class<?> type) {
        try {
            return mapper.treeToValue(envelope.payload(), type);
        } catch (Exception invalid) {
            throw new ProtocolValidationException("Invalid payload for " + envelope.type(), invalid);
        }
    }

    public ObjectMapper mapperCopy() {
        return mapper.copy();
    }

    public int maxDocumentBytes() {
        return maxDocumentBytes;
    }

    private void requireSize(String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length > maxDocumentBytes) {
            throw new ProtocolValidationException("Protocol document exceeds " + maxDocumentBytes + " bytes");
        }
    }
}
