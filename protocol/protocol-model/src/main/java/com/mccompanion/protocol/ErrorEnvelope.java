package com.mccompanion.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ErrorEnvelope(
        String failureCode,
        String message,
        boolean retryable,
        String commandId,
        Map<String, String> details,
        Instant occurredAt) {

    public ErrorEnvelope {
        failureCode = ProtocolFields.identifier(failureCode, "failureCode");
        message = ProtocolFields.text(message, "message");
        if (commandId != null) {
            commandId = ProtocolFields.identifier(commandId, "commandId");
        }
        details = ProtocolFields.immutableMap(details == null ? Map.of() : details, "details");
        details.forEach((key, value) -> {
            ProtocolFields.identifier(key, "detail key");
            ProtocolFields.text(value, "detail value");
        });
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
