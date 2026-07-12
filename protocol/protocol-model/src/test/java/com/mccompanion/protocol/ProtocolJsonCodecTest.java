package com.mccompanion.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolJsonCodecTest {
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");
    private static final String TOKEN = "0123456789abcdef";
    private final ProtocolJsonCodec codec = new ProtocolJsonCodec();

    @Test
    void roundTripsHandshakeAndRedactsTokenFromDiagnostics() {
        HandshakeRequest request = handshake();

        String json = codec.encode(request);
        HandshakeRequest decoded = codec.decode(json, HandshakeRequest.class);

        assertEquals(request, decoded);
        assertTrue(json.contains(TOKEN), "the pairing token must remain available on the wire");
        assertFalse(request.toString().contains(TOKEN), "diagnostic rendering must redact the token");
        assertTrue(request.toString().contains("<redacted>"));
    }

    @Test
    void createsValidTypedEnvelopeAndDecodesItsKnownPayload() {
        MessageEnvelope envelope = codec.envelope(MessageType.HANDSHAKE_REQUEST, 4, handshake());

        MessageEnvelope decoded = codec.decodeEnvelope(codec.encode(envelope));
        HandshakeRequest payload = codec.decodePayload(decoded, HandshakeRequest.class);

        assertEquals(MessageType.HANDSHAKE_REQUEST, decoded.type());
        assertEquals(4, decoded.sequence());
        assertEquals(handshake(), payload);
        assertThrows(ProtocolValidationException.class,
                () -> codec.decodePayload(decoded, Heartbeat.class));
    }

    @Test
    void rejectsPayloadWhoseRuntimeTypeDoesNotMatchMessageType() {
        assertThrows(ProtocolValidationException.class,
                () -> codec.envelope(MessageType.ERROR, 0, handshake()));
    }

    @Test
    void rejectsUnknownDuplicateAndTrailingJsonContent() {
        String heartbeat = """
                {"protocol":"mc-companion/1","type":"heartbeat","messageId":"msg-1","sequence":0,
                 "sentAt":"2026-07-12T00:00:00Z","correlationId":null,
                 "payload":{"sessionId":"session-1","sequence":0,
                            "sentAt":"2026-07-12T00:00:00Z","unexpected":true}}
                """;
        assertThrows(ProtocolValidationException.class, () -> codec.decodeEnvelope(heartbeat));

        String duplicate = """
                {"protocol":"mc-companion/1","protocol":"mc-companion/1","type":"heartbeat",
                 "messageId":"msg-1","sequence":0,"sentAt":"2026-07-12T00:00:00Z",
                 "correlationId":null,"payload":{"sessionId":"session-1","sequence":0,
                 "sentAt":"2026-07-12T00:00:00Z"}}
                """;
        assertThrows(ProtocolValidationException.class, () -> codec.decodeEnvelope(duplicate));

        String valid = codec.encode(codec.envelope(MessageType.HEARTBEAT, 0,
                new Heartbeat("session-1", 0, NOW)));
        assertThrows(ProtocolValidationException.class,
                () -> codec.decodeEnvelope(valid + " {}"));
    }

    @Test
    void rejectsOversizedDocumentsBeforeParsing() {
        ProtocolJsonCodec smallCodec = new ProtocolJsonCodec(1_024);
        String oversized = "\"" + "x".repeat(1_024) + "\"";

        ProtocolValidationException failure = assertThrows(ProtocolValidationException.class,
                () -> smallCodec.decode(oversized, String.class));

        assertTrue(failure.getMessage().contains("exceeds"));
    }

    @Test
    void envelopeAndCommandDefensivelyCopyJsonTrees() {
        ObjectNode originalPayload = JsonNodeFactory.instance.objectNode().put("value", 1);
        MessageEnvelope envelope = new MessageEnvelope(ProtocolVersion.CURRENT, MessageType.ERROR,
                "message-1", 0, NOW, null, originalPayload);
        originalPayload.put("value", 2);
        JsonNode returnedPayload = envelope.payload();
        ((ObjectNode) returnedPayload).put("value", 3);
        assertEquals(1, envelope.payload().path("value").asInt());

        ObjectNode argument = JsonNodeFactory.instance.objectNode().put("x", 10);
        CommandEnvelope command = new CommandEnvelope("command-1", CommandType.QUERY_STATUS,
                "companion-1", null, null, 0, 0, Map.of("target", argument));
        argument.put("x", 99);
        ((ObjectNode) command.arguments().get("target")).put("x", 77);
        assertEquals(10, command.arguments().get("target").path("x").asInt());
        assertThrows(UnsupportedOperationException.class,
                () -> command.arguments().put("other", JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void rejectsNonObjectEnvelopePayload() {
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope(
                ProtocolVersion.CURRENT, MessageType.HEARTBEAT, "message-1", 0, NOW, null,
                JsonNodeFactory.instance.arrayNode()));
    }

    private static HandshakeRequest handshake() {
        return new HandshakeRequest(
                ProtocolVersion.CURRENT,
                "0.1.0-alpha",
                "1.21.1",
                PlatformLoader.FABRIC,
                "world-1",
                CapabilitySet.builder()
                        .available("movement", "1")
                        .unavailable("optional-adapter", "not installed")
                        .build(),
                TOKEN);
    }
}
