package com.mccompanion.protocol;

import java.util.Objects;

public record HandshakeResponse(
        boolean accepted,
        ProtocolVersion protocol,
        String sessionId,
        String runtimeVersion,
        ControlPolicy controlPolicy,
        String failureCode,
        String message) {

    public HandshakeResponse {
        Objects.requireNonNull(protocol, "protocol");
        if (accepted) {
            sessionId = ProtocolFields.identifier(sessionId, "sessionId");
            runtimeVersion = ProtocolFields.identifier(runtimeVersion, "runtimeVersion");
            Objects.requireNonNull(controlPolicy, "controlPolicy");
            if (failureCode != null) {
                throw new IllegalArgumentException("accepted handshakes cannot contain a failureCode");
            }
        } else {
            if (sessionId != null || controlPolicy != null) {
                throw new IllegalArgumentException("rejected handshakes cannot establish a session");
            }
            failureCode = ProtocolFields.identifier(failureCode, "failureCode");
            message = ProtocolFields.text(message, "message");
            if (runtimeVersion != null) {
                runtimeVersion = ProtocolFields.identifier(runtimeVersion, "runtimeVersion");
            }
        }
        if (message != null) {
            message = ProtocolFields.text(message, "message");
        }
    }

    public static HandshakeResponse accepted(
            ProtocolVersion negotiated,
            String sessionId,
            String runtimeVersion,
            ControlPolicy policy) {
        return new HandshakeResponse(true, negotiated, sessionId, runtimeVersion, policy, null, null);
    }

    public static HandshakeResponse rejected(
            ProtocolVersion runtimeProtocol,
            String runtimeVersion,
            String failureCode,
            String message) {
        return new HandshakeResponse(false, runtimeProtocol, null, runtimeVersion, null, failureCode, message);
    }
}
