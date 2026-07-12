package com.mccompanion.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.protocol.CommandEnvelope;
import com.mccompanion.protocol.CommandType;
import com.mccompanion.protocol.MessageEnvelope;
import com.mccompanion.protocol.MessageType;
import com.mccompanion.protocol.ProtocolJsonCodec;
import com.mccompanion.protocol.ProtocolVersion;
import com.mccompanion.runtime.session.RuntimeSession;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Encodes outbound commands exclusively through the shared, strictly validated protocol model. */
public final class ProtocolCommandSender {
    private final Clock clock;
    private final ProtocolJsonCodec codec;

    public ProtocolCommandSender() {
        this(Clock.systemUTC(), new ProtocolJsonCodec());
    }

    public ProtocolCommandSender(Clock clock) {
        this(clock, new ProtocolJsonCodec());
    }

    ProtocolCommandSender(Clock clock, ProtocolJsonCodec codec) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public void send(RuntimeSession session, String commandId, CommandType command, String companionId,
                     String taskId, String leaseId, long controlEpoch, long expectedRevision, JsonNode arguments) {
        Objects.requireNonNull(session, "session");
        if (!session.peer().isOpen()) {
            throw new IllegalStateException("RUNTIME_OFFLINE");
        }
        Map<String, JsonNode> values = toArguments(arguments);
        CommandEnvelope commandPayload = new CommandEnvelope(commandId, command, companionId, taskId, leaseId,
                controlEpoch, expectedRevision, values);
        JsonNode payload = codec.mapperCopy().valueToTree(commandPayload);
        MessageEnvelope envelope = new MessageEnvelope(
                ProtocolVersion.parse(session.handshake().protocol()), MessageType.COMMAND,
                UUID.randomUUID().toString(), session.nextSequence(), clock.instant(), null, payload);
        session.peer().send(codec.encode(envelope));
    }

    private static Map<String, JsonNode> toArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return Map.of();
        }
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("Command arguments must be a JSON object");
        }
        Map<String, JsonNode> values = new LinkedHashMap<>();
        arguments.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().deepCopy()));
        return Map.copyOf(values);
    }
}
