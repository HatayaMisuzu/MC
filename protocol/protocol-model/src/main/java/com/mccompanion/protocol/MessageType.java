package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageType {
    HANDSHAKE_REQUEST,
    HANDSHAKE_RESPONSE,
    COMMAND,
    COMMAND_ACCEPTED,
    BEHAVIOR_EVENT,
    COMPANION_STATUS,
    ERROR,
    HEARTBEAT;

    @JsonCreator
    public static MessageType fromWire(String value) {
        return WireValue.parse(MessageType.class, value);
    }

    @JsonValue
    public String toWire() {
        return WireValue.of(this);
    }
}
